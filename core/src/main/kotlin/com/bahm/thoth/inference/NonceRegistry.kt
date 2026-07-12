package com.bahm.thoth.inference

import java.security.SecureRandom

data class PassageSource(
    val articleTitle: String,
    val sectionHeading: String,
    val sectionAnchor: String,
    val zimEntryPath: String,
)

object NonceRegistry {

    private val random = SecureRandom()
    private val registry = mutableMapOf<String, PassageSource>()

    // 3 case-sensitive letters (a–z, A–Z) → 52³ ≈ 140k ids. The old 8-char hex id was corrupted
    // by the 4B model ~30% of the time (digit drop/dup, e.g. 1906036b→19060600), falsely
    // ungrounding correct answers. 3 letters are short enough to copy verbatim, yet the space is
    // large enough that random ids stay collision-free within a message and aren't trivially guessable.
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val NONCE_LEN = 3

    fun generate(source: PassageSource): String {
        var nonce: String
        do {
            nonce = buildString(NONCE_LEN) {
                repeat(NONCE_LEN) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            }
        } while (registry.containsKey(nonce)) // avoid collisions in the smaller id space
        registry[nonce] = source
        return nonce
    }

    fun validate(nonce: String): PassageSource? = registry[nonce]

    fun reset() {
        registry.clear()
    }
}
