package com.bahm.thoth.knowledge

import android.util.Log
import com.bahm.thoth.knowledge.models.Article
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher
import org.kiwix.libzim.SuggestionSearcher
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ZimRepository @Inject constructor() {

    companion object {
        private const val TAG = "ZimRepository"

        init {
            System.loadLibrary("c++_shared")
            System.loadLibrary("zim")
            System.loadLibrary("kiwix")
            System.loadLibrary("zim_wrapper")
            System.loadLibrary("kiwix_wrapper")
            Log.d(TAG, "Native libraries loaded")
        }
    }

    private var archive: Archive? = null
    private val mutex = Mutex()

    suspend fun open(zimFilePath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            Log.d(TAG, "Opening ZIM: $zimFilePath")
            val file = File(zimFilePath)
            Log.d(TAG, "File exists: ${file.exists()}, size: ${file.length() / 1_000_000} MB, readable: ${file.canRead()}")
            archive?.dispose()
            try {
                archive = Archive(zimFilePath)
                Log.d(TAG, "Archive opened successfully")
                Log.d(TAG, "Article count: ${archive?.getArticleCount()}")
                Log.d(TAG, "Entry count: ${archive?.getAllEntryCount()}")
                Log.d(TAG, "Has fulltext index: ${archive?.hasFulltextIndex()}")
                Log.d(TAG, "Filename: ${archive?.getFilename()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open archive: ${e.message}", e)
                archive = null
                throw e
            }
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            archive?.dispose()
            archive = null
        }
    }

    fun isOpen(): Boolean = archive != null

    fun getArticleCount(): Int = archive?.getArticleCount() ?: 0

    fun getAllEntryCount(): Int = archive?.getAllEntryCount() ?: 0

    fun hasFulltextIndex(): Boolean = archive?.hasFulltextIndex() ?: false

    fun getFilename(): String? = archive?.getFilename()

    suspend fun getArticleByTitle(title: String): Article? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val arch = archive ?: return@withContext null
            if (!arch.hasEntryByTitle(title)) return@withContext null
            try {
                val entry = arch.getEntryByTitle(title)
                val item = entry.getItem(true)
                val blob = item.getData()
                try {
                    Article(
                        title = entry.getTitle(),
                        path = entry.getPath(),
                        htmlContent = String(blob.getData()),
                    )
                } finally {
                    blob.dispose()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun getArticleByPath(path: String): Article? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val arch = archive ?: return@withContext null
            if (!arch.hasEntryByPath(path)) return@withContext null
            try {
                val entry = arch.getEntryByPath(path)
                val item = entry.getItem(true)
                val blob = item.getData()
                try {
                    Article(
                        title = entry.getTitle(),
                        path = entry.getPath(),
                        htmlContent = String(blob.getData()),
                    )
                } finally {
                    blob.dispose()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun getArticleContent(path: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val arch = archive ?: return@withContext ""
            try {
                val entry = arch.getEntryByPath(path)
                val item = entry.getItem(true)
                val blob = item.getData()
                try {
                    String(blob.getData())
                } finally {
                    blob.dispose()
                }
            } catch (_: Exception) {
                ""
            }
        }
    }

    suspend fun searchArticles(query: String, maxResults: Int = 20): List<Article> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val arch = archive ?: return@withContext emptyList()

                if (arch.hasFulltextIndex()) {
                    searchFulltext(arch, query, maxResults)
                } else {
                    searchSuggestions(arch, query, maxResults)
                }
            }
        }

    private fun searchFulltext(arch: Archive, query: String, maxResults: Int): List<Article> {
        val searcher = Searcher(arch)
        val q = Query(query)
        try {
            val search = searcher.search(q)
            try {
                val iterator = search.getResults(0, maxResults)
                try {
                    val results = mutableListOf<Article>()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        try {
                            val item = entry.getItem(true)
                            val blob = item.getData()
                            try {
                                results.add(
                                    Article(
                                        title = entry.getTitle(),
                                        path = entry.getPath(),
                                        htmlContent = String(blob.getData()),
                                    )
                                )
                            } finally {
                                blob.dispose()
                            }
                        } catch (_: Exception) {
                            // Skip entries that fail to load
                        }
                    }
                    return results
                } finally {
                    iterator.dispose()
                }
            } finally {
                search.dispose()
            }
        } finally {
            q.dispose()
            searcher.dispose()
        }
    }

    private fun searchSuggestions(arch: Archive, query: String, maxResults: Int): List<Article> {
        val searcher = SuggestionSearcher(arch)
        try {
            val search = searcher.suggest(query)
            try {
                val iterator = search.getResults(0, maxResults)
                try {
                    val results = mutableListOf<Article>()
                    while (iterator.hasNext()) {
                        val suggestionItem = iterator.next()
                        try {
                            val path = suggestionItem.getPath()
                            val entry = arch.getEntryByPath(path)
                            val item = entry.getItem(true)
                            val blob = item.getData()
                            try {
                                results.add(
                                    Article(
                                        title = suggestionItem.getTitle(),
                                        path = path,
                                        htmlContent = String(blob.getData()),
                                    )
                                )
                            } finally {
                                blob.dispose()
                            }
                        } catch (_: Exception) {
                            // Skip entries that fail to load
                        }
                    }
                    return results
                } finally {
                    iterator.dispose()
                }
            } finally {
                search.dispose()
            }
        } finally {
            searcher.dispose()
        }
    }
}
