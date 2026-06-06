package com.bahm.thoth.inference

import java.security.SecureRandom

data class PassageSource(
    val articleTitle: String,
    val sectionHeading: String,
    val zimEntryPath: String,
)

object NonceRegistry {

    private val random = SecureRandom()
    private val registry = mutableMapOf<String, PassageSource>()

    fun generate(source: PassageSource): String {
        val bytes = ByteArray(4)
        random.nextBytes(bytes)
        val nonce = bytes.joinToString("") { "%02x".format(it) }
        registry[nonce] = source
        return nonce
    }

    fun validate(nonce: String): PassageSource? = registry[nonce]

    fun reset() {
        registry.clear()
    }
}
