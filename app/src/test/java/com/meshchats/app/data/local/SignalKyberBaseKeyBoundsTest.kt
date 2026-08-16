package com.meshchats.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fast, Room-free proof of the base-key size bounds enforced before a replay row
 * is ever inserted. The exact `require` these tests exercise runs inside
 * [SignalKyberBaseKeyDao.markKyberUsedWithBaseKey] before it touches the
 * database, so proving the bounds here covers the input-rejection path without a
 * device. A serialized libsignal `ECPublicKey` (Curve25519) is 33 bytes; the
 * ceiling leaves headroom without letting an unbounded blob reach the table.
 */
class SignalKyberBaseKeyBoundsTest {

    @Test
    fun typicalCurve25519PublicKeyIsAccepted() {
        // 33 bytes: the exact serialized size of a Curve25519 ECPublicKey.
        SignalKyberBaseKeyBounds.requireValid(ByteArray(33) { 1 })
    }

    @Test
    fun singleByteIsAccepted() {
        SignalKyberBaseKeyBounds.requireValid(byteArrayOf(7))
    }

    @Test
    fun maxSizeIsAccepted() {
        SignalKyberBaseKeyBounds.requireValid(ByteArray(SignalKyberBaseKeyBounds.MAX_BASE_KEY_BYTES) { 1 })
    }

    @Test
    fun emptyBaseKeyIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalKyberBaseKeyBounds.requireValid(ByteArray(0))
        }
    }

    @Test
    fun oversizeBaseKeyIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalKyberBaseKeyBounds.requireValid(ByteArray(SignalKyberBaseKeyBounds.MAX_BASE_KEY_BYTES + 1))
        }
    }

    @Test
    fun ceilingLeavesHeadroomOverACurve25519Key() {
        // Guards against the ceiling being tightened below the real key size.
        assertEquals(true, SignalKyberBaseKeyBounds.MAX_BASE_KEY_BYTES >= 33)
    }
}
