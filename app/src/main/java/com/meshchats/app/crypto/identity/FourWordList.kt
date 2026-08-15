package com.meshchats.app.crypto.identity

import java.io.BufferedReader
import java.text.Normalizer

/**
 * A bounded reason the four-word list resource could not be loaded or is not a
 * valid list. A failure here is a build/packaging defect, never attacker input:
 * the list is a checked-in resource, so any problem means the app was shipped
 * wrong and the short display must not be produced from a degraded list.
 */
enum class FourWordListError {
    /** The word-list resource was not found on the classpath. */
    RESOURCE_MISSING,

    /** The resource could not be read. */
    IO_FAILED,

    /** The list does not contain exactly [FourWordList.WORD_COUNT] words. */
    WRONG_SIZE,

    /** A word repeats; the list must be unique so the mapping is a bijection. */
    DUPLICATE_WORD,

    /** A word is empty or not NFKD-normalized/trimmed as required. */
    MALFORMED_WORD,
}

/** Raised when the four-word list resource is missing or malformed. */
class FourWordListException(val error: FourWordListError) :
    IllegalStateException("four-word list unavailable: $error")

/**
 * The fixed 2048-word list used only to render the short, human-comparable
 * four-word display of a device fingerprint.
 *
 * Provenance: this is the canonical BIP-39 English word list (2048 words,
 * SHA-256 `2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda`),
 * bundled as a checked-in resource. BIP-39 is public domain (Creative Commons
 * CC0 in the reference wordlist); it is used here purely as a fixed, widely
 * reviewed list of short, unambiguous English words. It is **not** used as a
 * mnemonic and carries no BIP-39 checksum semantics here.
 *
 * The list is validated on load to be exactly [WORD_COUNT] unique,
 * NFKD-normalized, trimmed, non-empty words, so the 11-bits-per-word mapping is a
 * total bijection over `0..2047`. A defect fails closed with a
 * [FourWordListException] rather than silently producing a degraded display.
 *
 * The four-word display is a convenience only; see [FourWordFingerprint] and the
 * repository KDoc. Authoritative comparison always uses the full fingerprint or
 * the QR payload, never these four words.
 */
class FourWordList private constructor(private val words: List<String>) {

    /** The word at [index] in `0 until WORD_COUNT`. */
    fun word(index: Int): String {
        require(index in 0 until WORD_COUNT) { "index out of range" }
        return words[index]
    }

    /** Read-only view of all words, in canonical order. */
    fun all(): List<String> = words

    companion object {
        /** Exactly this many words; 11 bits address one word. */
        const val WORD_COUNT: Int = 2048

        /** Bits addressed by one word (`2^11 == 2048`). */
        const val BITS_PER_WORD: Int = 11

        /** Classpath location of the checked-in word list, one word per line. */
        const val RESOURCE: String = "/com/meshchats/app/crypto/fourword-english.txt"

        /**
         * Loads and validates the bundled list from the classpath. Fails closed
         * with a [FourWordListException] if the resource is missing, unreadable,
         * the wrong size, non-unique, or contains a malformed word.
         */
        fun load(): FourWordList {
            val stream = FourWordList::class.java.getResourceAsStream(RESOURCE)
                ?: throw FourWordListException(FourWordListError.RESOURCE_MISSING)
            val raw = try {
                stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readLines)
            } catch (_: Exception) {
                throw FourWordListException(FourWordListError.IO_FAILED)
            }
            return fromLines(raw)
        }

        /**
         * Builds and validates a list from already-read [lines]. Exposed so the
         * loader and tests share one validation path. Trailing blank lines (a
         * common newline-at-EOF artifact) are ignored before validation.
         */
        fun fromLines(lines: List<String>): FourWordList {
            val trimmed = lines.dropLastWhile { it.isBlank() }
            if (trimmed.size != WORD_COUNT) throw FourWordListException(FourWordListError.WRONG_SIZE)

            val seen = HashSet<String>(WORD_COUNT * 2)
            for (word in trimmed) {
                if (word.isEmpty() || word != word.trim()) {
                    throw FourWordListException(FourWordListError.MALFORMED_WORD)
                }
                if (Normalizer.normalize(word, Normalizer.Form.NFKD) != word) {
                    throw FourWordListException(FourWordListError.MALFORMED_WORD)
                }
                if (!seen.add(word)) throw FourWordListException(FourWordListError.DUPLICATE_WORD)
            }
            return FourWordList(trimmed.toList())
        }
    }
}
