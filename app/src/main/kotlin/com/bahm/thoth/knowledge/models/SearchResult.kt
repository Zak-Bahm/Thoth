package com.bahm.thoth.knowledge.models

data class SearchResult(
    val passages: List<Passage>,
    val query: String,
    val searchTimeMs: Long,
    // Per-stage breakdown for performance instrumentation
    val zimMs: Long = 0,
    val chunkMs: Long = 0,
    val bm25Ms: Long = 0,
    val articleCount: Int = 0,
    val candidatePassageCount: Int = 0,
)
