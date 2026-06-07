package com.bahm.thoth.ui.common

import android.graphics.Color as AndroidColor
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** A citation surfaced beneath an answer, linking back to a ZIM article. */
data class AnswerCitation(
    val articleTitle: String,
    val zimEntryPath: String,
)

/**
 * Renders a grounded HTML answer (in a sandboxed WebView) followed by tappable citation chips.
 * Shared by the Home quick-answer surface and the Chat detailed view.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnswerContent(
    html: String,
    citations: List<AnswerCitation>,
    onOpenArticle: (zimEntryPath: String) -> Unit,
    textColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier,
    maxHeight: androidx.compose.ui.unit.Dp = 400.dp,
) {
    HtmlContent(
        html = wrapWithCss(
            bodyHtml = html.ifBlank { "<p>(no answer)</p>" },
            textColor = textColor.toCssHex(),
            accentColor = accentColor.toCssHex(),
        ),
        modifier = modifier,
        maxHeight = maxHeight,
    )

    if (citations.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            citations.forEach { citation ->
                AssistChip(
                    onClick = { onOpenArticle(citation.zimEntryPath) },
                    label = { Text(citation.articleTitle) },
                )
            }
        }
    }
}

/**
 * Sandboxed WebView: no JavaScript, no DOM storage, no file/content access, no network.
 * Bounded height with internal scroll for long answers (per plan guidance for
 * WebView-in-LazyColumn).
 */
@Composable
private fun HtmlContent(html: String, modifier: Modifier, maxHeight: androidx.compose.ui.unit.Dp) {
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                setBackgroundColor(AndroidColor.TRANSPARENT)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        },
    )
}

private fun Color.toCssHex(): String = "#%06X".format(0xFFFFFF and toArgb())

private fun wrapWithCss(bodyHtml: String, textColor: String, accentColor: String): String = """
    <!DOCTYPE html>
    <html>
    <head>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
      body { margin: 0; padding: 0; background: transparent;
             font-family: sans-serif; font-size: 15px; line-height: 1.5; color: $textColor; }
      p { margin: 0 0 8px 0; }
      ul, ol { margin: 0 0 8px 0; padding-left: 20px; }
      li { margin: 0 0 4px 0; }
      b, strong { font-weight: 700; }
      cite { color: $accentColor; font-style: normal; font-size: 12px; }
    </style>
    </head>
    <body>$bodyHtml</body>
    </html>
""".trimIndent()
