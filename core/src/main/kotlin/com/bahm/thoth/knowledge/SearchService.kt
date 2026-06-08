package com.bahm.thoth.knowledge

import com.bahm.thoth.core.Log
import com.bahm.thoth.knowledge.models.SearchResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class SearchService @Inject constructor(
    private val zim: ZimSource,
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
        val articles = zim.searchArticles(query, maxResults = MAX_ARTICLES)
        val afterZim = System.currentTimeMillis()
        val zimTime = afterZim - startTime

        // 2. Chunk each article into passages
        val allPassages = articles.flatMap { article -> chunker.chunk(article) }
        val afterChunk = System.currentTimeMillis()
        val chunkTime = afterChunk - afterZim

        // 3. BM25-rank all passages against query
        val ranked = bm25Scorer.score(query, allPassages)
        val bm25Time = System.currentTimeMillis() - afterChunk

        // 4. Return top-K passages
        val topResults = ranked.take(topK)
        val elapsed = System.currentTimeMillis() - startTime

        Log.d(TAG, "search(\"$query\"): ${articles.size} articles (${zimTime}ms) -> ${allPassages.size} passages (chunk ${chunkTime}ms) -> bm25 ${bm25Time}ms -> top-$topK ranked (${elapsed}ms total)")

        SearchResult(
            passages = topResults.map { it.first },
            query = query,
            searchTimeMs = elapsed,
            zimMs = zimTime,
            chunkMs = chunkTime,
            bm25Ms = bm25Time,
            articleCount = articles.size,
            candidatePassageCount = allPassages.size,
        )
    }
}
