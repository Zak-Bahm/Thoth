package com.bahm.thoth.knowledge

import com.bahm.thoth.knowledge.models.Passage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Bm25ScorerTest {

    private lateinit var scorer: Bm25Scorer

    @Before
    fun setup() {
        scorer = Bm25Scorer()
    }

    private fun passage(text: String, index: Int = 0) = Passage(
        id = "test#$index",
        text = text,
        articleTitle = "Test",
        sectionHeading = null,
        sectionAnchor = null,
        zimEntryPath = "A/Test",
        chunkIndex = index,
    )

    @Test
    fun `passages with query terms rank higher`() {
        val passages = listOf(
            passage("The cat sat on the mat", 0),
            passage("Quantum physics explains subatomic behavior", 1),
            passage("The cat chased a mouse through the garden", 2),
        )

        val results = scorer.score("cat mouse", passages)

        // Passage with both "cat" and "mouse" should rank first
        assertEquals("test#2", results[0].first.id)
        // Passage with just "cat" should rank second
        assertEquals("test#0", results[1].first.id)
        // Unrelated passage should rank last
        assertEquals("test#1", results[2].first.id)
    }

    @Test
    fun `rare terms boost score via IDF`() {
        val passages = listOf(
            passage("common word common word common word", 0),
            passage("common word rare unique term here", 1),
            passage("another common word passage text", 2),
        )

        val results = scorer.score("rare unique", passages)

        // Passage with rare terms should rank first
        assertEquals("test#1", results[0].first.id)
        assertTrue("Score should be positive", results[0].second > 0.0)
    }

    @Test
    fun `stopwords are ignored in scoring`() {
        val passages = listOf(
            passage("the is at which on a an and or but", 0),
            passage("python programming language guide tutorial", 1),
        )

        val results = scorer.score("the python", passages)

        // "the" is a stopword, so only "python" matters
        assertEquals("test#1", results[0].first.id)
    }

    @Test
    fun `empty query returns all passages with zero scores`() {
        val passages = listOf(
            passage("Some content here", 0),
            passage("Other content there", 1),
        )

        val results = scorer.score("", passages)

        assertEquals(2, results.size)
        results.forEach { (_, score) ->
            assertEquals(0.0, score, 0.001)
        }
    }

    @Test
    fun `empty passages list returns empty results`() {
        val results = scorer.score("test query", emptyList())
        assertTrue(results.isEmpty())
    }

    @Test
    fun `results are sorted by score descending`() {
        val passages = listOf(
            passage("unrelated content about weather", 0),
            passage("machine learning algorithms for classification", 1),
            passage("machine learning deep learning neural networks machine", 2),
        )

        val results = scorer.score("machine learning", passages)

        for (i in 0 until results.size - 1) {
            assertTrue(
                "Results should be sorted descending",
                results[i].second >= results[i + 1].second,
            )
        }
    }

    @Test
    fun `term frequency increases score`() {
        val passages = listOf(
            passage("python is great", 0),
            passage("python python python programming in python", 1),
        )

        val results = scorer.score("python", passages)

        // Passage with more occurrences of "python" should score higher
        assertEquals("test#1", results[0].first.id)
        assertTrue(results[0].second > results[1].second)
    }
}
