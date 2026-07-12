package com.bahm.thoth.inference

import com.bahm.thoth.core.Log
import com.bahm.thoth.knowledge.SearchService
import com.bahm.thoth.knowledge.ZimSource
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

data class Claim(
    val text: String,
    val passageNonce: String,
)

data class StructuredResponse(
    val claims: List<ValidatedClaim>,
    val groundedCount: Int,
    val ungroundedCount: Int,
)

data class ValidatedClaim(
    val text: String,
    val source: PassageSource?,
    val isGrounded: Boolean,
)

@Singleton
class ThothTools @Inject constructor(
    private val searchService: SearchService,
    private val zim: ZimSource,
    private val perfTracker: PerfTracker,
) : ToolSet {

    companion object {
        private const val TAG = "ThothTools"

        // lookupArticle returns plain text capped at this many chars. Kept well under the
        // engine token budget (LlmService.MAX_NUM_TOKENS) so a lookup on top of search
        // results + reasoning doesn't overflow the context and break answer generation.
        private const val LOOKUP_MAX_CHARS = 6000
    }

    var callCount: Int = 0
    var lastResponse: StructuredResponse? = null

    /**
     * Passages returned by searches during the current message (thorough mode: every
     * searchKnowledge call; quick mode: the single direct retrieval, appended by LlmService).
     * Cleared per message via [ToolHandler.resetForNewMessage]; surfaced in the eval record so
     * the harness can score retrieval recall (was the gold article/section actually retrieved?).
     */
    val retrievedHits = mutableListOf<RetrievedHit>()

    @Tool(description = "Search Wikipedia using technical keywords. Use specific nouns and terms, NOT natural language questions. Example: for 'why do leaves fall' search 'deciduous abscission leaf senescence'. For 'how do planes fly' search 'aerodynamic lift wing airfoil'.")
    fun searchKnowledge(
        @ToolParam(description = "Technical keywords and terms (not a question). Use 3-5 specific words.")
        query: String
    ): String {
        val startOffset = perfTracker.elapsedMs()
        callCount++
        val result = runBlocking { searchService.search(query, topK = 5) }
        val passages = result.passages.enforceBudget(MAX_CONTEXT_CHARS)

        val nonces = mutableListOf<String>()
        val jsonArray = JSONArray()
        passages.forEachIndexed { index, passage ->
            val nonce = NonceRegistry.generate(
                PassageSource(
                    articleTitle = passage.articleTitle,
                    sectionHeading = passage.sectionHeading ?: "",
                    sectionAnchor = passage.sectionAnchor ?: "",
                    zimEntryPath = passage.zimEntryPath,
                )
            )
            nonces.add(nonce)
            retrievedHits.add(
                RetrievedHit(
                    articleTitle = passage.articleTitle,
                    sectionHeading = passage.sectionHeading ?: "",
                    sectionAnchor = passage.sectionAnchor ?: "",
                    zimEntryPath = passage.zimEntryPath,
                    rank = index,
                    text = passage.text.take(RetrievedHit.TEXT_MAX_CHARS),
                )
            )
            jsonArray.put(JSONObject().apply {
                put("id", nonce)
                put("title", passage.articleTitle)
                put("section", passage.sectionHeading ?: "")
                put("content", passage.text)
            })
        }
        val jsonStr = jsonArray.toString()
        perfTracker.recordTool(
            name = "searchKnowledge",
            startOffsetMs = startOffset,
            endOffsetMs = perfTracker.elapsedMs(),
            detail = mapOf(
                "query" to query,
                "zimMs" to result.zimMs,
                "chunkMs" to result.chunkMs,
                "bm25Ms" to result.bm25Ms,
                "articleCount" to result.articleCount,
                "candidatePassageCount" to result.candidatePassageCount,
                "returnedPassages" to passages.size,
                "contextChars" to jsonStr.length,
            ),
        )
        Log.d(TAG, "searchKnowledge | query=\"$query\" | ${passages.size} passages, nonces=$nonces")
        Log.d(TAG, "searchKnowledge RETURN (first 500 chars): ${jsonStr.take(500)}")
        return jsonStr
    }

    @Tool(description = "Retrieve the full content of a specific Wikipedia article by its exact title. Use this when you need more detail from an article found via searchKnowledge.")
    fun lookupArticle(
        @ToolParam(description = "Exact Wikipedia article title")
        title: String
    ): String {
        val startOffset = perfTracker.elapsedMs()
        callCount++
        Log.d(TAG, "lookupArticle | title=\"$title\"")
        val article = runBlocking { zim.getArticleByTitle(title) }
        val plainText = Jsoup.parse(article?.htmlContent ?: "").text().take(LOOKUP_MAX_CHARS)
        perfTracker.recordTool(
            name = "lookupArticle",
            startOffsetMs = startOffset,
            endOffsetMs = perfTracker.elapsedMs(),
            detail = mapOf(
                "title" to title,
                "found" to (article != null),
                "contentChars" to plainText.length,
            ),
        )
        Log.d(TAG, "lookupArticle returning ${plainText.length} chars")
        return JSONObject().apply {
            put("title", article?.title ?: "Not found")
            put("content", plainText.ifEmpty { "Article not found." })
        }.toString()
    }

    @Tool(description = "Submit your final answer. You MUST use this tool to respond. Each claim goes on a separate line. Line format: id|claim text. The id is the 3-letter (case-sensitive) id from search results. Example: 'qWz|The sky is blue due to Rayleigh scattering.'")
    fun submitAnswer(
        @ToolParam(description = "One claim per line. Format: id|text. Example:\nqWz|First claim here.\nBne|Second claim here.")
        claims: String
    ): String {
        val startOffset = perfTracker.elapsedMs()
        callCount++
        Log.d(TAG, "submitAnswer RAW INPUT:\n$claims")
        val parsedClaims = claims.lines()
            .map { it.trim() }
            .filter { it.contains("|") }
            .map { line ->
                Log.d(TAG, "  Parsing line: \"$line\"")
                val nonce = line.substringBefore("|").trim()
                val text = line.substringAfter("|").trim()
                Log.d(TAG, "  → nonce=\"$nonce\" text=\"${text.take(50)}...\"")
                Claim(text = text, passageNonce = nonce)
            }
        Log.d(TAG, "submitAnswer | ${parsedClaims.size} claims parsed from input")

        val validated = parsedClaims.map { claim ->
            val source = NonceRegistry.validate(claim.passageNonce)
            val isGrounded = source != null
            if (isGrounded) {
                Log.d(TAG, "  Claim GROUNDED — nonce=${claim.passageNonce} → \"${source.articleTitle}/${source.sectionHeading}\"")
            } else {
                Log.w(TAG, "  Claim UNGROUNDED — nonce=${claim.passageNonce} (invalid)")
            }
            ValidatedClaim(
                text = claim.text,
                source = source,
                isGrounded = isGrounded,
            )
        }

        val grounded = validated.count { it.isGrounded }
        val ungrounded = validated.size - grounded
        Log.i(TAG, "submitAnswer complete | $grounded/${validated.size} claims grounded")

        lastResponse = StructuredResponse(
            claims = validated,
            groundedCount = grounded,
            ungroundedCount = ungrounded,
        )

        perfTracker.recordTool(
            name = "submitAnswer",
            startOffsetMs = startOffset,
            endOffsetMs = perfTracker.elapsedMs(),
            detail = mapOf(
                "claimsParsed" to parsedClaims.size,
                "grounded" to grounded,
                "ungrounded" to ungrounded,
            ),
        )
        return "accepted: $grounded/${validated.size} grounded"
    }
}
