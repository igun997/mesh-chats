package com.meshchats.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AtomicSecretFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun nonce(): ByteArray = ByteArray(12) { it.toByte() }
    private fun ciphertext(): ByteArray = ByteArray(48) { (0x40 + it).toByte() }

    @Test
    fun `write then read round-trips`() {
        val file = AtomicSecretFile(File(temp.root, "s.bin"))
        assertTrue(file.write(nonce(), ciphertext()) is SecretFileWriteResult.Success)

        val read = file.read()
        assertTrue(read is SecretFileReadResult.Success)
        val success = read as SecretFileReadResult.Success
        assertArrayEquals(nonce(), success.nonce)
        assertArrayEquals(ciphertext(), success.ciphertext)
    }

    @Test
    fun `read of missing file reports not found`() {
        val file = AtomicSecretFile(File(temp.root, "missing.bin"))
        val read = file.read()
        assertEquals(SecretFileReadError.NOT_FOUND, (read as SecretFileReadResult.Failure).error)
    }

    @Test
    fun `read of corrupt file reports corrupt`() {
        val target = File(temp.root, "s.bin")
        target.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val read = AtomicSecretFile(target).read()
        assertEquals(SecretFileReadError.CORRUPT, (read as SecretFileReadResult.Failure).error)
    }

    @Test
    fun `read of tampered record reports corrupt when structurally invalid`() {
        val target = File(temp.root, "s.bin")
        val file = AtomicSecretFile(target)
        file.write(nonce(), ciphertext())
        // Corrupt the magic → structural rejection.
        val bytes = target.readBytes()
        bytes[1] = 0x00
        target.writeBytes(bytes)
        val read = file.read()
        assertEquals(SecretFileReadError.CORRUPT, (read as SecretFileReadResult.Failure).error)
    }

    @Test
    fun `write is atomic and leaves no temp files behind`() {
        val target = File(temp.root, "s.bin")
        AtomicSecretFile(target).write(nonce(), ciphertext())
        val stray = temp.root.listFiles()?.filter { it.name.contains(".tmp-") } ?: emptyList()
        assertTrue("temp files must be cleaned up, found $stray", stray.isEmpty())
    }

    @Test
    fun `overwrite replaces prior contents`() {
        val target = File(temp.root, "s.bin")
        val file = AtomicSecretFile(target)
        file.write(nonce(), ciphertext())

        val newCt = ByteArray(64) { (0x11).toByte() }
        file.write(nonce(), newCt)

        val read = file.read() as SecretFileReadResult.Success
        assertArrayEquals(newCt, read.ciphertext)
    }

    @Test
    fun `symlink target is refused on read`() {
        val real = File(temp.root, "real.bin")
        AtomicSecretFile(real).write(nonce(), ciphertext())

        val link = File(temp.root, "link.bin")
        try {
            Files.createSymbolicLink(link.toPath(), real.toPath())
        } catch (_: Exception) {
            // Filesystem without symlink support; skip.
            return
        }
        val read = AtomicSecretFile(link).read()
        assertEquals(SecretFileReadError.UNSAFE_PATH, (read as SecretFileReadResult.Failure).error)
    }

    @Test
    fun `symlink target is refused on write`() {
        val real = File(temp.root, "real.bin")
        AtomicSecretFile(real).write(nonce(), ciphertext())

        val link = File(temp.root, "link.bin")
        try {
            Files.createSymbolicLink(link.toPath(), real.toPath())
        } catch (_: Exception) {
            return
        }
        val write = AtomicSecretFile(link).write(nonce(), ciphertext())
        assertEquals(SecretFileWriteError.UNSAFE_PATH, (write as SecretFileWriteResult.Failure).error)
    }

    @Test
    fun `path through a symlinked directory is refused`() {
        val realDir = File(temp.root, "realdir").apply { mkdirs() }
        val linkDir = File(temp.root, "linkdir")
        try {
            Files.createSymbolicLink(linkDir.toPath(), realDir.toPath())
        } catch (_: Exception) {
            return
        }
        val target = File(linkDir, "s.bin")
        val write = AtomicSecretFile(target).write(nonce(), ciphertext())
        assertEquals(SecretFileWriteError.UNSAFE_PATH, (write as SecretFileWriteResult.Failure).error)
    }

    @Test
    fun `delete removes the file`() {
        val target = File(temp.root, "s.bin")
        val file = AtomicSecretFile(target)
        file.write(nonce(), ciphertext())
        assertTrue(file.exists())
        assertTrue(file.delete())
        assertFalse(file.exists())
    }

    @Test
    fun `write creates missing parent directory`() {
        val nested = File(temp.root, "a/b/c/s.bin")
        val write = AtomicSecretFile(nested).write(nonce(), ciphertext())
        assertTrue(write is SecretFileWriteResult.Success)
        assertTrue(nested.exists())
    }
    @Test
    fun `write fsyncs the containing directory after the move`() {
        val recorder = RecordingDirectorySync()
        val target = File(temp.root, "s.bin")
        val write = AtomicSecretFile(target, directorySync = recorder).write(nonce(), ciphertext())
        assertTrue(write is SecretFileWriteResult.Success)
        assertEquals(listOf(target.parentFile), recorder.synced)
    }

    @Test
    fun `failed atomic move preserves old contents and cleans up temp`() {
        val target = File(temp.root, "s.bin")
        // Establish an existing durable record with the ORIGINAL ciphertext.
        val original = ciphertext()
        assertTrue(
            AtomicSecretFile(target).write(nonce(), original) is SecretFileWriteResult.Success,
        )

        // A mover that always fails atomically, simulating a mid-write crash of the
        // rename. It must NOT delete the live target first.
        val failingMover = AtomicMover { _, _ -> throw IOException("injected move failure") }
        val newCt = ByteArray(48) { 0x7E }
        val write = AtomicSecretFile(target, mover = failingMover).write(nonce(), newCt)
        assertEquals(SecretFileWriteError.IO_FAILED, (write as SecretFileWriteResult.Failure).error)

        // Old bytes must still be readable and unchanged.
        val read = AtomicSecretFile(target).read()
        assertArrayEquals(original, (read as SecretFileReadResult.Success).ciphertext)

        // The temp file must have been cleaned up; only the target remains.
        val stray = temp.root.listFiles()?.filter { it.name.contains(".tmp-") } ?: emptyList()
        assertTrue("temp files must be cleaned up, found $stray", stray.isEmpty())
    }

    @Test
    fun `invalid encode input is a precondition failure that never touches disk`() {
        val target = File(temp.root, "s.bin")
        // Empty ciphertext is rejected by the codec before any I/O.
        val write = AtomicSecretFile(target).write(nonce(), ByteArray(0))
        assertEquals(SecretFileWriteError.ENCODE_INVALID, (write as SecretFileWriteResult.Failure).error)
        assertFalse("no file should be created on encode failure", target.exists())
    }

    @Test
    fun `overwrite is durable and readable`() {
        val target = File(temp.root, "s.bin")
        val file = AtomicSecretFile(target)
        file.write(nonce(), ciphertext())
        val newCt = ByteArray(64) { 0x22 }
        assertTrue(file.write(nonce(), newCt) is SecretFileWriteResult.Success)
        assertArrayEquals(newCt, (file.read() as SecretFileReadResult.Success).ciphertext)
    }

    @Test
    fun `withCreationLock runs the block exactly once and returns its value`() {
        val file = AtomicSecretFile(File(temp.root, "s.bin"))
        val result = file.withCreationLock { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `concurrent withCreationLock on two instances serialize`() {
        val path = File(temp.root, "s.bin")
        val a = AtomicSecretFile(path)
        val b = AtomicSecretFile(path)
        val active = java.util.concurrent.atomic.AtomicInteger(0)
        val maxObserved = java.util.concurrent.atomic.AtomicInteger(0)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(a, b).map { instance ->
                Callable {
                    start.await()
                    instance.withCreationLock {
                        val now = active.incrementAndGet()
                        maxObserved.updateAndGet { m -> maxOf(m, now) }
                        Thread.sleep(50)
                        active.decrementAndGet()
                        Unit
                    }
                }
            }
            val futures = tasks.map { pool.submit(it) }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
            assertEquals("critical sections must not overlap", 1, maxObserved.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `creation lock file is never left as the secret and does not block reads`() {
        val target = File(temp.root, "s.bin")
        val file = AtomicSecretFile(target)
        file.withCreationLock {
            file.write(nonce(), ciphertext())
        }
        // The .lock sibling exists but is distinct from the secret target.
        val lock = File(temp.root, "s.bin.lock")
        assertTrue(lock.exists())
        assertTrue(target.exists())
        assertArrayEquals(ciphertext(), (file.read() as SecretFileReadResult.Success).ciphertext)
    }
}
