package com.meshchats.app.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/**
 * Exercises the real Android Keystore AES-256-GCM wrapper and the full
 * DatabaseKeyProvider stack on-device. Each test uses a unique Keystore alias and
 * a unique file under the app's noBackupFilesDir, both cleaned up afterward, so
 * runs never collide or leak Keystore entries.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretWrapperTest {

    private lateinit var alias: String
    private lateinit var keyFile: File

    @Before
    fun setUp() {
        alias = "mesh-test-${UUID.randomUUID()}"
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        keyFile = File(ctx.noBackupFilesDir, "test-${UUID.randomUUID()}.key")
    }

    @After
    fun tearDown() {
        keyFile.delete()
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        } catch (_: Exception) {
            // best effort
        }
    }

    @Test
    fun wrapThenUnwrapRoundTripsPlaintext() {
        val wrapper = AndroidKeystoreSecretWrapper(alias)
        val secret = ByteArray(32) { it.toByte() }
        val aad = "mesh-chats/db-key/v1".toByteArray()

        val wrapped = wrapper.wrap(secret, aad)
        assertTrue(wrapped is WrapResult.Success)
        val w = (wrapped as WrapResult.Success).wrapped

        val unwrapped = wrapper.unwrap(w.nonce, w.ciphertext, aad)
        assertTrue(unwrapped is UnwrapResult.Success)
        assertArrayEquals(secret, (unwrapped as UnwrapResult.Success).plaintext)
    }

    @Test
    fun wrappedCiphertextIsNotPlaintext() {
        val wrapper = AndroidKeystoreSecretWrapper(alias)
        val secret = ByteArray(32) { 0x55 }
        val w = (wrapper.wrap(secret, ByteArray(0)) as WrapResult.Success).wrapped
        assertFalse(w.ciphertext.contentEquals(secret))
    }

    @Test
    fun wrongAssociatedDataFailsAsTampered() {
        val wrapper = AndroidKeystoreSecretWrapper(alias)
        val secret = ByteArray(32) { it.toByte() }
        val w = (wrapper.wrap(secret, "domain-a".toByteArray()) as WrapResult.Success).wrapped

        val result = wrapper.unwrap(w.nonce, w.ciphertext, "domain-b".toByteArray())
        assertEquals(SecretUnwrapError.TAMPERED, (result as UnwrapResult.Failure).error)
    }

    @Test
    fun tamperedCiphertextFailsAsTampered() {
        val wrapper = AndroidKeystoreSecretWrapper(alias)
        val secret = ByteArray(32) { it.toByte() }
        val w = (wrapper.wrap(secret, ByteArray(0)) as WrapResult.Success).wrapped

        val tampered = w.ciphertext.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()

        val result = wrapper.unwrap(w.nonce, tampered, ByteArray(0))
        assertEquals(SecretUnwrapError.TAMPERED, (result as UnwrapResult.Failure).error)
    }

    @Test
    fun unwrapWithNoKeyReportsKeyLost() {
        // Never created a key under this alias, so the key is "lost" from unwrap's view.
        val wrapper = AndroidKeystoreSecretWrapper(alias)
        val result = wrapper.unwrap(ByteArray(12), ByteArray(48), ByteArray(0))
        assertEquals(SecretUnwrapError.KEY_LOST, (result as UnwrapResult.Failure).error)
    }

    @Test
    fun databaseKeyProviderCreatesOnceAndReopensAcrossInstances() {
        val file = AtomicSecretFile(keyFile)
        val first = DatabaseKeyProvider(AndroidKeystoreSecretWrapper(alias), file).getOrCreateKey()
        assertTrue(first is DatabaseKeyResult.Success)
        val firstKey = (first as DatabaseKeyResult.Success).key
        assertEquals(32, firstKey.size)

        // New provider + new wrapper handle over the same alias/file = new process.
        val second = DatabaseKeyProvider(AndroidKeystoreSecretWrapper(alias), AtomicSecretFile(keyFile)).getOrCreateKey()
        assertArrayEquals(firstKey, (second as DatabaseKeyResult.Success).key)
    }

    @Test
    fun databaseKeyProviderReportsTamperOnCorruptedFile() {
        val file = AtomicSecretFile(keyFile)
        DatabaseKeyProvider(AndroidKeystoreSecretWrapper(alias), file).getOrCreateKey() as DatabaseKeyResult.Success

        val bytes = keyFile.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        keyFile.writeBytes(bytes)

        val result = DatabaseKeyProvider(AndroidKeystoreSecretWrapper(alias), AtomicSecretFile(keyFile)).getOrCreateKey()
        assertEquals(DatabaseKeyError.TAMPERED, (result as DatabaseKeyResult.Failure).error)
        // The file is preserved — no silent regeneration.
        assertTrue(keyFile.exists())
    }

    @Test
    fun storedFileLivesUnderNoBackupDir() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        DatabaseKeyProvider(AndroidKeystoreSecretWrapper(alias), AtomicSecretFile(keyFile)).getOrCreateKey()
        assertNotNull(keyFile.parentFile)
        assertEquals(ctx.noBackupFilesDir.canonicalPath, keyFile.parentFile!!.canonicalPath)
        assertTrue(keyFile.exists())
    }
}
