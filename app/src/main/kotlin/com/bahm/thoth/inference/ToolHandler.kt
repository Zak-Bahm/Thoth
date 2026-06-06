package com.bahm.thoth.inference

import android.util.Log
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
        NonceRegistry.reset()
        Log.d(TAG, "Reset for new message — callCount=0, nonces cleared, lastResponse=null")
    }

    fun getStructuredResponse(): StructuredResponse? = thothTools.lastResponse

    fun renderToHtml(response: StructuredResponse): String {
        val sb = StringBuilder()
        for (claim in response.claims) {
            sb.append("<p>")
            sb.append(claim.text)
            if (claim.isGrounded && claim.source != null) {
                sb.append(" <cite>[${claim.source.articleTitle}]</cite>")
            }
            sb.append("</p>")
        }
        if (response.ungroundedCount > 0) {
            Log.w(TAG, "Response has ${response.ungroundedCount} ungrounded claim(s)")
        }
        return sb.toString()
    }
}
