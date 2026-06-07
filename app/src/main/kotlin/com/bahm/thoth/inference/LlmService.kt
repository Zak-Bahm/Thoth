package com.bahm.thoth.inference

import android.util.Log
import com.bahm.thoth.knowledge.SearchService
import com.bahm.thoth.knowledge.models.Passage
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
    private val searchService: SearchService,
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

        // Quick Answer mode: how many top passages to inject, and the per-passage char cap.
        // Kept small so the single prefill stays cheap (~1800 chars / ~450 tokens at top-3).
        // top-3 (vs 2) measurably improves recall — the answer often ranks just below the top
        // couple of BM25 hits — while generation (one sentence) stays the same length.
        private const val QUICK_TOP_K = 3
        private const val QUICK_PASSAGE_CHARS = 600

        private const val QUICK_MISS_HTML =
            "<p>I couldn't find a quick answer to that. Try a detailed search.</p>"
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    // Separate, tool-free conversation for Quick Answer mode (recreated per message so its
    // prefill stays minimal and prior turns don't accumulate).
    private var quickConversation: Conversation? = null

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

    /**
     * Fresh, tool-free conversation for one Quick Answer turn. No tools and
     * automaticToolCalling=false mean no agentic loop runs; lower temperature keeps the single
     * sentence terse and deterministic. Recreated per message so prefill stays minimal.
     */
    private fun createQuickConversation(): Conversation {
        quickConversation?.close()
        return createScratchConversation(SystemPrompt.QUICK_SYSTEM_PROMPT).also {
            quickConversation = it
            Log.d(TAG, "Quick conversation created (no tools, automaticToolCalling=false)")
        }
    }

    /** A throwaway tool-free, no-reasoning conversation for a single short generation. */
    private fun createScratchConversation(systemPrompt: String): Conversation {
        val eng = engine ?: throw IllegalStateException("Engine not initialized")
        val samplerConfig = SamplerConfig(
            /* topK = */ 40,
            /* topP = */ 0.95,
            /* temperature = */ 0.3,
            /* seed = */ 0,
        )
        val conversationConfig = ConversationConfig(
            samplerConfig = samplerConfig,
            systemInstruction = Contents.of(systemPrompt),
            tools = emptyList(),
            automaticToolCalling = false,
        )
        return eng.createConversation(conversationConfig)
    }

    fun sendMessage(text: String, mode: AnswerMode = AnswerMode.QUICK): Flow<String> = when (mode) {
        AnswerMode.QUICK -> sendQuickMessage(text)
        AnswerMode.THOROUGH -> sendThoroughMessage(text)
    }

    private fun sendThoroughMessage(text: String): Flow<String> {
        val conv = conversation ?: throw IllegalStateException("Conversation not created")
        Log.d(TAG, "sendThoroughMessage | length=${text.length}")
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

    /**
     * Quick Answer mode: retrieve in code, inject the top passages into one prompt, and run a
     * single tool-free, no-reasoning generation. Collapses the agentic two-pass loop into one
     * short generation. Grounding is done post-hoc against the top passage we fed the model.
     */
    private fun sendQuickMessage(text: String): Flow<String> {
        Log.d(TAG, "sendQuickMessage | length=${text.length}")
        toolHandler.resetForNewMessage()
        perfTracker.startMessage(text)

        return flow {
            val conv = createQuickConversation()

            // 1. Turn the question into Wikipedia keywords first — searching the raw
            // natural-language question retrieves poorly (matches titles like songs, not the
            // subject). This mirrors what the agentic thorough path does before searching.
            val kwStart = perfTracker.elapsedMs()
            val keywords = extractKeywords(text)
            perfTracker.recordTool(
                name = "quickKeywords",
                startOffsetMs = kwStart,
                endOffsetMs = perfTracker.elapsedMs(),
                detail = mapOf("query" to text, "keywords" to keywords),
            )

            // 2. Retrieve directly (fast, deterministic) — no searchKnowledge tool round-trip.
            val retrieveStart = perfTracker.elapsedMs()
            val result = searchService.search(keywords, topK = QUICK_TOP_K)
            val passages = result.passages.take(QUICK_TOP_K)
            perfTracker.recordTool(
                name = "quickRetrieve",
                startOffsetMs = retrieveStart,
                endOffsetMs = perfTracker.elapsedMs(),
                detail = mapOf(
                    "query" to keywords,
                    "zimMs" to result.zimMs,
                    "chunkMs" to result.chunkMs,
                    "bm25Ms" to result.bm25Ms,
                    "articleCount" to result.articleCount,
                    "candidatePassageCount" to result.candidatePassageCount,
                    "returnedPassages" to passages.size,
                ),
            )

            if (passages.isEmpty()) {
                Log.w(TAG, "Quick: no passages retrieved for \"$text\"")
                perfTracker.recordRawOutput("", emptyMap(), structuredCalled = false, streamToolCalls = 0)
                perfTracker.finishMessage(0, 0)
                emit(QUICK_MISS_HTML)
                return@flow
            }

            // 3. One generation pass over the injected context.
            val prompt = buildQuickPrompt(text, passages)
            val collected = collectResponse(conv, prompt)
            val answer = stripThinking(collected.text).trim()
            perfTracker.recordRawOutput(
                rawText = collected.text,
                channels = collected.channels,
                structuredCalled = false,
                streamToolCalls = collected.toolCallCount,
            )

            // 4. Ground post-hoc: the top passage IS the citation.
            val normalized = answer.trimEnd('.', '!', '?', ' ').lowercase()
            val isMiss = answer.isBlank() ||
                normalized.startsWith("i don't know") ||
                normalized.startsWith("i dont know")
            if (isMiss) {
                Log.d(TAG, "Quick: miss (answerLen=${answer.length})")
                perfTracker.finishMessage(0, 0)
                emit(QUICK_MISS_HTML)
            } else {
                // Attribute the claim to the passage the answer actually drew from (most term
                // overlap), not blindly to the top-ranked one — the answer often comes from a
                // lower-ranked passage.
                val src = pickSourcePassage(answer, passages)
                val topSource = PassageSource(
                    articleTitle = src.articleTitle,
                    sectionHeading = src.sectionHeading ?: "",
                    zimEntryPath = src.zimEntryPath,
                )
                val structured = StructuredResponse(
                    claims = listOf(ValidatedClaim(text = answer, source = topSource, isGrounded = true)),
                    groundedCount = 1,
                    ungroundedCount = 0,
                )
                toolHandler.setQuickResponse(structured)
                val html = toolHandler.renderToHtml(structured)
                Log.d(TAG, "Quick: answer grounded to \"${topSource.articleTitle}\" | chars=${answer.length}")
                perfTracker.finishMessage(1, 1)
                emit(html)
            }
        }
    }

    /**
     * Pick which injected passage a Quick answer drew from, by content-word overlap with the
     * answer. Falls back to the top-ranked passage when there's no clear signal.
     */
    private fun pickSourcePassage(answer: String, passages: List<Passage>): Passage {
        val answerTerms = quickTokens(answer)
        if (answerTerms.isEmpty()) return passages[0]
        return passages.maxByOrNull { p ->
            val passageTerms = quickTokens(p.text).toSet()
            answerTerms.count { it in passageTerms }
        } ?: passages[0]
    }

    // Lowercase content words of length >= 4 (cheaply skips most stopwords) for overlap scoring.
    private fun quickTokens(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }

    /**
     * Run a quick keyword-extraction generation to convert the question into Wikipedia search
     * terms. Falls back to the raw question if the model returns nothing usable.
     */
    private suspend fun extractKeywords(question: String): String {
        val conv = createScratchConversation(SystemPrompt.QUICK_KEYWORDS_SYSTEM_PROMPT)
        try {
            val raw = generateFirstLine(conv, question)
            var kw = stripThinking(raw)
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            // Defend against the model echoing the few-shot "question -> keywords" format.
            if (kw.contains("->")) kw = kw.substringAfterLast("->").trim()
            kw = kw.trim('"', '\'', '.', ' ').take(120)
            Log.d(TAG, "Quick keywords: \"$question\" -> \"$kw\" (rawLen=${raw.length})")
            return kw.ifBlank { question }
        } finally {
            conv.close()
        }
    }

    /**
     * Collect text only until the first line is complete, then stop — cancelling the flow stops
     * generation. Used for the keyword pass, where the model otherwise rambles past the one line
     * we keep, wasting decode time.
     */
    private suspend fun generateFirstLine(conv: Conversation, text: String): String {
        val sb = StringBuilder()
        try {
            conv.sendMessageAsync(text, emptyMap()).collect { message ->
                message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .forEach { sb.append(it.text) }
                if (sb.indexOf("\n") >= 0) throw FirstLineComplete
            }
        } catch (_: FirstLineComplete) {
            // Expected: we have the first line, stop decoding the rest.
        }
        return sb.toString()
    }

    private object FirstLineComplete : Exception()

    private fun buildQuickPrompt(question: String, passages: List<Passage>): String {
        val sb = StringBuilder("Context:\n")
        passages.forEachIndexed { i, p ->
            sb.append("[${i + 1}] ${p.articleTitle}: ${p.text.take(QUICK_PASSAGE_CHARS)}\n")
        }
        sb.append("\nQuestion: ").append(question)
        return sb.toString()
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
        quickConversation?.close()
        quickConversation = null
        createConversation()
    }

    fun release() {
        Log.d(TAG, "Releasing resources")
        conversation?.close()
        conversation = null
        quickConversation?.close()
        quickConversation = null
        engine?.close()
        engine = null
        _state.value = LlmState.Uninitialized
    }
}
