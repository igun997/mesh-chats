package com.meshchats.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DatabaseKeyProviderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun provider(
        wrapper: SecretWrapper,
        file: File = File(temp.root, "db.key"),
        random: SecureRandom = SecureRandom(),
    ) = DatabaseKeyProvider(wrapper, AtomicSecretFile(file), random)

    @Test
    fun `generates a 32-byte key on first call`() {
        val result = provider(FakeSecretWrapper()).getOrCreateKey()
        assertTrue(result is DatabaseKeyResult.Success)
        assertEquals(32, (result as DatabaseKeyResult.Success).key.size)
    }

    @Test
    fun `persists key so a new provider instance reopens the same key`() {
        val wrapper = FakeSecretWrapper()
        val file = File(temp.root, "db.key")

        val first = provider(wrapper, file).getOrCreateKey() as DatabaseKeyResult.Success
        // A fresh provider instance simulates a new process; same wrapper (same Keystore key).
        val second = provider(wrapper, file).getOrCreateKey() as DatabaseKeyResult.Success

        assertArrayEquals(first.key, second.key)
    }

    @Test
    fun `returns defensive copies that do not alias`() {
        val provider = provider(FakeSecretWrapper())
        val a = (provider.getOrCreateKey() as DatabaseKeyResult.Success).key
        val b = (provider.getOrCreateKey() as DatabaseKeyResult.Success).key
        assertArrayEquals(a, b)
        a.fill(0)
        // Mutating the first returned array must not affect a later read.
        val c = (provider.getOrCreateKey() as DatabaseKeyResult.Success).key
        assertFalse(c.all { it == 0.toByte() })
    }

    @Test
    fun `stored file is not the plaintext key`() {
        val random = SecureRandom()
        val file = File(temp.root, "db.key")
        val key = (provider(FakeSecretWrapper(), file, random).getOrCreateKey() as DatabaseKeyResult.Success).key
        val onDisk = file.readBytes()
        // The plaintext key must not appear verbatim anywhere in the stored blob.
        assertFalse(containsSubarray(onDisk, key))
    }

    @Test
    fun `key loss fails closed and never regenerates over existing file`() {
        val wrapper = FakeSecretWrapper()
        val file = File(temp.root, "db.key")
        provider(wrapper, file).getOrCreateKey() as DatabaseKeyResult.Success

        wrapper.keyLost = true
        val result = provider(wrapper, file).getOrCreateKey()
        assertEquals(DatabaseKeyError.KEY_LOST, (result as DatabaseKeyResult.Failure).error)
        // File must be untouched — no silent regeneration.
        assertTrue(file.exists())
    }

    @Test
    fun `tampered ciphertext fails closed as tampered`() {
        val wrapper = FakeSecretWrapper()
        val file = File(temp.root, "db.key")
        provider(wrapper, file).getOrCreateKey() as DatabaseKeyResult.Success

        // Flip a byte deep in the record (past the header, inside the ciphertext).
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        file.writeBytes(bytes)

        val result = provider(wrapper, file).getOrCreateKey()
        assertEquals(DatabaseKeyError.TAMPERED, (result as DatabaseKeyResult.Failure).error)
    }

    @Test
    fun `structurally corrupt file fails closed as tampered`() {
        val wrapper = FakeSecretWrapper()
        val file = File(temp.root, "db.key")
        provider(wrapper, file).getOrCreateKey() as DatabaseKeyResult.Success

        // Corrupt the magic so the codec rejects it structurally.
        val bytes = file.readBytes()
        bytes[0] = 0x00
        file.writeBytes(bytes)

        val result = provider(wrapper, file).getOrCreateKey()
        assertEquals(DatabaseKeyError.TAMPERED, (result as DatabaseKeyResult.Failure).error)
    }

    @Test
    fun `wrapper unavailable on first run reports wrapper unavailable`() {
        val wrapper = FakeSecretWrapper().apply { wrapUnavailable = true }
        val result = provider(wrapper).getOrCreateKey()
        assertEquals(DatabaseKeyError.WRAPPER_UNAVAILABLE, (result as DatabaseKeyResult.Failure).error)
    }

    @Test
    fun `concurrent first-run callers generate exactly one key`() {
        val wrapper = FakeSecretWrapper()
        val file = File(temp.root, "db.key")
        val provider = provider(wrapper, file)

        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val tasks = List(threads) {
                Callable { provider.getOrCreateKey() }
            }
            val results = pool.invokeAll(tasks, 30, TimeUnit.SECONDS).map { it.get() }
            val keys = results.map { (it as DatabaseKeyResult.Success).key }
            // Every thread must observe the identical single generated key.
            val reference = keys.first()
            keys.forEach { assertArrayEquals(reference, it) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `distinct wrappers produce distinct keys`() {
        val a = (provider(FakeSecretWrapper(), File(temp.root, "a.key")).getOrCreateKey() as DatabaseKeyResult.Success).key
        val b = (provider(FakeSecretWrapper(), File(temp.root, "b.key")).getOrCreateKey() as DatabaseKeyResult.Success).key
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `two provider instances racing first-run produce one persisted key`() {
        // Distinct provider instances have independent in-object locks, so only the
        // cross-process file lock in AtomicSecretFile can serialize them. A shared
        // wrapper models one Keystore key; a shared file path models one process's
        // storage. Both instances must converge on a single generated, persisted key.
        val wrapper = FakeSecretWrapper()
        val file = File(temp.root, "db.key")

        val p1 = DatabaseKeyProvider(wrapper, AtomicSecretFile(file), SecureRandom())
        val p2 = DatabaseKeyProvider(wrapper, AtomicSecretFile(file), SecureRandom())

        val start = java.util.concurrent.CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val f1 = pool.submit(Callable { start.await(); p1.getOrCreateKey() })
            val f2 = pool.submit(Callable { start.await(); p2.getOrCreateKey() })
            start.countDown()
            val r1 = f1.get(30, TimeUnit.SECONDS) as DatabaseKeyResult.Success
            val r2 = f2.get(30, TimeUnit.SECONDS) as DatabaseKeyResult.Success
            assertArrayEquals(r1.key, r2.key)

            // Exactly one persisted secret file (plus its .lock sibling), never two.
            val secrets = temp.root.listFiles()?.filter { it.name == "db.key" } ?: emptyList()
            assertEquals(1, secrets.size)

            // A fresh instance reopens the identical key.
            val reopened = DatabaseKeyProvider(wrapper, AtomicSecretFile(file), SecureRandom())
                .getOrCreateKey() as DatabaseKeyResult.Success
            assertArrayEquals(r1.key, reopened.key)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun containsSubarray(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
