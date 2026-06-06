package com.bahm.thoth.ui.chat

import java.util.UUID

enum class Role { USER, ASSISTANT }

/** A citation surfaced below an assistant message, linking back to a ZIM article. */
data class Source(
    val articleTitle: String,
    val zimEntryPath: String,
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val sources: List<Source> = emptyList(),
    val isGenerating: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)
