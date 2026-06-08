package com.bahm.thoth.inference

import org.json.JSONArray
import org.json.JSONObject

/** One passage returned by a search during a query (for scoring retrieval recall). */
data class RetrievedHit(
    val articleTitle: String,
    val sectionHeading: String,
    val sectionAnchor: String,
    val zimEntryPath: String,
    val rank: Int,
)

/** A final-answer claim with its (flattened) citation, for scoring citation correctness. */
data class EvalClaim(
    val text: String,
    val articleTitle: String,
    val sectionHeading: String,
    val sectionAnchor: String,
    val grounded: Boolean,
)

/** Latency breakdown carried over from [PerfReport]. */
data class EvalTimings(
    val totalMs: Long,
    val llmComputeMs: Long,
    val toolTotalMs: Long,
    val ttftMs: Long,
    val searchCallCount: Int,
)

/**
 * One self-contained eval record per query — everything the (future) scoring harness needs to
 * grade an answer end to end: the answer, its citations, the passages retrieved during the query,
 * grounding counts, and timings. Emitted identically by the Android debug harness and the desktop
 * CLI (one JSON object per line in eval_session.jsonl).
 */
data class EvalRecord(
    val timestamp: Long,
    val query: String,
    val mode: String,
    val status: String,
    val answerHtml: String,
    val claims: List<EvalClaim>,
    val retrievedHits: List<RetrievedHit>,
    val grounded: Int,
    val totalClaims: Int,
    val timings: EvalTimings?,
) {
    fun toJson(): String {
        val claimsJson = JSONArray()
        for (c in claims) {
            claimsJson.put(
                JSONObject()
                    .put("text", c.text)
                    .put("articleTitle", c.articleTitle)
                    .put("sectionHeading", c.sectionHeading)
                    .put("sectionAnchor", c.sectionAnchor)
                    .put("grounded", c.grounded),
            )
        }
        val hitsJson = JSONArray()
        for (h in retrievedHits) {
            hitsJson.put(
                JSONObject()
                    .put("articleTitle", h.articleTitle)
                    .put("sectionHeading", h.sectionHeading)
                    .put("sectionAnchor", h.sectionAnchor)
                    .put("zimEntryPath", h.zimEntryPath)
                    .put("rank", h.rank),
            )
        }
        val obj = JSONObject()
            .put("timestamp", timestamp)
            .put("query", query)
            .put("mode", mode)
            .put("status", status)
            .put("answerHtml", answerHtml)
            .put("claims", claimsJson)
            .put("retrievedHits", hitsJson)
            .put("grounded", grounded)
            .put("totalClaims", totalClaims)
        if (timings != null) {
            obj.put(
                "timings",
                JSONObject()
                    .put("totalMs", timings.totalMs)
                    .put("llmComputeMs", timings.llmComputeMs)
                    .put("toolTotalMs", timings.toolTotalMs)
                    .put("ttftMs", timings.ttftMs)
                    .put("searchCallCount", timings.searchCallCount),
            )
        }
        return obj.toString()
    }
}
