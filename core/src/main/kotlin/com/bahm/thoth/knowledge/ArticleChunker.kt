package com.bahm.thoth.knowledge

import com.bahm.thoth.knowledge.models.Article
import com.bahm.thoth.knowledge.models.Passage
import javax.inject.Inject
import javax.inject.Singleton
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

@Singleton
class ArticleChunker @Inject constructor() {

    companion object {
        private const val MAX_CHUNK_CHARS = 2048
        private const val OVERLAP_CHARS = 256
        private val SENTENCE_BOUNDARY = Regex("""[.!?]\s+""")

        // CSS classes to remove before chunking (navigation, chrome, references)
        private val REMOVE_SELECTORS = listOf(
            ".navbox", ".navbar", ".hatnote", ".mbox", ".ambox",
            ".reflist", "ol.references", ".noprint", ".catlinks",
            ".mw-editsection", ".sistersitebox", ".side-box",
            "sup.reference", ".reference", ".toc", "#toc",
            ".mw-heading-collapsible",
        )
    }

    fun chunk(article: Article): List<Passage> {
        val doc = Jsoup.parse(article.htmlContent)

        // Find the actual article content root
        val contentRoot = doc.select(".mw-parser-output").first()
            ?: doc.select("#mw-content-text").first()
            ?: doc.body()

        // Remove navigation, references, and other chrome elements
        for (selector in REMOVE_SELECTORS) {
            contentRoot.select(selector).remove()
        }

        val passages = mutableListOf<Passage>()
        var currentHeading: String? = null
        var currentAnchor: String? = null
        var buffer = StringBuilder()
        var overlapText = ""

        fun flushBuffer() {
            val text = buffer.toString().trim()
            if (text.isNotEmpty()) {
                passages.add(
                    Passage(
                        id = "${article.path}#${passages.size}",
                        text = text,
                        articleTitle = article.title,
                        sectionHeading = currentHeading,
                        sectionAnchor = currentAnchor,
                        zimEntryPath = article.path,
                        chunkIndex = passages.size,
                    )
                )
                overlapText = if (text.length > OVERLAP_CHARS) {
                    text.substring(text.length - OVERLAP_CHARS)
                } else {
                    text
                }
            }
            buffer = StringBuilder()
        }

        fun appendText(text: String) {
            if (text.isBlank()) return

            if (buffer.length + text.length > MAX_CHUNK_CHARS && buffer.isNotEmpty()) {
                val combined = buffer.toString() + text
                val splitPoint = findSentenceBoundary(combined, MAX_CHUNK_CHARS)
                if (splitPoint > 0) {
                    buffer = StringBuilder(combined.substring(0, splitPoint))
                    flushBuffer()
                    buffer.append(overlapText)
                    buffer.append(combined.substring(splitPoint))
                } else {
                    flushBuffer()
                    buffer.append(overlapText)
                    buffer.append(text)
                }
            } else {
                buffer.append(text)
            }

            while (buffer.length > MAX_CHUNK_CHARS) {
                val content = buffer.toString()
                val splitPoint = findSentenceBoundary(content, MAX_CHUNK_CHARS)
                if (splitPoint > 0) {
                    buffer = StringBuilder(content.substring(0, splitPoint))
                    flushBuffer()
                    buffer.append(overlapText)
                    buffer.append(content.substring(splitPoint))
                } else {
                    buffer = StringBuilder(content.substring(0, MAX_CHUNK_CHARS))
                    flushBuffer()
                    buffer.append(overlapText)
                    buffer.append(content.substring(MAX_CHUNK_CHARS))
                }
            }
        }

        // Process direct children of the content root
        for (element in contentRoot.children()) {
            processElement(element, ::appendText, ::flushBuffer) { heading, anchor ->
                currentHeading = heading
                currentAnchor = anchor.ifEmpty { null }
            }
        }

        flushBuffer()
        return passages
    }

    private fun processElement(
        element: Element,
        appendText: (String) -> Unit,
        flushBuffer: () -> Unit,
        setHeading: (heading: String, anchor: String) -> Unit,
    ) {
        val tagName = element.tagName().lowercase()

        when {
            tagName in setOf("h1", "h2", "h3") -> {
                flushBuffer()
                // mwoffliner puts the section id directly on the heading element
                // (e.g. <h2 id="History">); older dumps use a child .mw-headline span.
                val anchor = element.id().ifEmpty {
                    element.selectFirst(".mw-headline[id]")?.id()
                        ?: element.selectFirst("[id]")?.id().orEmpty()
                }
                setHeading(element.text(), anchor)
            }

            tagName == "table" -> {
                val tableText = element.text()
                if (tableText.length <= MAX_CHUNK_CHARS) {
                    appendText(tableText + "\n")
                }
            }

            tagName == "ul" || tagName == "ol" -> {
                val listText = extractListText(element)
                appendText(listText)
            }

            tagName == "p" -> {
                val text = element.text()
                if (text.isNotBlank()) {
                    appendText(text + "\n")
                }
            }

            tagName == "blockquote" || tagName == "figcaption" || tagName == "dd" -> {
                val text = element.text()
                if (text.isNotBlank()) {
                    appendText(text + "\n")
                }
            }

            tagName in setOf("script", "style", "nav", "footer", "header", "link", "meta") -> {
                // Skip non-content elements
            }

            tagName in setOf("div", "section", "article", "main", "figure", "aside", "details") -> {
                // Recurse into container elements
                for (child in element.children()) {
                    processElement(child, appendText, flushBuffer, setHeading)
                }
            }

            else -> {
                // For other elements (span, a, b, i, dl, dt, etc.), extract text
                val text = element.text()
                if (text.isNotBlank()) {
                    appendText(text + " ")
                }
            }
        }
    }

    private fun extractListText(listElement: Element): String {
        val sb = StringBuilder()
        for (li in listElement.select("> li")) {
            sb.append("• ").append(li.text()).append("\n")
        }
        return sb.toString()
    }

    private fun findSentenceBoundary(text: String, maxPos: Int): Int {
        var lastBoundary = -1
        for (match in SENTENCE_BOUNDARY.findAll(text)) {
            val endPos = match.range.last + 1
            if (endPos <= maxPos) {
                lastBoundary = endPos
            } else {
                break
            }
        }
        return lastBoundary
    }
}
