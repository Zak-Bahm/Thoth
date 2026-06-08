package com.bahm.thoth.desktop

import com.bahm.thoth.core.Log
import com.bahm.thoth.knowledge.ZimSource
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

/**
 * Desktop [ZimSource] over the same `org.kiwix.libzim.*` bindings the Android app uses, backed by
 * the locally-built glibc natives ([NativeLoader]). Mirrors the Android ZimRepository logic so the
 * shared SearchService/ThothTools behave identically; only native loading differs.
 */
class DesktopZimRepository : ZimSource {

    companion object {
        private const val TAG = "DesktopZimRepository"
    }

    init {
        NativeLoader.ensureZimLoaded()
    }

    private var archive: Archive? = null
    private val mutex = Mutex()

    suspend fun open(zimFilePath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = File(zimFilePath)
            Log.d(TAG, "Opening ZIM: $zimFilePath (exists=${file.exists()}, ${file.length() / 1_000_000} MB)")
            archive?.dispose()
            archive = Archive(zimFilePath)
            Log.d(TAG, "Archive opened: articles=${archive?.getArticleCount()}, fulltext=${archive?.hasFulltextIndex()}")
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            archive?.dispose()
            archive = null
        }
    }

    fun isOpen(): Boolean = archive != null

    override suspend fun getArticleByTitle(title: String): Article? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val arch = archive ?: return@withContext null
            if (!arch.hasEntryByTitle(title)) return@withContext null
            readArticle { arch.getEntryByTitle(title) }
        }
    }

    override suspend fun getArticleByPath(path: String): Article? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val arch = archive ?: return@withContext null
            if (!arch.hasEntryByPath(path)) return@withContext null
            readArticle { arch.getEntryByPath(path) }
        }
    }

    override suspend fun searchArticles(query: String, maxResults: Int): List<Article> =
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

    private inline fun readArticle(getEntry: () -> org.kiwix.libzim.Entry): Article? = try {
        val entry = getEntry()
        val item = entry.getItem(true)
        val blob = item.getData()
        try {
            Article(entry.getTitle(), entry.getPath(), String(blob.getData()))
        } finally {
            blob.dispose()
        }
    } catch (_: Exception) {
        null
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
                        readArticle { entry }?.let { results.add(it) }
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
                        val path = suggestionItem.getPath()
                        readArticle { arch.getEntryByPath(path) }?.let { results.add(it) }
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
