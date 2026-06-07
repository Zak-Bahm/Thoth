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
    private val perfTracker: PerfTracker,
) {

    companion object {
        private const val TAG = "LlmService"
        private val THINKING_PATTERN = Regex("""<\|channel>thought\s*\n?(.*?)<channel\|>""", RegexOption.DOT_MATCHES_ALL)

        // Total token budget for the engine. Set explicitly (vs. the library default) so a
        // single turn — system prompt + search results + a lookupArticle + reasoning + answer
        // — fits without truncating before the model calls submitAnswer. 8192 leaves room for
        // a trimmed lookupArticle (see ThothTools.LOOKUP_MAX_CHARS) on top of search context.
        private const val MAX_NUM_TOKENS = 8192

        // Below this, a no-submitAnswer plain-text response is treated as a degenerate
        // miss and replaced with a friendly message instead of a truncated fragment.
        private const val MIN_PLAIN_TEXT_CHARS = 40
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

            val config = EngineConfig(modelPath = modelPath, maxNumTokens = MAX_NUM_TOKENS)
            Log.d(TAG, "EngineConfig maxNumTokens=${config.maxNumTokens}")
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
        perfTracker.startMessage(text)

        return flow {
            // Collect the full response (tools execute automatically during this)
            val collected = collectResponse(conv, text)
            val rawText = collected.text

            // Check if submitAnswer was called (structured response available)
            val structured = toolHandler.getStructuredResponse()

            // Diagnostic dump: full raw text + reasoning channels + tool-call count to
            // debug/raw_output.jsonl, so failed/empty answers can be inspected off-device.
            perfTracker.recordRawOutput(
                rawText = rawText,
                channels = collected.channels,
                structuredCalled = structured != null,
                streamToolCalls = collected.toolCallCount,
            )

            if (structured != null) {
                val html = toolHandler.renderToHtml(structured)
                Log.d(TAG, "Structured response | ${structured.groundedCount}/${structured.claims.size} grounded | html length=${html.length}")
                perfTracker.finishMessage(structured.groundedCount, structured.claims.size)
                emit(html)
            } else {
                // Model produced no submitAnswer call. Keep a substantive plain-text reply
                // (e.g. a clarification), but replace a degenerate fragment with a clean message.
                val cleanText = stripThinking(rawText)
                Log.w(TAG, "No structured response — model did not call submitAnswer. textLen=${rawText.length}, channels=${collected.channels.keys}")
                perfTracker.finishMessage(0, 0)
                val out = if (cleanText.length >= MIN_PLAIN_TEXT_CHARS) {
                    cleanText
                } else {
                    "<p>I couldn't find a clear answer to that. Try rephrasing with more specific terms.</p>"
                }
                emit(out)
            }
        }
    }

    private data class CollectResult(
        val text: String,
        val channels: Map<String, String>,
        val toolCallCount: Int,
    )

    private suspend fun collectResponse(conv: Conversation, text: String): CollectResult {
        val accumulated = StringBuilder()
        val channels = linkedMapOf<String, StringBuilder>()
        var streamToolCalls = 0
        conv.sendMessageAsync(text, emptyMap())
            .collect { message ->
                val tokenText = message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                if (tokenText.isNotEmpty()) {
                    perfTracker.recordFirstToken()
                    perfTracker.addChunk(tokenText.length)
                    accumulated.append(tokenText)
                }
                // Reasoning streams via channels (not Content.Text); accumulate per channel.
                for ((key, value) in message.channels) {
                    if (value.isNotEmpty()) channels.getOrPut(key) { StringBuilder() }.append(value)
                }
                streamToolCalls += message.toolCalls.size
            }
        val result = accumulated.toString()
        val channelsText = channels.mapValues { it.value.toString() }
        Log.d(
            TAG,
            "Response collected | textLen=${result.length} | channels=${channelsText.mapValues { it.value.length }} | " +
                "streamToolCalls=$streamToolCalls | toolCallCount=${thothTools.callCount}",
        )
        return CollectResult(result, channelsText, streamToolCalls)
    }

    private fun stripThinking(text: String): String {
        return THINKING_PATTERN.replace(text, "").trim()
    }

    fun getToolCallCount(): Int = thothTools.callCount

    /**
     * Grounded sources from the most recent [sendMessage] response, deduplicated by
     * ZIM entry path. Empty when the model produced plain text (no submitAnswer call).
     */
    fun getLastSources(): List<PassageSource> {
        val structured = toolHandler.getStructuredResponse() ?: return emptyList()
        return structured.claims
            .mapNotNull { if (it.isGrounded) it.source else null }
            .distinctBy { it.zimEntryPath }
    }

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
