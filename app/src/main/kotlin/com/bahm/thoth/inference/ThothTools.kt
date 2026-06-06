package com.bahm.thoth.inference

import android.util.Log
import com.bahm.thoth.knowledge.SearchService
import com.bahm.thoth.knowledge.ZimRepository
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
    private val zimRepository: ZimRepository,
) : ToolSet {

    companion object {
        private const val TAG = "ThothTools"
    }

    var callCount: Int = 0
    var lastResponse: StructuredResponse? = null

    @Tool(description = "Search Wikipedia using technical keywords. Use specific nouns and terms, NOT natural language questions. Example: for 'why do leaves fall' search 'deciduous abscission leaf senescence'. For 'how do planes fly' search 'aerodynamic lift wing airfoil'.")
    fun searchKnowledge(
        @ToolParam(description = "Technical keywords and terms (not a question). Use 3-5 specific words.")
        query: String
    ): String {
        callCount++
        val result = runBlocking { searchService.search(query, topK = 5) }
        val passages = result.passages.enforceBudget(MAX_CONTEXT_CHARS)

        val nonces = mutableListOf<String>()
        val jsonArray = JSONArray()
        for (passage in passages) {
            val nonce = NonceRegistry.generate(
                PassageSource(
                    articleTitle = passage.articleTitle,
                    sectionHeading = passage.sectionHeading ?: "",
                    zimEntryPath = passage.zimEntryPath,
                )
            )
            nonces.add(nonce)
            jsonArray.put(JSONObject().apply {
                put("id", nonce)
                put("title", passage.articleTitle)
                put("section", passage.sectionHeading ?: "")
                put("content", passage.text)
            })
        }
        val jsonStr = jsonArray.toString()
        Log.d(TAG, "searchKnowledge | query=\"$query\" | ${passages.size} passages, nonces=$nonces")
        Log.d(TAG, "searchKnowledge RETURN (first 500 chars): ${jsonStr.take(500)}")
        return jsonStr
    }

    @Tool(description = "Retrieve the full content of a specific Wikipedia article by its exact title. Use this when you need more detail from an article found via searchKnowledge.")
    fun lookupArticle(
        @ToolParam(description = "Exact Wikipedia article title")
        title: String
    ): String {
        callCount++
        Log.d(TAG, "lookupArticle | title=\"$title\"")
        val article = runBlocking { zimRepository.getArticleByTitle(title) }
        val plainText = Jsoup.parse(article?.htmlContent ?: "").text().take(16000)
        Log.d(TAG, "lookupArticle returning ${plainText.length} chars")
        return JSONObject().apply {
            put("title", article?.title ?: "Not found")
            put("content", plainText.ifEmpty { "Article not found." })
        }.toString()
    }

    @Tool(description = "Submit your final answer. You MUST use this tool to respond. Each claim goes on a separate line. Line format: id|claim text. The id is the 8-character hex id from search results. Example: 'a1b2c3d4|The sky is blue due to Rayleigh scattering.'")
    fun submitAnswer(
        @ToolParam(description = "One claim per line. Format: id|text. Example:\na1b2c3d4|First claim here.\ne5f6a7b8|Second claim here.")
        claims: String
    ): String {
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

        return "accepted: $grounded/${validated.size} grounded"
    }
}
