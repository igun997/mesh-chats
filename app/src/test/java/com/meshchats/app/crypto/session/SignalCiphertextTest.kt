package com.meshchats.app.crypto.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Pure-JVM spec for [SignalCiphertext] and [SignalCiphertextType], the app-owned
 * envelope carrying a serialized Signal message across the transport boundary
 * without exposing any libsignal type. Bytes are copied defensively on
 * construction and every read; [toString] never emits ciphertext bytes.
 */
class SignalCiphertextTest {

    private fun bytes(seed: Int = 0, size: Int = 16): ByteArray = ByteArray(size) { (it + seed).toByte() }

    @Test
    fun copiesBytesOnConstructionAndRead() {
        val input = bytes(1)
        val ct = SignalCiphertext(SignalCiphertextType.PREKEY, input)
        input.fill(0)
        // Construction copy: unaffected by later input mutation.
        assertArrayEquals(bytes(1), ct.bytes)
        // Read copy: fresh array each time.
        val a = ct.bytes
        val b = ct.bytes
        assertNotSame(a, b)
        a.fill(0)
        assertArrayEquals(bytes(1), ct.bytes)
    }

    @Test
    fun retainsType() {
        assertEquals(SignalCiphertextType.PREKEY, SignalCiphertext(SignalCiphertextType.PREKEY, bytes()).type)
        assertEquals(SignalCiphertextType.WHISPER, SignalCiphertext(SignalCiphertextType.WHISPER, bytes()).type)
    }

    @Test
    fun rejectsEmptyBytes() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalCiphertext(SignalCiphertextType.WHISPER, ByteArray(0))
        }
    }

    @Test
    fun toStringRedactsBytes() {
        val ct = SignalCiphertext(SignalCiphertextType.PREKEY, bytes(3))
        val s = ct.toString()
        assertFalse(s.contains(Base64.getEncoder().encodeToString(bytes(3))))
        assertTrue(s.contains("PREKEY"))
        assertTrue(s.contains("16B"))
    }

    @Test
    fun equalsAndHashCodeAreStructural() {
        val a = SignalCiphertext(SignalCiphertextType.WHISPER, bytes(5))
        val b = SignalCiphertext(SignalCiphertextType.WHISPER, bytes(5))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == SignalCiphertext(SignalCiphertextType.PREKEY, bytes(5)))
        assertFalse(a == SignalCiphertext(SignalCiphertextType.WHISPER, bytes(6)))
    }
}
