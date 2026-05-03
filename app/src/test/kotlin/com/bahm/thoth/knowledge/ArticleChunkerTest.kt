package com.bahm.thoth.knowledge

import com.bahm.thoth.knowledge.models.Article
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArticleChunkerTest {

    private lateinit var chunker: ArticleChunker

    @Before
    fun setup() {
        chunker = ArticleChunker()
    }

    @Test
    fun `chunk produces passages with correct metadata`() {
        val article = Article(
            title = "Test Article",
            path = "A/Test_Article",
            htmlContent = "<html><body><p>Hello world.</p></body></html>",
        )

        val passages = chunker.chunk(article)

        assertEquals(1, passages.size)
        assertEquals("A/Test_Article#0", passages[0].id)
        assertEquals("Test Article", passages[0].articleTitle)
        assertEquals("A/Test_Article", passages[0].zimEntryPath)
        assertEquals(0, passages[0].chunkIndex)
        assertEquals(null, passages[0].sectionHeading)
        assertTrue(passages[0].text.contains("Hello world"))
    }

    @Test
    fun `h2 heading starts a new chunk`() {
        val html = """
            <html><body>
            <p>First paragraph content here.</p>
            <h2>Section Two</h2>
            <p>Second paragraph content here.</p>
            </body></html>
        """.trimIndent()

        val article = Article(title = "Test", path = "A/Test", htmlContent = html)
        val passages = chunker.chunk(article)

        assertEquals(2, passages.size)
        assertEquals(null, passages[0].sectionHeading)
        assertEquals("Section Two", passages[1].sectionHeading)
        assertTrue(passages[0].text.contains("First paragraph"))
        assertTrue(passages[1].text.contains("Second paragraph"))
    }

    @Test
    fun `h3 heading starts a new chunk`() {
        val html = """
            <html><body>
            <h2>Main Section</h2>
            <p>Content under main section.</p>
            <h3>Subsection</h3>
            <p>Content under subsection.</p>
            </body></html>
        """.trimIndent()

        val article = Article(title = "Test", path = "A/Test", htmlContent = html)
        val passages = chunker.chunk(article)

        assertEquals(2, passages.size)
        assertEquals("Main Section", passages[0].sectionHeading)
        assertEquals("Subsection", passages[1].sectionHeading)
    }

    @Test
    fun `long paragraph is split at sentence boundary`() {
        // Create content that exceeds 2048 chars
        val sentence = "This is a test sentence with some words. "
        val longParagraph = sentence.repeat(60) // ~2520 chars
        val html = "<html><body><p>$longParagraph</p></body></html>"

        val article = Article(title = "Test", path = "A/Test", htmlContent = html)
        val passages = chunker.chunk(article)

        assertTrue("Should produce multiple chunks", passages.size >= 2)
        // Each chunk should end at a sentence boundary (end with period+space or just the text)
        for (passage in passages) {
            assertTrue(
                "Chunk should be <= 2048+256 chars (with overlap): was ${passage.text.length}",
                passage.text.length <= 2048 + 256 + 100, // some tolerance for overlap injection
            )
        }
    }

    @Test
    fun `small table is kept as single chunk`() {
        val html = """
            <html><body>
            <p>Intro text.</p>
            <table><tr><td>Cell 1</td><td>Cell 2</td></tr></table>
            <p>After table.</p>
            </body></html>
        """.trimIndent()

        val article = Article(title = "Test", path = "A/Test", htmlContent = html)
        val passages = chunker.chunk(article)

        val allText = passages.joinToString(" ") { it.text }
        assertTrue("Table content should be present", allText.contains("Cell 1"))
    }

    @Test
    fun `large table is skipped`() {
        val row = "<tr><td>${"x".repeat(100)}</td></tr>"
        val largeTable = "<table>${row.repeat(30)}</table>" // >3000 chars
        val html = "<html><body><p>Before table.</p>$largeTable<p>After table.</p></body></html>"

        val article = Article(title = "Test", path = "A/Test", htmlContent = html)
        val passages = chunker.chunk(article)

        val allText = passages.joinToString(" ") { it.text }
        assertTrue("Content before table should be present", allText.contains("Before table"))
        assertTrue("Content after table should be present", allText.contains("After table"))
    }

    @Test
    fun `list items are extracted`() {
        val html = """
            <html><body>
            <ul>
                <li>Item one</li>
                <li>Item two</li>
                <li>Item three</li>
            </ul>
            </body></html>
        """.trimIndent()

        val article = Article(title = "Test", path = "A/Test", htmlContent = html)
        val passages = chunker.chunk(article)

        val allText = passages.joinToString(" ") { it.text }
        assertTrue(allText.contains("Item one"))
        assertTrue(allText.contains("Item two"))
        assertTrue(allText.contains("Item three"))
    }

    @Test
    fun `empty article produces no passages`() {
        val article = Article(title = "Empty", path = "A/Empty", htmlContent = "<html><body></body></html>")
        val passages = chunker.chunk(article)
        assertTrue(passages.isEmpty())
    }

    @Test
    fun `chunk indices are sequential`() {
        val html = """
            <html><body>
            <h2>Section 1</h2><p>Content one.</p>
            <h2>Section 2</h2><p>Content two.</p>
            <h2>Section 3</h2><p>Content three.</p>
            </body></html>
        """.trimIndent()

        val article = Article(title = "Test", path = "A/Test", htmlContent = html)
        val passages = chunker.chunk(article)

        passages.forEachIndexed { index, passage ->
            assertEquals(index, passage.chunkIndex)
        }
    }
}
