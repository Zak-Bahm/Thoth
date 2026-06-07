package com.bahm.thoth.ui.common

import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat

/** A citation surfaced beneath an answer, linking back to a ZIM article. */
data class AnswerCitation(
    val articleTitle: String,
    val zimEntryPath: String,
)

/**
 * Renders a grounded HTML answer followed by tappable citation chips. Shared by the Home
 * quick-answer surface and the Chat detailed view.
 *
 * The answer is drawn in a wrap-content [TextView] (not a WebView): it sizes to its full
 * content height, so the *outer* scroll container (Home's verticalScroll column, Chat's
 * LazyColumn) shows the whole response and scrolls it. A bounded-height WebView previously
 * clipped long answers and its internal scroll fought the outer scroll.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnswerContent(
    html: String,
    citations: List<AnswerCitation>,
    onOpenArticle: (zimEntryPath: String, anchor: String?, heading: String?) -> Unit,
    textColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    HtmlText(
        html = html.ifBlank { "<p>(no answer)</p>" },
        color = textColor,
        linkColor = accentColor,
        onOpenArticle = onOpenArticle,
        modifier = modifier.fillMaxWidth(),
    )

    if (citations.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            citations.forEach { citation ->
                AssistChip(
                    onClick = { onOpenArticle(citation.zimEntryPath, null, null) },
                    label = { Text(citation.articleTitle) },
                )
            }
        }
    }
}

/**
 * Renders basic HTML (<p>, <b>, <i>, <ul>/<ol>/<li>, <br>) plus inline citation links as
 * wrap-content text via a native TextView, which reports its real height to Compose so the
 * full answer is laid out. Citation `<a href="thoth://sec?...">` links are turned into tappable
 * spans that deep-link into the cited article section via [onOpenArticle].
 */
@Composable
private fun HtmlText(
    html: String,
    color: Color,
    linkColor: Color,
    onOpenArticle: (zimEntryPath: String, anchor: String?, heading: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val argb = color.toArgb()
    val linkArgb = linkColor.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                textSize = 15f
                setLineSpacing(0f, 1.3f)
                setTextColor(argb)
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { textView ->
            textView.setTextColor(argb)
            textView.setLinkTextColor(linkArgb)
            textView.text = buildCitationText(html, onOpenArticle)
        },
    )
}

/**
 * Parses [html] and swaps each citation `URLSpan` (href `thoth://sec?p=&a=&h=`) for a
 * [ClickableSpan] that invokes [onOpenArticle] with the decoded path/anchor/heading.
 */
private fun buildCitationText(
    html: String,
    onOpenArticle: (zimEntryPath: String, anchor: String?, heading: String?) -> Unit,
): CharSequence {
    val parsed = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
    val builder = SpannableStringBuilder(parsed)
    val urlSpans = builder.getSpans(0, builder.length, URLSpan::class.java)
    for (span in urlSpans) {
        val url = span.url ?: continue
        if (!url.startsWith("thoth://sec")) continue
        val uri = Uri.parse(url)
        val path = uri.getQueryParameter("p") ?: continue
        val anchor = uri.getQueryParameter("a")?.ifBlank { null }
        val heading = uri.getQueryParameter("h")?.ifBlank { null }
        val start = builder.getSpanStart(span)
        val end = builder.getSpanEnd(span)
        val flags = builder.getSpanFlags(span)
        builder.removeSpan(span)
        builder.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) = onOpenArticle(path, anchor, heading)
            },
            start, end, flags,
        )
    }
    return builder
}
