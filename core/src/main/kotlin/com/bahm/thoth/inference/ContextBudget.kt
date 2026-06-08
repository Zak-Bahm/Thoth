package com.bahm.thoth.inference

import com.bahm.thoth.knowledge.models.Passage

const val MAX_CONTEXT_CHARS = 16_000

fun List<Passage>.enforceBudget(maxChars: Int = MAX_CONTEXT_CHARS): List<Passage> {
    var total = 0
    return takeWhile { passage ->
        total += passage.text.length
        total <= maxChars
    }
}
