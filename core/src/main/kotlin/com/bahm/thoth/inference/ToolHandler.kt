package com.bahm.thoth.inference

import com.bahm.thoth.core.Log
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolHandler @Inject constructor(
    private val thothTools: ThothTools,
) {

    companion object {
        private const val TAG = "ToolHandler"
    }

    fun resetForNewMessage() {
        thothTools.callCount = 0
        thothTools.lastResponse = null
        thothTools.retrievedHits.clear()
        NonceRegistry.reset()
        Log.d(TAG, "Reset for new message — callCount=0, nonces cleared, lastResponse=null, hits cleared")
    }

    fun getStructuredResponse(): StructuredResponse? = thothTools.lastResponse

    /** Passages retrieved by searches during the current message (for eval retrieval scoring). */
    fun getRetrievedHits(): List<RetrievedHit> = thothTools.retrievedHits.toList()

    /**
     * Quick Answer mode bypasses the submitAnswer tool, so it sets the structured response
     * directly. Routing through the same [ThothTools.lastResponse] field means
     * [getStructuredResponse] / getLastSources and the debug harness work unchanged.
     */
    fun setQuickResponse(response: StructuredResponse) {
        thothTools.lastResponse = response
    }

    fun renderToHtml(response: StructuredResponse): String {
        val sb = StringBuilder()
        for (claim in response.claims) {
            sb.append("<p>")
            sb.append(claim.text)
            if (claim.isGrounded && claim.source != null) {
                sb.append(" ").append(citationLink(claim.source))
            }
            sb.append("</p>")
        }
        if (response.ungroundedCount > 0) {
            Log.w(TAG, "Response has ${response.ungroundedCount} ungrounded claim(s)")
        }
        return sb.toString()
    }

    /**
     * A tappable inline citation. The href is a custom `thoth://sec` URI carrying the article
     * path plus the section anchor (and heading text as a fallback). [com.bahm.thoth.ui.common]'s
     * AnswerContent parses it on tap to deep-link into the article at the cited section.
     */
    private fun citationLink(source: PassageSource): String {
        val uri = "thoth://sec?p=${urlEncode(source.zimEntryPath)}" +
            "&a=${urlEncode(source.sectionAnchor)}" +
            "&h=${urlEncode(source.sectionHeading)}"
        val label = if (source.sectionHeading.isNotBlank()) {
            "${source.articleTitle}:${source.sectionHeading}"
        } else {
            source.articleTitle
        }
        return "<a href=\"${uri.htmlEscape()}\">[${label.htmlEscape()}]</a>"
    }

    private fun String.htmlEscape(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
