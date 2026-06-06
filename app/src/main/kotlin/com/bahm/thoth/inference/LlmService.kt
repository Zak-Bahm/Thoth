package com.bahm.thoth.inference

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed class LlmState {
    data object Uninitialized : LlmState()
    data object Initializing : LlmState()
    data object Ready : LlmState()
    data class Error(val message: String) : LlmState()
}

@Singleton
class LlmService @Inject constructor(
    private val thothTools: ThothTools,
    private val toolHandler: ToolHandler,
) {

    companion object {
        private const val TAG = "LlmService"
        private val THINKING_PATTERN = Regex("""<\|channel>thought\s*\n?(.*?)<channel\|>""", RegexOption.DOT_MATCHES_ALL)
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    private val _state = MutableStateFlow<LlmState>(LlmState.Uninitialized)
    val state: StateFlow<LlmState> = _state.asStateFlow()

    fun isInitialized(): Boolean = _state.value is LlmState.Ready

    suspend fun initialize(modelPath: String) = withContext(Dispatchers.Default) {
        _state.value = LlmState.Initializing
        try {
            Log.d(TAG, "Loading model from: $modelPath")
            val startTime = System.currentTimeMillis()

            val config = EngineConfig(modelPath)
            val eng = Engine(config)
            eng.initialize()

            engine = eng
            val elapsed = (System.currentTimeMillis() - startTime) / 1_000.0
            Log.d(TAG, "Model loaded in ${"%.1f".format(elapsed)}s")
            _state.value = LlmState.Ready
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}", e)
            _state.value = LlmState.Error(e.message ?: "Failed to load model")
        }
    }

    fun createConversation() {
        val eng = engine ?: throw IllegalStateException("Engine not initialized")
        conversation?.close()

        val samplerConfig = SamplerConfig(
            /* topK = */ 40,
            /* topP = */ 0.95,
            /* temperature = */ 0.7,
            /* seed = */ 0,
        )
        val conversationConfig = ConversationConfig(
            samplerConfig = samplerConfig,
            systemInstruction = Contents.of(SystemPrompt.THOTH_SYSTEM_PROMPT),
            tools = listOf(tool(thothTools)),
            automaticToolCalling = true,
        )
        conversation = eng.createConversation(conversationConfig)
        Log.d(TAG, "Conversation created with system prompt and tools registered (automaticToolCalling=true)")
    }

    fun sendMessage(text: String): Flow<String> {
        val conv = conversation ?: throw IllegalStateException("Conversation not created")
        Log.d(TAG, "sendMessage | length=${text.length}")
        toolHandler.resetForNewMessage()

        return flow {
            // Collect the full response (tools execute automatically during this)
            val rawText = collectResponse(conv, text)

            // Log any thinking content for debugging
            val thinkingMatch = THINKING_PATTERN.find(rawText)
            if (thinkingMatch != null) {
                Log.d(TAG, "Model reasoning: ${thinkingMatch.groupValues[1].trim().take(500)}")
            }

            // Check if submitAnswer was called (structured response available)
            val structured = toolHandler.getStructuredResponse()
            if (structured != null) {
                val html = toolHandler.renderToHtml(structured)
                Log.d(TAG, "Structured response | ${structured.groundedCount}/${structured.claims.size} grounded | html length=${html.length}")
                emit(html)
            } else {
                // Model responded with plain text (didn't call submitAnswer) — strip thinking tokens
                val cleanText = stripThinking(rawText)
                Log.w(TAG, "No structured response — model did not call submitAnswer. Emitting raw text.")
                emit(cleanText)
            }
        }
    }

    private suspend fun collectResponse(conv: Conversation, text: String): String {
        val accumulated = StringBuilder()
        conv.sendMessageAsync(text, emptyMap())
            .map { message ->
                message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
            }
            .collect { token ->
                accumulated.append(token)
            }
        val result = accumulated.toString()
        Log.d(TAG, "Response collected | length=${result.length} | toolCalls=${thothTools.callCount}")
        return result
    }

    private fun stripThinking(text: String): String {
        return THINKING_PATTERN.replace(text, "").trim()
    }

    fun getToolCallCount(): Int = thothTools.callCount

    fun resetConversation() {
        conversation?.close()
        conversation = null
        createConversation()
    }

    fun release() {
        Log.d(TAG, "Releasing resources")
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        _state.value = LlmState.Uninitialized
    }
}
