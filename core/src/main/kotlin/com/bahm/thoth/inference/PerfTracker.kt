package com.bahm.thoth.inference

import com.bahm.thoth.core.Log
import com.bahm.thoth.core.OutputSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ToolSpan(
    val name: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val detail: Map<String, Any?> = emptyMap(),
) {
    val wallMs: Long get() = endOffsetMs - startOffsetMs
}

data class PerfReport(
    val timestamp: Long,
    val query: String,
    val totalMs: Long,
    val llmComputeMs: Long,
    val toolTotalMs: Long,
    val ttftMs: Long,
    val toolCallCount: Int,
    val searchCallCount: Int,
    val tools: List<ToolSpan>,
    val llmSegments: List<Long>,
    val answerChars: Int,
    val emittedChunks: Int,
    val groundedClaims: Int,
    val totalClaims: Int,
)

/**
 * Always-on, per-message performance tracker. Messages are sequential (guarded by the
 * UI's isGenerating), but tool spans are recorded from LiteRT-LM's inference thread while
 * start/finish run on the response flow's coroutine — hence the synchronization.
 *
 * Writes one JSON object per message to {externalFilesDir}/perf/chat_perf.jsonl and emits
 * a one-line PERF logcat summary.
 */
@Singleton
class PerfTracker @Inject constructor(
    private val outputSink: OutputSink,
) {
    companion object {
        private const val TAG = "PERF"
    }

    private var query: String = ""
    private var startMs: Long = 0
    private var ttftMs: Long = -1
    private var answerChars: Int = 0
    private var emittedChunks: Int = 0
    private val spans = mutableListOf<ToolSpan>()

    /** The most recently finished message's report — read by EvalRunner to fill EvalRecord timings. */
    @Volatile
    var lastReport: PerfReport? = null
        private set

    @Synchronized
    fun startMessage(query: String) {
        this.query = query
        startMs = System.currentTimeMillis()
        ttftMs = -1
        answerChars = 0
        emittedChunks = 0
        spans.clear()
    }

    /** Wall-clock ms since the current message started — used to compute tool offsets. */
    @Synchronized
    fun elapsedMs(): Long = System.currentTimeMillis() - startMs

    @Synchronized
    fun recordFirstToken() {
        if (ttftMs < 0) ttftMs = elapsedMs()
    }

    @Synchronized
    fun addChunk(chars: Int) {
        answerChars += chars
        emittedChunks++
    }

    @Synchronized
    fun recordTool(name: String, startOffsetMs: Long, endOffsetMs: Long, detail: Map<String, Any?> = emptyMap()) {
        spans.add(ToolSpan(name, startOffsetMs, endOffsetMs, detail))
    }

    @Synchronized
    fun finishMessage(groundedClaims: Int, totalClaims: Int): PerfReport? {
        if (startMs == 0L) return null
        val totalMs = elapsedMs()
        val ordered = spans.sortedBy { it.startOffsetMs }
        val toolTotalMs = ordered.sumOf { it.wallMs }
        val llmComputeMs = (totalMs - toolTotalMs).coerceAtLeast(0)

        // LLM segments are the gaps around the (non-overlapping, sequential) tool spans.
        val segments = mutableListOf<Long>()
        var cursor = 0L
        for (span in ordered) {
            segments.add((span.startOffsetMs - cursor).coerceAtLeast(0))
            cursor = span.endOffsetMs
        }
        segments.add((totalMs - cursor).coerceAtLeast(0))

        val report = PerfReport(
            timestamp = System.currentTimeMillis(),
            query = query,
            totalMs = totalMs,
            llmComputeMs = llmComputeMs,
            toolTotalMs = toolTotalMs,
            ttftMs = if (ttftMs < 0) 0 else ttftMs,
            toolCallCount = ordered.size,
            searchCallCount = ordered.count { it.name == "searchKnowledge" },
            tools = ordered,
            llmSegments = segments,
            answerChars = answerChars,
            emittedChunks = emittedChunks,
            groundedClaims = groundedClaims,
            totalClaims = totalClaims,
        )

        lastReport = report
        logSummary(report)
        writeJsonl(report)
        startMs = 0
        return report
    }

    /**
     * Diagnostic dump of the model's full raw output for the current message — the visible
     * text, the reasoning `channels`, and stream tool-call count — to perf/raw_output.jsonl.
     * Lets us see why a message produced no/empty answer (truncation vs refusal vs malformed).
     */
    @Synchronized
    fun recordRawOutput(
        rawText: String,
        channels: Map<String, String>,
        structuredCalled: Boolean,
        streamToolCalls: Int,
    ) {
        try {
            val chJson = JSONObject()
            for ((k, v) in channels) chJson.put(k, v)
            val obj = JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("query", query)
                .put("structuredCalled", structuredCalled)
                .put("streamToolCalls", streamToolCalls)
                .put("rawTextLength", rawText.length)
                .put("rawText", rawText)
                .put("channels", chJson)
            File(logDir(), "raw_output.jsonl").appendText(obj.toString() + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write raw output: ${e.message}", e)
        }
    }

    fun logDir(): File = outputSink.dir("perf")

    private fun logSummary(r: PerfReport) {
        val pct = if (r.totalMs > 0) (r.llmComputeMs * 100 / r.totalMs) else 0
        val searchDetail = r.tools.filter { it.name == "searchKnowledge" }.joinToString(",") {
            "[zim${it.detail["zimMs"]},chunk${it.detail["chunkMs"]},bm25${it.detail["bm25Ms"]}]"
        }
        Log.i(
            TAG,
            "total=${r.totalMs}ms llm=${r.llmComputeMs}ms($pct%) tools=${r.toolTotalMs}ms " +
                "search×${r.searchCallCount}$searchDetail ttft=${r.ttftMs}ms chars=${r.answerChars} " +
                "chunks=${r.emittedChunks} grounded=${r.groundedClaims}/${r.totalClaims} | \"${r.query.take(60)}\"",
        )
    }

    private fun writeJsonl(r: PerfReport) {
        try {
            val toolsJson = JSONArray()
            for (s in r.tools) {
                val detailJson = JSONObject()
                for ((k, v) in s.detail) detailJson.put(k, v)
                toolsJson.put(
                    JSONObject()
                        .put("name", s.name)
                        .put("startOffsetMs", s.startOffsetMs)
                        .put("endOffsetMs", s.endOffsetMs)
                        .put("wallMs", s.wallMs)
                        .put("detail", detailJson),
                )
            }
            val obj = JSONObject()
                .put("timestamp", r.timestamp)
                .put("query", r.query)
                .put("totalMs", r.totalMs)
                .put("llmComputeMs", r.llmComputeMs)
                .put("toolTotalMs", r.toolTotalMs)
                .put("ttftMs", r.ttftMs)
                .put("toolCallCount", r.toolCallCount)
                .put("searchCallCount", r.searchCallCount)
                .put("tools", toolsJson)
                .put("llmSegments", JSONArray(r.llmSegments))
                .put("answerChars", r.answerChars)
                .put("emittedChunks", r.emittedChunks)
                .put("groundedClaims", r.groundedClaims)
                .put("totalClaims", r.totalClaims)

            File(logDir(), "chat_perf.jsonl").appendText(obj.toString() + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write perf log: ${e.message}", e)
        }
    }
}
