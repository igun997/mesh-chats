package com.meshchats.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException

/**
 * Verifies [classifyKeystoreUnwrapError] on the host JVM. A real
 * `KeyPermanentlyInvalidatedException` cannot be raised off-device, so this uses
 * a stand-in whose class name ends in `KeyPermanentlyInvalidatedException` —
 * exactly what the classifier matches — to prove the KEY_LOST path, including
 * when the invalidation is buried in the cause chain.
 */
class KeystoreUnwrapClassifierTest {

    /** Mirrors the simple name the production classifier matches by. */
    private class KeyPermanentlyInvalidatedException(message: String) : GeneralSecurityException(message)

    @Test
    fun `direct key invalidation classifies as KEY_LOST`() {
        val e = KeyPermanentlyInvalidatedException("credential reset")
        assertEquals(SecretUnwrapError.KEY_LOST, classifyKeystoreUnwrapError(e))
    }

    @Test
    fun `wrapped key invalidation in cause chain classifies as KEY_LOST`() {
        val wrapped = RuntimeException(
            "decrypt failed",
            IllegalStateException(KeyPermanentlyInvalidatedException("invalidated")),
        )
        assertEquals(SecretUnwrapError.KEY_LOST, classifyKeystoreUnwrapError(wrapped))
    }

    @Test
    fun `key invalidation wins over a bad tag in the same chain`() {
        // If both appear, permanent key loss is the more severe, unrecoverable
        // truth and must take precedence.
        val e = KeyPermanentlyInvalidatedException("invalidated").apply {
            initCause(AEADBadTagException("tag"))
        }
        assertEquals(SecretUnwrapError.KEY_LOST, classifyKeystoreUnwrapError(e))
    }

    @Test
    fun `bad tag anywhere in the chain classifies as TAMPERED`() {
        val e = RuntimeException("decrypt failed", AEADBadTagException("bad tag"))
        assertEquals(SecretUnwrapError.TAMPERED, classifyKeystoreUnwrapError(e))
    }

    @Test
    fun `unrelated failure classifies as UNWRAP_FAILED`() {
        val e = RuntimeException("keystore backend hiccup")
        assertEquals(SecretUnwrapError.UNWRAP_FAILED, classifyKeystoreUnwrapError(e))
    }

    @Test
    fun `null classifies as UNWRAP_FAILED`() {
        assertEquals(SecretUnwrapError.UNWRAP_FAILED, classifyKeystoreUnwrapError(null))
    }
}
