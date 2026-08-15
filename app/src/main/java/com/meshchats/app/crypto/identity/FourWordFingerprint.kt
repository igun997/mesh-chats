package com.meshchats.app.crypto.identity

/**
 * Derives the short, human-comparable four-word display of a device fingerprint.
 *
 * ## This display is a convenience only — NOT authoritative
 *
 * The four words encode only the **first 44 bits** of the SHA-256 fingerprint
 * (four words × 11 bits). 44 bits is deliberately short enough for two people to
 * read aloud and compare, but it is **not** collision-resistant against a
 * motivated attacker: roughly `2^22` identities suffice for a birthday collision
 * on the displayed words. It exists purely to catch accidental mismatches and
 * casual errors in an out-of-band check.
 *
 * Authoritative identity comparison MUST use the full 32-byte fingerprint or the
 * QR payload (which carries the full public key and signed binding), never these
 * four words. Callers and UI must present the words as a convenience aid and the
 * QR/full-key comparison as the real verification. This is an API contract, not
 * an implementation detail.
 *
 * ## Determinism
 *
 * The mapping is fixed: take the fingerprint's first 44 bits, big-endian, split
 * into four 11-bit groups (most-significant group first), and index each group
 * into the fixed [FourWordList]. The same fingerprint always yields the same
 * words on every device and app version, so two users comparing the same identity
 * see identical words.
 */
class FourWordFingerprint(private val wordList: FourWordList) {

    /**
     * Returns the four words for [fingerprint], using its first
     * [SHORT_DISPLAY_BITS] bits. [fingerprint] must be at least
     * [MIN_FINGERPRINT_BYTES] bytes (a SHA-256 fingerprint is 32).
     *
     * A defensive copy of the needed prefix is taken; [fingerprint] is not
     * retained or mutated.
     */
    fun words(fingerprint: ByteArray): List<String> {
        require(fingerprint.size >= MIN_FINGERPRINT_BYTES) {
            "fingerprint must be at least $MIN_FINGERPRINT_BYTES bytes"
        }

        // Assemble the first 44 bits from the leading 6 bytes (48 bits) into a
        // long, then discard the low 4 bits so only the top 44 remain.
        var acc = 0L
        for (i in 0 until PREFIX_BYTES) {
            acc = (acc shl 8) or (fingerprint[i].toLong() and 0xFF)
        }
        // acc now holds 48 bits; keep the top 44 by shifting off the low 4.
        val bits44 = acc ushr (PREFIX_BYTES * 8 - SHORT_DISPLAY_BITS)

        // Split into four 11-bit groups, most-significant first.
        val indices = IntArray(WORD_COUNT_IN_DISPLAY)
        for (group in 0 until WORD_COUNT_IN_DISPLAY) {
            val shift = (WORD_COUNT_IN_DISPLAY - 1 - group) * FourWordList.BITS_PER_WORD
            indices[group] = ((bits44 ushr shift) and WORD_MASK).toInt()
        }
        return indices.map { wordList.word(it) }
    }

    /** Convenience: the four words joined by [separator]. */
    fun display(fingerprint: ByteArray, separator: String = "-"): String =
        words(fingerprint).joinToString(separator)

    companion object {
        /** Words in the short display. */
        const val WORD_COUNT_IN_DISPLAY: Int = 4

        /** Total bits encoded: four words × 11 bits. */
        const val SHORT_DISPLAY_BITS: Int = WORD_COUNT_IN_DISPLAY * FourWordList.BITS_PER_WORD // 44

        /** Bytes read from the fingerprint prefix (48 bits ≥ 44). */
        private const val PREFIX_BYTES: Int = 6

        /** Low 11 bits mask. */
        private const val WORD_MASK: Long = 0x7FF

        /** Minimum fingerprint length accepted (SHA-256 is 32). */
        const val MIN_FINGERPRINT_BYTES: Int = PREFIX_BYTES
    }
}
