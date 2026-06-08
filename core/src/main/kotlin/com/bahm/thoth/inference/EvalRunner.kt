package com.bahm.thoth.inference

import com.bahm.thoth.core.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one query through the full pipeline and assembles a platform-neutral [EvalRecord].
 * Shared by the Android debug harness and the desktop CLI so both emit identical records.
 *
 * Each call starts from a fresh conversation so prior turns can't contaminate the result
 * (matches the debug harness's per-query isolation). The caller must ensure the model is loaded.
 */
@Singleton
class EvalRunner @Inject constructor(
    private val llmService: LlmService,
    private val toolHandler: ToolHandler,
    private val perfTracker: PerfTracker,
) {
    companion object {
        private const val TAG = "EvalRunner"
    }

    suspend fun runQuery(query: String, mode: AnswerMode): EvalRecord {
        var answer = ""
        var status = "done"
        try {
            llmService.resetConversation()
            llmService.sendMessage(query, mode).collect { answer = it }
        } catch (e: Exception) {
            status = "error"
            answer = "ERROR: ${e.message}"
            Log.e(TAG, "runQuery failed for \"$query\": ${e.message}", e)
        }

        val structured = toolHandler.getStructuredResponse()
        val claims = structured?.claims?.map { c ->
            EvalClaim(
                text = c.text,
                articleTitle = c.source?.articleTitle ?: "",
                sectionHeading = c.source?.sectionHeading ?: "",
                sectionAnchor = c.source?.sectionAnchor ?: "",
                grounded = c.isGrounded,
            )
        } ?: emptyList()

        val report = perfTracker.lastReport
        val timings = report?.let {
            EvalTimings(
                totalMs = it.totalMs,
                llmComputeMs = it.llmComputeMs,
                toolTotalMs = it.toolTotalMs,
                ttftMs = it.ttftMs,
                searchCallCount = it.searchCallCount,
            )
        }

        return EvalRecord(
            timestamp = System.currentTimeMillis(),
            query = query,
            mode = mode.name.lowercase(),
            status = status,
            answerHtml = answer,
            claims = claims,
            retrievedHits = toolHandler.getRetrievedHits(),
            grounded = structured?.groundedCount ?: 0,
            totalClaims = structured?.claims?.size ?: 0,
            timings = timings,
        )
    }
}
