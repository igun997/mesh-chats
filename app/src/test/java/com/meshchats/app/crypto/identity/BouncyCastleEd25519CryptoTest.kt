package com.meshchats.app.crypto.identity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Security

/**
 * Exercises the isolated-provider Bouncy Castle Ed25519 implementation on the host
 * JVM, including RFC 8032 fixed vectors. Bouncy Castle runs identically on the
 * host JVM and on Android, so this core crypto is proven here without a device.
 */
class BouncyCastleEd25519CryptoTest {

    private val crypto = BouncyCastleEd25519Crypto()

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** Wraps a 32-byte RFC 8032 seed as a PKCS#8 v1 Ed25519 private key. */
    private fun pkcs8FromSeed(seed: ByteArray): ByteArray {
        require(seed.size == 32)
        val prefix = hex("302e020100300506032b657004220420")
        return prefix + seed
    }

    /** The X.509 SubjectPublicKeyInfo prefix for a 32-byte Ed25519 raw public key. */
    private fun rawPublicFromX509(x509: ByteArray): ByteArray =
        x509.copyOfRange(x509.size - 32, x509.size)

    @Test
    fun doesNotRegisterProviderGlobally() {
        val before = Security.getProviders().map { it.name }.toSet()
        BouncyCastleEd25519Crypto().generate()
        val after = Security.getProviders().map { it.name }.toSet()
        assertEquals("crypto must not mutate the global provider list", before, after)
    }

    @Test
    fun generateProducesUsableKeyPair() {
        val kp = (crypto.generate() as Ed25519GenerateResult.Success).keyPair
        assertEquals(44, kp.publicX509.size) // X.509 SPKI for Ed25519
        assertTrue(kp.privatePkcs8.isNotEmpty())

        val sig = (crypto.sign(kp.privatePkcs8, "hello".toByteArray()) as Ed25519SignResult.Success).signature
        assertEquals(Ed25519Crypto.SIGNATURE_BYTES, sig.size)
        val verify = crypto.verify(kp.publicX509, "hello".toByteArray(), sig)
        assertTrue((verify as Ed25519VerifyResult.Success).valid)
    }

    @Test
    fun derivePublicMatchesGeneratedPublic() {
        val kp = (crypto.generate() as Ed25519GenerateResult.Success).keyPair
        val derived = crypto.derivePublic(kp.privatePkcs8) as Ed25519DeriveResult.Success
        assertArrayEquals(kp.publicX509, derived.publicX509)
    }

    @Test
    fun rfc8032Test1EmptyMessage() {
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val pkcs8 = pkcs8FromSeed(seed)
        val derived = crypto.derivePublic(pkcs8) as Ed25519DeriveResult.Success
        assertEquals(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            hex(rawPublicFromX509(derived.publicX509)),
        )
        val sig = crypto.sign(pkcs8, ByteArray(0)) as Ed25519SignResult.Success
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            hex(sig.signature),
        )
    }

    @Test
    fun rfc8032Test2OneByteMessage() {
        val seed = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val pkcs8 = pkcs8FromSeed(seed)
        val sig = crypto.sign(pkcs8, hex("72")) as Ed25519SignResult.Success
        assertEquals(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            hex(sig.signature),
        )
        // And it verifies against the RFC public key.
        val pub = hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        val x509 = hex("302a300506032b6570032100") + pub
        assertTrue((crypto.verify(x509, hex("72"), sig.signature) as Ed25519VerifyResult.Success).valid)
    }

    @Test
    fun rfc8032Test3TwoByteMessage() {
        val seed = hex("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7")
        val sig = crypto.sign(pkcs8FromSeed(seed), hex("af82")) as Ed25519SignResult.Success
        assertEquals(
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
                "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
            hex(sig.signature),
        )
    }

    @Test
    fun wrongMessageDoesNotVerify() {
        val kp = (crypto.generate() as Ed25519GenerateResult.Success).keyPair
        val sig = (crypto.sign(kp.privatePkcs8, "message-a".toByteArray()) as Ed25519SignResult.Success).signature
        val verify = crypto.verify(kp.publicX509, "message-b".toByteArray(), sig)
        assertFalse((verify as Ed25519VerifyResult.Success).valid)
    }

    @Test
    fun tamperedSignatureDoesNotVerify() {
        val kp = (crypto.generate() as Ed25519GenerateResult.Success).keyPair
        val sig = (crypto.sign(kp.privatePkcs8, "m".toByteArray()) as Ed25519SignResult.Success).signature
        sig[0] = (sig[0].toInt() xor 0xFF).toByte()
        val verify = crypto.verify(kp.publicX509, "m".toByteArray(), sig)
        assertFalse((verify as Ed25519VerifyResult.Success).valid)
    }

    @Test
    fun wrongSizedSignatureIsNonMatchNotFault() {
        val kp = (crypto.generate() as Ed25519GenerateResult.Success).keyPair
        val verify = crypto.verify(kp.publicX509, "m".toByteArray(), ByteArray(10))
        assertFalse((verify as Ed25519VerifyResult.Success).valid)
    }

    @Test
    fun invalidPrivateKeyReportsInvalidKey() {
        val result = crypto.sign(ByteArray(10) { 1 }, "m".toByteArray())
        assertEquals(Ed25519Error.INVALID_KEY, (result as Ed25519SignResult.Failure).error)
    }

    @Test
    fun invalidPublicKeyReportsInvalidKey() {
        val result = crypto.verify(ByteArray(10) { 1 }, "m".toByteArray(), ByteArray(64))
        assertEquals(Ed25519Error.INVALID_KEY, (result as Ed25519VerifyResult.Failure).error)
    }

    @Test
    fun oversizedMessageIsRefused() {
        val kp = (crypto.generate() as Ed25519GenerateResult.Success).keyPair
        val big = ByteArray(Ed25519Crypto.MAX_MESSAGE_BYTES + 1)
        assertEquals(
            Ed25519Error.INVALID_INPUT,
            (crypto.sign(kp.privatePkcs8, big) as Ed25519SignResult.Failure).error,
        )
        assertEquals(
            Ed25519Error.INVALID_INPUT,
            (crypto.verify(kp.publicX509, big, ByteArray(64)) as Ed25519VerifyResult.Failure).error,
        )
    }
}
