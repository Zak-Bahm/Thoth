package com.bahm.thoth.inference

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.min

@Singleton
class ModelDownloadService @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "ModelDownload"
        private const val BUFFER_SIZE = 256 * 1024
        private const val CHUNK_COUNT = 4
        private const val MAX_CONCURRENT = 2
        private const val MAX_RETRIES = 5
        private const val INITIAL_BACKOFF_MS = 2_000L
    }

    data class DownloadProgress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val cancelled = AtomicBoolean(false)

    fun download(url: String, filename: String): Flow<DownloadProgress> = flow {
        cancelled.set(false)
        val downloadDir = getDownloadDir()
        val targetFile = File(downloadDir, filename)
        val tempFile = File(downloadDir, "$filename.part")
        val chunkDir = File(downloadDir, "$filename.chunks")

        Log.d(TAG, "Starting download: $url -> $filename")
        Log.d(TAG, "Download dir: ${downloadDir.absolutePath}, free space: ${downloadDir.freeSpace / 1_000_000} MB")
        Log.d(TAG, "Parallel connections: $MAX_CONCURRENT, chunks: $CHUNK_COUNT")

        val totalBytes = getContentLength(url)
        if (totalBytes <= 0) {
            Log.w(TAG, "Cannot determine file size, falling back to single-stream download")
            downloadSingleStream(url, tempFile, targetFile, totalBytes)
            return@flow
        }
        Log.d(TAG, "Total file size: ${totalBytes / 1_000_000} MB")

        if (!chunkDir.exists()) chunkDir.mkdirs()
        val chunks = buildChunks(totalBytes, chunkDir)

        chunks.forEachIndexed { i, chunk ->
            val downloaded = if (chunk.file.exists()) chunk.file.length() else 0L
            Log.d(TAG, "Chunk $i: ${chunk.startByte}-${chunk.endByte} (${(chunk.endByte - chunk.startByte + 1) / 1_000_000} MB), existing: ${downloaded / 1_000_000} MB")
        }

        val totalDownloaded = AtomicLong(chunks.sumOf { if (it.file.exists()) it.file.length() else 0L })
        val semaphore = Semaphore(MAX_CONCURRENT)

        val startTime = System.currentTimeMillis()
        var lastLogTime = startTime
        var lastLogBytes = totalDownloaded.get()

        coroutineScope {
            val jobs = chunks.mapIndexed { index, chunk ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        downloadChunk(url, chunk, index, totalDownloaded)
                    }
                }
            }

            while (jobs.any { it.isActive }) {
                coroutineContext.ensureActive()
                if (cancelled.get()) {
                    jobs.forEach { it.cancel() }
                    throw RuntimeException("Download cancelled")
                }

                val downloaded = totalDownloaded.get()
                emit(DownloadProgress(downloaded, totalBytes))

                val now = System.currentTimeMillis()
                if (now - lastLogTime >= 5_000) {
                    val elapsed = (now - lastLogTime) / 1_000.0
                    val deltaBytes = downloaded - lastLogBytes
                    val speedMbps = (deltaBytes * 8.0) / (elapsed * 1_000_000)
                    val pct = "%.1f%%".format(downloaded * 100.0 / totalBytes)
                    Log.d(TAG, "Progress: ${downloaded / 1_000_000} MB / ${totalBytes / 1_000_000} MB ($pct) — ${"%.1f".format(speedMbps)} Mbps")
                    lastLogTime = now
                    lastLogBytes = downloaded
                }

                delay(250)
            }

            jobs.forEach { it.await() }
        }

        emit(DownloadProgress(totalBytes, totalBytes))
        val elapsed = (System.currentTimeMillis() - startTime) / 1_000.0
        val avgSpeed = (totalBytes * 8.0) / (elapsed * 1_000_000)
        Log.d(TAG, "All chunks complete in ${"%.1f".format(elapsed)}s (${"%.1f".format(avgSpeed)} Mbps avg)")

        mergeChunks(chunks, tempFile)
        tempFile.renameTo(targetFile)
        chunkDir.deleteRecursively()
        Log.d(TAG, "File saved: ${targetFile.absolutePath} (${targetFile.length() / 1_000_000} MB)")
    }.flowOn(Dispatchers.IO)

    private fun getContentLength(url: String): Long {
        val request = Request.Builder().url(url).head().build()
        val response = client.newCall(request).execute()
        response.use { resp ->
            Log.d(TAG, "HEAD ${resp.code}, Content-Length: ${resp.header("Content-Length")}")
            return resp.header("Content-Length")?.toLongOrNull() ?: -1L
        }
    }

    private data class Chunk(
        val startByte: Long,
        val endByte: Long,
        val file: File,
    )

    private fun buildChunks(totalBytes: Long, chunkDir: File): List<Chunk> {
        val chunkSize = totalBytes / CHUNK_COUNT
        return (0 until CHUNK_COUNT).map { i ->
            val start = i * chunkSize
            val end = if (i == CHUNK_COUNT - 1) totalBytes - 1 else (start + chunkSize - 1)
            Chunk(start, end, File(chunkDir, "chunk_$i"))
        }
    }

    private suspend fun downloadChunk(
        url: String,
        chunk: Chunk,
        index: Int,
        totalDownloaded: AtomicLong,
    ) {
        val chunkTotal = chunk.endByte - chunk.startByte + 1
        var existingBytes = if (chunk.file.exists()) chunk.file.length() else 0L

        if (existingBytes >= chunkTotal) {
            Log.d(TAG, "Chunk $index: already complete")
            return
        }

        var attempt = 0
        while (existingBytes < chunkTotal) {
            val rangeStart = chunk.startByte + existingBytes
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=$rangeStart-${chunk.endByte}")
                .build()

            Log.d(TAG, "Chunk $index: requesting bytes $rangeStart-${chunk.endByte} (attempt ${attempt + 1})")

            val response = client.newCall(request).execute()
            response.use { resp ->
                if (resp.code == 429) {
                    val retryAfter = resp.header("Retry-After")?.toLongOrNull()
                    val backoffMs = if (retryAfter != null) {
                        retryAfter * 1_000
                    } else {
                        INITIAL_BACKOFF_MS * (1L shl min(attempt, 4))
                    }
                    attempt++

                    if (attempt > MAX_RETRIES) {
                        throw RuntimeException("Chunk $index: max retries exceeded after $MAX_RETRIES 429 responses")
                    }

                    Log.w(TAG, "Chunk $index: 429 Too Many Requests, backing off ${backoffMs}ms (attempt $attempt/$MAX_RETRIES, Retry-After: $retryAfter)")
                    delay(backoffMs)
                    return@use
                }

                if (resp.code != 206 && !resp.isSuccessful) {
                    throw RuntimeException("Chunk $index failed: HTTP ${resp.code}")
                }

                attempt = 0

                val body = resp.body ?: throw RuntimeException("Chunk $index: empty body")

                RandomAccessFile(chunk.file, "rw").use { raf ->
                    raf.seek(existingBytes)
                    val buffer = ByteArray(BUFFER_SIZE)
                    val source = body.byteStream()

                    while (!cancelled.get()) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        raf.write(buffer, 0, read)
                        totalDownloaded.addAndGet(read.toLong())
                        existingBytes += read
                    }
                }
            }
        }

        Log.d(TAG, "Chunk $index: done (${chunk.file.length() / 1_000_000} MB)")
    }

    private fun mergeChunks(chunks: List<Chunk>, targetFile: File) {
        Log.d(TAG, "Merging ${chunks.size} chunks...")
        val mergeStart = System.currentTimeMillis()

        RandomAccessFile(targetFile, "rw").use { raf ->
            val buffer = ByteArray(BUFFER_SIZE)
            for ((i, chunk) in chunks.withIndex()) {
                chunk.file.inputStream().buffered(BUFFER_SIZE).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        raf.write(buffer, 0, read)
                    }
                }
                Log.d(TAG, "Merged chunk $i")
            }
        }

        val elapsed = (System.currentTimeMillis() - mergeStart) / 1_000.0
        Log.d(TAG, "Merge complete in ${"%.1f".format(elapsed)}s")
    }

    private suspend fun downloadSingleStream(
        url: String,
        tempFile: File,
        targetFile: File,
        totalBytes: Long,
    ) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw RuntimeException("Empty response body")

            tempFile.outputStream().buffered(BUFFER_SIZE).use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                val source = body.byteStream()
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                }
            }
            tempFile.renameTo(targetFile)
        }
    }

    fun cancelDownload() {
        cancelled.set(true)
    }

    fun getDownloadDir(): File {
        val dir = File(context.getExternalFilesDir(null), "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getDownloadedFile(filename: String): File? {
        val file = File(getDownloadDir(), filename)
        return if (file.exists()) file else null
    }
}
