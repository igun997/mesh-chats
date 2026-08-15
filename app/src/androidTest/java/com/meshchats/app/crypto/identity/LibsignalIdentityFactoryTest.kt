package com.meshchats.app.crypto.identity

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the REAL libsignal 0.100.0 identity factory on-device (its native
 * library is unavailable on the host JVM). Verifies a fresh identity has a valid
 * in-range registration id, serializes/parses round-trip, and yields stable
 * public identity bytes.
 */
@RunWith(AndroidJUnit4::class)
class LibsignalIdentityFactoryTest {

    private val factory = LibsignalIdentityFactory()

    @Test
    fun createProducesInRangeRegistrationIdAndParsablePair() {
        val identity = (factory.create() as SignalIdentityResult.Success).identity
        // libsignal's standard (non-extended) registration id range is [1, 16380].
        assertTrue("regId=${identity.registrationId}", identity.registrationId in 1..16380)
        assertTrue(identity.serializedKeyPair.isNotEmpty())
        assertTrue(identity.publicIdentityBytes.isNotEmpty())
    }

    @Test
    fun parseRecoversSamePublicIdentityBytes() {
        val created = (factory.create() as SignalIdentityResult.Success).identity
        val parsed = (
            factory.parse(created.serializedKeyPair, created.registrationId) as SignalIdentityResult.Success
            ).identity
        assertArrayEquals(created.publicIdentityBytes, parsed.publicIdentityBytes)
        assertArrayEquals(created.serializedKeyPair, parsed.serializedKeyPair)
    }

    @Test
    fun twoCreatesDifferInKeyMaterial() {
        val a = (factory.create() as SignalIdentityResult.Success).identity
        val b = (factory.create() as SignalIdentityResult.Success).identity
        assertNotEquals(
            a.serializedKeyPair.toList(),
            b.serializedKeyPair.toList(),
        )
    }

    @Test
    fun parseOfGarbageFailsClosed() {
        val result = factory.parse(ByteArray(4) { 0 }, 1)
        assertTrue(result is SignalIdentityResult.Failure)
    }
}
