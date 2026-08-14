package com.meshchats.app.data.local

/**
 * Encodes a raw 256-bit database key into the byte form SQLCipher accepts as a
 * *raw key* rather than a passphrase.
 *
 * SQLCipher treats a key of the exact textual form `x'<64 hex chars>'` as the
 * literal 32-byte AES key and skips PBKDF2 key derivation entirely; the salt is
 * then read from the first 16 bytes of the database file. Passing the 32 raw
 * bytes directly instead would make SQLCipher derive a *different* key from them
 * via PBKDF2, so the two code paths (migration export and Room open) must agree
 * on this raw-key encoding to open the same database.
 *
 * The key is never rendered into a SQL string or logged: this helper only ever
 * produces the ASCII bytes SQLCipher's `sqlite3_key`/`SupportOpenHelperFactory`
 * consume directly. Where a key must reach SQL (e.g. `ATTACH ... KEY ?` during
 * export) it is bound as a statement parameter, never concatenated.
 */
object SqlCipherRawKey {

    private const val HEX_DIGITS = "0123456789abcdef"

    /** The exact number of raw key bytes SQLCipher's raw-key form encodes (256-bit). */
    const val KEY_SIZE_BYTES: Int = 32

    /**
     * Returns the ASCII bytes of `x'<hex>'` for [key]. The caller owns the result
     * and should zero it once the database that consumes it is closed; SQLCipher
     * retains the array for the lifetime of the open database (it re-keys every
     * connection), so it must not be zeroed while any database opened with it is
     * still in use.
     *
     * @throws IllegalArgumentException if [key] is not exactly 32 bytes — a
     * wrong-sized key is a programming error, not a runtime-recoverable state.
     */
    fun encode(key: ByteArray): ByteArray {
        require(key.size == KEY_SIZE_BYTES) {
            "raw key must be $KEY_SIZE_BYTES bytes, was ${key.size}"
        }
        // x' + 64 hex + '  == 67 bytes.
        val out = ByteArray(2 + key.size * 2 + 1)
        out[0] = 'x'.code.toByte()
        out[1] = '\''.code.toByte()
        var o = 2
        for (b in key) {
            val v = b.toInt() and 0xFF
            out[o++] = HEX_DIGITS[v ushr 4].code.toByte()
            out[o++] = HEX_DIGITS[v and 0x0F].code.toByte()
        }
        out[o] = '\''.code.toByte()
        return out
    }
}
