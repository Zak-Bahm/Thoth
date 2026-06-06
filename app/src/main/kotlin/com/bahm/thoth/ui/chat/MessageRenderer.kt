package com.bahm.thoth.ui.chat

import android.graphics.Color as AndroidColor
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun UserMessageBubble(message: ChatMessage) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "You",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantMessageBubble(
    message: ChatMessage,
    onOpenArticle: (zimEntryPath: String) -> Unit,
) {
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.primary

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Thoth",
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
            )
            Spacer(Modifier.height(4.dp))

            if (message.isGenerating) {
                ThinkingIndicator(color = textColor)
            } else {
                HtmlContent(
                    html = wrapWithCss(
                        bodyHtml = message.content.ifBlank { "<p>(no answer)</p>" },
                        textColor = textColor.toCssHex(),
                        accentColor = accentColor.toCssHex(),
                    ),
                )

                if (message.sources.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        message.sources.forEach { source ->
                            AssistChip(
                                onClick = { onOpenArticle(source.zimEntryPath) },
                                label = { Text(source.articleTitle) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingIndicator(color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.height(16.dp).width(16.dp),
            strokeWidth = 2.dp,
            color = color,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "thinking…",
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

/**
 * Sandboxed WebView: no JavaScript, no DOM storage, no file/content access, no network.
 * Bounded height with internal scroll for long answers (per plan guidance for
 * WebView-in-LazyColumn).
 */
@Composable
private fun HtmlContent(html: String) {
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp),
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
