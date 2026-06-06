package com.bahm.thoth.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bahm.thoth.inference.LlmService
import com.bahm.thoth.inference.LlmState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val llmService: LlmService,
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    val llmState: StateFlow<LlmState> = llmService.state

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return
        if (llmService.state.value !is LlmState.Ready) {
            Log.w(TAG, "sendMessage ignored — model not ready")
            return
        }

        _isGenerating.value = true
        val userMessage = ChatMessage(role = Role.USER, content = trimmed)
        val assistantMessage = ChatMessage(role = Role.ASSISTANT, content = "", isGenerating = true)
        _messages.value = _messages.value + userMessage + assistantMessage
        Log.d(TAG, "sendMessage | \"${trimmed.take(60)}\"")

        viewModelScope.launch {
            var responseHtml = ""
            try {
                llmService.sendMessage(trimmed)
                    .catch { e ->
                        Log.e(TAG, "Generation error: ${e.message}", e)
                        responseHtml = "<p>Error: ${e.message}</p>"
                    }
                    .collect { chunk -> responseHtml = chunk }
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage failed: ${e.message}", e)
                responseHtml = "<p>Error: ${e.message}</p>"
            } finally {
                val sources = llmService.getLastSources().map {
                    Source(articleTitle = it.articleTitle, zimEntryPath = it.zimEntryPath)
                }
                Log.d(TAG, "response complete | htmlLength=${responseHtml.length} | sources=${sources.size}")
                finishAssistantMessage(assistantMessage.id, responseHtml, sources)
                _isGenerating.value = false
            }
        }
    }

    private fun finishAssistantMessage(id: String, content: String, sources: List<Source>) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == id) {
                msg.copy(content = content, sources = sources, isGenerating = false)
            } else {
                msg
            }
        }
    }

    fun resetChat() {
        _messages.value = emptyList()
        llmService.resetConversation()
    }
}
