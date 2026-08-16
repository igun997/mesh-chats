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
 * Pure-JVM spec for [VerifiedSignalPeer], the app-owned boundary describing a
 * remote party a session may be established with. It carries no libsignal type,
 * derives its stable protocol name canonically from the FULL fingerprint, copies
 * its mutable byte inputs defensively, and never leaks key bytes through
 * [toString].
 */
class VerifiedSignalPeerTest {

    private fun fp(seed: Int = 0): ByteArray = ByteArray(32) { (it + seed).toByte() }
    private fun identityBytes(): ByteArray = ByteArray(33) { (it + 5).toByte() }

    private fun peer(
        fingerprint: ByteArray = fp(),
        deviceId: Int = 1,
        identity: ByteArray = identityBytes(),
    ) = VerifiedSignalPeer(
        fingerprintSha256 = fingerprint,
        deviceId = deviceId,
        expectedSignalIdentityKey = identity,
    )

    @Test
    fun protocolNameDerivedFromFullFingerprint() {
        val fingerprint = fp(9)
        val p = peer(fingerprint = fingerprint)
        val expected = "mc1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprint)
        assertEquals(expected, p.protocolName)
    }

    @Test
    fun copiesFingerprintDefensively() {
        val fingerprint = fp(1)
        val p = peer(fingerprint = fingerprint)
        fingerprint.fill(0)
        // The derived name must reflect the ORIGINAL bytes, not the later mutation.
        val expected = "mc1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(fp(1))
        assertEquals(expected, p.protocolName)
    }

    @Test
    fun copiesIdentityKeyOnReadAndConstruction() {
        val identity = identityBytes()
        val p = peer(identity = identity)
        identity.fill(0)
        // Construction copy: stored value unaffected by later mutation of the input.
        assertArrayEquals(identityBytes(), p.expectedSignalIdentityKey)
        // Read copy: each getter yields a fresh array.
        val first = p.expectedSignalIdentityKey
        val second = p.expectedSignalIdentityKey
        assertNotSame(first, second)
        first.fill(0)
        assertArrayEquals(identityBytes(), p.expectedSignalIdentityKey)
    }

    @Test
    fun rejectsWrongLengthFingerprint() {
        assertThrows(IllegalArgumentException::class.java) { peer(fingerprint = ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { peer(fingerprint = ByteArray(33)) }
    }

    @Test
    fun rejectsNonPositiveDeviceId() {
        assertThrows(IllegalArgumentException::class.java) { peer(deviceId = 0) }
        assertThrows(IllegalArgumentException::class.java) { peer(deviceId = -1) }
    }

    @Test
    fun rejectsEmptyOrUnboundedIdentityKey() {
        assertThrows(IllegalArgumentException::class.java) { peer(identity = ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) {
            peer(identity = ByteArray(VerifiedSignalPeer.MAX_IDENTITY_KEY_BYTES + 1))
        }
    }

    @Test
    fun toStringRedactsKeyBytes() {
        val p = peer()
        val s = p.toString()
        // Never emits raw key bytes; reports only the sizes / ids / derived name.
        assertFalse(s.contains(Base64.getEncoder().encodeToString(identityBytes())))
        assertTrue(s.contains("deviceId=1"))
        assertTrue(s.contains("expectedSignalIdentityKey=33B"))
        assertTrue(s.contains(p.protocolName))
    }

    @Test
    fun equalsAndHashCodeAreStructural() {
        assertEquals(peer(), peer())
        assertEquals(peer().hashCode(), peer().hashCode())
        assertFalse(peer() == peer(fingerprint = fp(2)))
        assertFalse(peer() == peer(deviceId = 2))
        assertFalse(peer() == peer(identity = ByteArray(33) { 1 }))
    }
}
