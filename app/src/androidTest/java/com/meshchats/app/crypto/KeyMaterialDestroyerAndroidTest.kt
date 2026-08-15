package com.meshchats.app.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/**
 * Exercises the real Android Keystore alias deletion ([AndroidKeystoreSecretWrapper.destroy])
 * and the full key-domain teardown on-device. Each test uses a unique alias and a
 * unique file under `noBackupFilesDir`, cleaned up afterward, so runs never
 * collide or leak Keystore entries. The critical property proven here is that,
 * after destruction, a wrapped secret can no longer be unwrapped: the domain is
 * cryptographically unrecoverable.
 */
@RunWith(AndroidJUnit4::class)
class KeyMaterialDestroyerAndroidTest {

    private lateinit var alias: String
    private lateinit var blobFile: File

    @Before
    fun setUp() {
        alias = "mesh-test-destroy-${UUID.randomUUID()}"
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        blobFile = File(ctx.noBackupFilesDir, "test-destroy-${UUID.randomUUID()}.wrapped")
    }

    @After
    fun tearDown() {
        blobFile.delete()
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        } catch (_: Exception) {
            // best effort
        }
    }

    private fun keystoreContains(): Boolean {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.containsAlias(alias)
    }

    @Test
    fun destroyRemovesTheAliasAndIsConfirmedDeleted() {
        val wrapper = AndroidKeystoreSecretWrapper(alias)
        // Create the alias by wrapping something.
        assertTrue(wrapper.wrap(ByteArray(32) { 7 }, ByteArray(0)) is WrapResult.Success)
        assertTrue(keystoreContains())

        val result = wrapper.destroy()
        assertEquals(WrappingKeyDeleteResult.Deleted, result)
        assertFalse(keystoreContains())
    }

    @Test
    fun destroyOnAbsentAliasIsIdempotentlyDeleted() {
        // Never created: destroying an absent alias converges to Deleted.
        val wrapper = AndroidKeystoreSecretWrapper(alias)
        assertEquals(WrappingKeyDeleteResult.Deleted, wrapper.destroy())
        assertFalse(keystoreContains())
    }

    @Test
    fun afterDestroyWrappedSecretCannotBeUnwrapped() {
        val wrapper = AndroidKeystoreSecretWrapper(alias)
        val secret = ByteArray(32) { it.toByte() }
        val aad = "domain".toByteArray()
        val wrapped = (wrapper.wrap(secret, aad) as WrapResult.Success).wrapped

        // Sanity: it unwraps before destruction.
        assertTrue(wrapper.unwrap(wrapped.nonce, wrapped.ciphertext, aad) is UnwrapResult.Success)

        assertEquals(WrappingKeyDeleteResult.Deleted, wrapper.destroy())

        // After destruction the key is gone: the blob (even if kept on disk) is
        // cryptographically unrecoverable.
        val after = wrapper.unwrap(wrapped.nonce, wrapped.ciphertext, aad)
        assertTrue(after is UnwrapResult.Failure)
        assertEquals(SecretUnwrapError.KEY_LOST, (after as UnwrapResult.Failure).error)
    }

    @Test
    fun keyDomainDestroyRemovesBothAliasAndWrappedBlob() {
        val wrapper = AndroidKeystoreSecretWrapper(alias)
        val secret = ByteArray(32) { 3 }
        val aad = "db".toByteArray()
        val wrapped = (wrapper.wrap(secret, aad) as WrapResult.Success).wrapped
        // Persist a wrapped blob at the domain's file.
        val atomic = AtomicSecretFile(blobFile)
        assertTrue(atomic.write(wrapped.nonce, wrapped.ciphertext) is SecretFileWriteResult.Success)
        assertTrue(blobFile.exists())
        assertTrue(keystoreContains())

        val domain = KeyDomain(destroyer = wrapper, wrappedBlob = blobFile)
        assertTrue("domain must confirm both alias and blob absent", domain.destroy())
        assertFalse(keystoreContains())
        assertFalse(blobFile.exists())
    }

    @Test
    fun keyDomainDestroyIsIdempotentOnRepeat() {
        val wrapper = AndroidKeystoreSecretWrapper(alias)
        val wrapped = (wrapper.wrap(ByteArray(32) { 9 }, ByteArray(0)) as WrapResult.Success).wrapped
        val atomic = AtomicSecretFile(blobFile)
        atomic.write(wrapped.nonce, wrapped.ciphertext)

        val domain = KeyDomain(destroyer = wrapper, wrappedBlob = blobFile)
        assertTrue(domain.destroy())
        // Repeat: already absent, still confirmed destroyed.
        assertTrue(domain.destroy())
    }
}
