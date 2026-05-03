package com.bahm.thoth.knowledge.models

data class SearchResult(
    val passages: List<Passage>,
    val query: String,
    val searchTimeMs: Long,
)
