package com.bahm.thoth.knowledge.models

data class Passage(
    val id: String,
    val text: String,
    val articleTitle: String,
    val sectionHeading: String?,
    val zimEntryPath: String,
    val chunkIndex: Int,
)
