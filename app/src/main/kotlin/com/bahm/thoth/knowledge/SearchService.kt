package com.bahm.thoth.knowledge

import android.util.Log
import com.bahm.thoth.knowledge.models.SearchResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class SearchService @Inject constructor(
    private val zimRepository: ZimRepository,
    private val chunker: ArticleChunker,
    private val bm25Scorer: Bm25Scorer,
) {
    companion object {
        private const val TAG = "SearchService"
        private const val MAX_ARTICLES = 20
    }

    suspend fun search(query: String, topK: Int = 5): SearchResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // 1. Search ZIM for top matching articles
        val articles = zimRepository.searchArticles(query, maxResults = MAX_ARTICLES)
        val zimTime = System.currentTimeMillis() - startTime

        // 2. Chunk each article into passages
        val allPassages = articles.flatMap { article -> chunker.chunk(article) }
        val chunkTime = System.currentTimeMillis() - startTime

        // 3. BM25-rank all passages against query
        val ranked = bm25Scorer.score(query, allPassages)

        // 4. Return top-K passages
        val topResults = ranked.take(topK)
        val elapsed = System.currentTimeMillis() - startTime

        Log.d(TAG, "search(\"$query\"): ${articles.size} articles (${zimTime}ms) -> ${allPassages.size} passages (${chunkTime}ms) -> top-$topK ranked (${elapsed}ms total)")

        SearchResult(
            passages = topResults.map { it.first },
            query = query,
            searchTimeMs = elapsed,
        )
    }
}
