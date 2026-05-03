package com.bahm.thoth.knowledge

import com.bahm.thoth.knowledge.models.Passage
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

@Singleton
class Bm25Scorer @Inject constructor() {

    companion object {
        private const val K1 = 1.2
        private const val B = 0.75
        private val TOKENIZE_REGEX = Regex("[\\s\\p{Punct}]+")
        private val STOPWORDS = setOf(
            "the", "is", "at", "which", "on", "a", "an", "and", "or", "but",
            "in", "with", "to", "for", "of", "it", "its", "this", "that", "be",
            "are", "was", "were", "been", "have", "has", "had", "do", "does",
        )
    }

    fun score(query: String, passages: List<Passage>): List<Pair<Passage, Double>> {
        if (passages.isEmpty()) return emptyList()

        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) return passages.map { it to 0.0 }

        val tokenizedPassages = passages.map { tokenize(it.text) }
        val n = passages.size
        val avgdl = tokenizedPassages.map { it.size.toDouble() }.average()

        // Compute document frequency for each query term
        val df = mutableMapOf<String, Int>()
        for (term in queryTerms) {
            df[term] = tokenizedPassages.count { term in it }
        }

        // Score each passage
        val scored = passages.zip(tokenizedPassages).map { (passage, tokens) ->
            val docLen = tokens.size.toDouble()
            val termFreqs = tokens.groupingBy { it }.eachCount()

            var score = 0.0
            for (term in queryTerms) {
                val tf = (termFreqs[term] ?: 0).toDouble()
                val nq = (df[term] ?: 0).toDouble()
                val idf = ln((n - nq + 0.5) / (nq + 0.5) + 1.0)
                val tfNorm = (tf * (K1 + 1.0)) / (tf + K1 * (1.0 - B + B * docLen / avgdl))
                score += idf * tfNorm
            }

            passage to score
        }

        return scored.sortedByDescending { it.second }
    }

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .split(TOKENIZE_REGEX)
            .filter { it.isNotEmpty() && it !in STOPWORDS }
    }
}
