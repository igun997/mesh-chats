package com.meshchats.app.crypto.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Pure-JVM spec for the canonical protocol-name derivation. The name is the sole
 * stable Signal protocol-address identifier the app uses; it is derived ONLY from
 * the full 32-byte Ed25519 fingerprint, never from four words, a BLE id, or a
 * display name.
 */
class SignalProtocolNameTest {

    private fun fp(seed: Int): ByteArray = ByteArray(32) { (it + seed).toByte() }

    @Test
    fun derivesMc1PrefixedBase64UrlWithoutPadding() {
        val fingerprint = fp(0)
        val name = SignalProtocolName.fromFingerprint(fingerprint)

        val expected = "mc1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprint)
        assertEquals(expected, name)
        assertTrue(name.startsWith("mc1:"))
        // base64url alphabet only after the prefix — no '+', '/', or '=' padding.
        val body = name.removePrefix("mc1:")
        assertTrue(body.none { it == '+' || it == '/' || it == '=' })
    }

    @Test
    fun isStableForSameFingerprint() {
        assertEquals(SignalProtocolName.fromFingerprint(fp(7)), SignalProtocolName.fromFingerprint(fp(7)))
    }

    @Test
    fun differsForDifferentFingerprints() {
        assertNotEquals(SignalProtocolName.fromFingerprint(fp(1)), SignalProtocolName.fromFingerprint(fp(2)))
    }

    @Test
    fun rejectsWrongLengthFingerprint() {
        assertThrows(IllegalArgumentException::class.java) { SignalProtocolName.fromFingerprint(ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { SignalProtocolName.fromFingerprint(ByteArray(33)) }
        assertThrows(IllegalArgumentException::class.java) { SignalProtocolName.fromFingerprint(ByteArray(0)) }
    }

    @Test
    fun doesNotMutateInput() {
        val fingerprint = fp(3)
        val copy = fingerprint.copyOf()
        SignalProtocolName.fromFingerprint(fingerprint)
        assertTrue(fingerprint.contentEquals(copy))
    }
}
