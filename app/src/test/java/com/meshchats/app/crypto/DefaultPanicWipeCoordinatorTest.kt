package com.meshchats.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the app-level panic wipe is KEY-FIRST, HONEST, atomic/idempotent, and
 * fail-closed. The strict-order assertions are the security core: both wrapping
 * key domains must be attacked before any data file is touched, so a crash after
 * the first key destruction still leaves data cryptographically unrecoverable.
 */
class DefaultPanicWipeCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A recording deleter that logs order and can be told which files to fail on. */
    private class RecordingDeleter(
        private val log: MutableList<String>,
        val failFor: MutableSet<String> = mutableSetOf(),
        var throwFor: MutableSet<String> = mutableSetOf(),
    ) : SensitiveFileDeleter {
        override fun deleteConfirmingAbsent(file: File): Boolean {
            log.add("file:${file.name}")
            if (file.name in throwFor) throw RuntimeException("delete blew up: ${file.name}")
            if (file.name in failFor) return false
            if (file.exists()) file.delete()
            return !file.exists()
        }
    }

    private fun newFile(name: String, bytes: ByteArray = byteArrayOf(1)): File =
        File(tmp.root, name).apply { writeBytes(bytes) }

    private fun coordinator(
        log: MutableList<String>,
        dbKey: FakeKeyMaterialDestroyer,
        idKey: FakeKeyMaterialDestroyer,
        dbBlob: File,
        idBlob: File,
        dataFiles: List<File>,
        deleter: RecordingDeleter,
        close: () -> DatabaseCloseOutcome = { DatabaseCloseOutcome.CLOSED_AND_KEY_CLEARED },
    ): DefaultPanicWipeCoordinator = DefaultPanicWipeCoordinator(
        databaseKeyDomain = KeyDomain(dbKey, dbBlob, deleter),
        identityKeyDomain = KeyDomain(idKey, idBlob, deleter),
        closeDatabase = { log.add("close"); close() },
        sensitiveFiles = { dataFiles },
        deleter = deleter,
    )

    @Test
    fun keyDomainsDestroyedBeforeAnyDataFileTouched() {
        val log = mutableListOf<String>()
        val deleter = RecordingDeleter(log)
        val dbBlob = newFile("db-key.wrapped")
        val idBlob = newFile("identity-key.wrapped")
        val data = listOf(newFile("mesh-chats.db"), newFile("prefs.pb"))

        val report = coordinator(
            log,
            FakeKeyMaterialDestroyer("db", log),
            FakeKeyMaterialDestroyer("id", log),
            dbBlob, idBlob, data, deleter,
        ).wipe()

        // Both Keystore aliases must be destroyed before the first DATA file delete.
        // (The wrapped-blob deletes for each domain are part of destroying that
        // domain and legitimately interleave with the alias deletes.)
        val firstDataIdx = log.indexOfFirst { it == "file:mesh-chats.db" || it == "file:prefs.pb" }
        val lastKeyAliasIdx = log.indexOfLast { it == "key:db" || it == "key:id" }
        assertTrue("data file touched before a key alias was destroyed",
            firstDataIdx > lastKeyAliasIdx)
        // The wrapped blobs belong to key domains and must also precede data files.
        val dbBlobIdx = log.indexOf("file:db-key.wrapped")
        val idBlobIdx = log.indexOf("file:identity-key.wrapped")
        assertTrue(dbBlobIdx in 0 until firstDataIdx)
        assertTrue(idBlobIdx in 0 until firstDataIdx)

        assertEquals(PanicWipeOutcome.COMPLETE, report.outcome)
        assertTrue(report.databaseKeyDestroyed)
        assertTrue(report.identityKeyDestroyed)
        assertTrue(report.filesRemoved)
        assertFalse(report.processRestartRequired)
        // db before id domain.
        assertTrue(log.indexOf("key:db") < log.indexOf("key:id"))
    }

    @Test
    fun completeRequiresBothDomainsAndAllFilesAndNoRestart() {
        val log = mutableListOf<String>()
        val deleter = RecordingDeleter(log)
        val report = coordinator(
            log,
            FakeKeyMaterialDestroyer("db", log),
            FakeKeyMaterialDestroyer("id", log),
            newFile("db-key.wrapped"), newFile("identity-key.wrapped"),
            listOf(newFile("mesh-chats.db")), deleter,
        ).wipe()
        assertEquals(PanicWipeOutcome.COMPLETE, report.outcome)
    }

    @Test
    fun keysDestroyedButFileResidueIsPartialNotComplete() {
        val log = mutableListOf<String>()
        val deleter = RecordingDeleter(log, failFor = mutableSetOf("mesh-chats.db"))
        val report = coordinator(
            log,
            FakeKeyMaterialDestroyer("db", log),
            FakeKeyMaterialDestroyer("id", log),
            newFile("db-key.wrapped"), newFile("identity-key.wrapped"),
            listOf(newFile("mesh-chats.db")), deleter,
        ).wipe()
        // Both key domains gone -> data cryptographically unrecoverable, but a file
        // could not be removed: must be PARTIAL, never COMPLETE.
        assertEquals(PanicWipeOutcome.KEYS_DESTROYED_DATA_PARTIAL, report.outcome)
        assertTrue(report.databaseKeyDestroyed)
        assertTrue(report.identityKeyDestroyed)
        assertFalse(report.filesRemoved)
    }

    @Test
    fun keysDestroyedButRestartRequiredIsPartialNotComplete() {
        val log = mutableListOf<String>()
        val deleter = RecordingDeleter(log)
        val report = coordinator(
            log,
            FakeKeyMaterialDestroyer("db", log),
            FakeKeyMaterialDestroyer("id", log),
            newFile("db-key.wrapped"), newFile("identity-key.wrapped"),
            listOf(newFile("mesh-chats.db")), deleter,
            close = { DatabaseCloseOutcome.RESTART_REQUIRED },
        ).wipe()
        assertEquals(PanicWipeOutcome.KEYS_DESTROYED_DATA_PARTIAL, report.outcome)
        assertTrue(report.processRestartRequired)
    }

    @Test
    fun failedWhenDatabaseKeyDomainCannotBeConfirmedDestroyed() {
        val log = mutableListOf<String>()
        val deleter = RecordingDeleter(log)
        val report = coordinator(
            log,
            FakeKeyMaterialDestroyer("db", log, fail = true),
            FakeKeyMaterialDestroyer("id", log),
            newFile("db-key.wrapped"), newFile("identity-key.wrapped"),
            listOf(newFile("mesh-chats.db")), deleter,
        ).wipe()
        assertEquals(PanicWipeOutcome.FAILED, report.outcome)
        assertFalse(report.databaseKeyDestroyed)
    }

    @Test
    fun failedWhenIdentityKeyDomainCannotBeConfirmedDestroyed() {
        val log = mutableListOf<String>()
        val deleter = RecordingDeleter(log)
        val report = coordinator(
            log,
            FakeKeyMaterialDestroyer("db", log),
            FakeKeyMaterialDestroyer("id", log, fail = true),
            newFile("db-key.wrapped"), newFile("identity-key.wrapped"),
            listOf(newFile("mesh-chats.db")), deleter,
        ).wipe()
        assertEquals(PanicWipeOutcome.FAILED, report.outcome)
        assertFalse(report.identityKeyDestroyed)
    }

    @Test
    fun exceptionsDuringKeyDestructionAreBoundedAndFailClosed() {
        val log = mutableListOf<String>()
        val deleter = RecordingDeleter(log)
        val report = coordinator(
            log,
            FakeKeyMaterialDestroyer("db", log, throwOnDestroy = true),
            FakeKeyMaterialDestroyer("id", log),
            newFile("db-key.wrapped"), newFile("identity-key.wrapped"),
            listOf(newFile("mesh-chats.db")), deleter,
        ).wipe()
        // A throwing destroyer must not abort the wipe; it fails closed on that domain
        // but the identity domain and the data cleanup still run.
        assertEquals(PanicWipeOutcome.FAILED, report.outcome)
        assertFalse(report.databaseKeyDestroyed)
        assertTrue(report.identityKeyDestroyed)
        assertTrue(log.contains("key:id"))
    }

    @Test
    fun exceptionsDuringFileDeleteAreBoundedAndDowngradeToPartial() {
        val log = mutableListOf<String>()
        val deleter = RecordingDeleter(log, throwFor = mutableSetOf("mesh-chats.db"))
        val report = coordinator(
            log,
            FakeKeyMaterialDestroyer("db", log),
            FakeKeyMaterialDestroyer("id", log),
            newFile("db-key.wrapped"), newFile("identity-key.wrapped"),
            listOf(newFile("mesh-chats.db")), deleter,
        ).wipe()
        assertEquals(PanicWipeOutcome.KEYS_DESTROYED_DATA_PARTIAL, report.outcome)
        assertFalse(report.filesRemoved)
    }

    @Test
    fun repeatedWipeConvergesAndStillReportsComplete() {
        val log = mutableListOf<String>()
        val deleter = RecordingDeleter(log)
        val dbBlob = newFile("db-key.wrapped")
        val idBlob = newFile("identity-key.wrapped")
        val data = listOf(newFile("mesh-chats.db"))
        val coord = coordinator(
            log,
            FakeKeyMaterialDestroyer("db", log),
            FakeKeyMaterialDestroyer("id", log),
            dbBlob, idBlob, data, deleter,
        )
        assertEquals(PanicWipeOutcome.COMPLETE, coord.wipe().outcome)
        // Files/aliases already gone; the second wipe is a no-op that still succeeds
        // (idempotent), because absence counts as destroyed.
        assertEquals(PanicWipeOutcome.COMPLETE, coord.wipe().outcome)
    }

    @Test
    fun retryAfterCrashFollowingFirstKeyCompletesTheWipe() {
        // Simulate a crash after only the DB key domain was destroyed on attempt 1
        // (identity domain "fails" that run), then a successful retry on attempt 2.
        val log = mutableListOf<String>()
        val deleter = RecordingDeleter(log)
        val dbBlob = newFile("db-key.wrapped")
        val idBlob = newFile("identity-key.wrapped")
        val data = listOf(newFile("mesh-chats.db"))
        val idKey = FakeKeyMaterialDestroyer("id", log, fail = true)
        val coord = coordinator(
            log,
            FakeKeyMaterialDestroyer("db", log),
            idKey,
            dbBlob, idBlob, data, deleter,
        )
        val first = coord.wipe()
        assertEquals(PanicWipeOutcome.FAILED, first.outcome)
        assertTrue(first.databaseKeyDestroyed)
        assertFalse(first.identityKeyDestroyed)

        // Retry: the identity Keystore alias now deletes cleanly. DB domain is
        // already absent (idempotent) and still counts as destroyed.
        idKey.fail = false
        val second = coord.wipe()
        assertEquals(PanicWipeOutcome.COMPLETE, second.outcome)
        assertTrue(second.databaseKeyDestroyed)
        assertTrue(second.identityKeyDestroyed)
    }

    @Test
    fun keyDomainRequiresBothAliasAndBlobConfirmedAbsent() {
        // Alias deletes fine but the wrapped blob cannot be removed: the domain must
        // NOT be treated as destroyed (defense in depth requires both confirmed).
        val log = mutableListOf<String>()
        val deleter = RecordingDeleter(log, failFor = mutableSetOf("db-key.wrapped"))
        val report = coordinator(
            log,
            FakeKeyMaterialDestroyer("db", log),
            FakeKeyMaterialDestroyer("id", log),
            newFile("db-key.wrapped"), newFile("identity-key.wrapped"),
            listOf(newFile("mesh-chats.db")), deleter,
        ).wipe()
        assertEquals(PanicWipeOutcome.FAILED, report.outcome)
        assertFalse(report.databaseKeyDestroyed)
    }

    @Test
    fun neverRegeneratesKeysDuringWipe() {
        // Each destroyer is called exactly once per wipe; the coordinator must not
        // re-invoke a "getOrCreate" style path that could regenerate a key.
        val log = mutableListOf<String>()
        val deleter = RecordingDeleter(log)
        val dbKey = FakeKeyMaterialDestroyer("db", log)
        val idKey = FakeKeyMaterialDestroyer("id", log)
        coordinator(
            log, dbKey, idKey,
            newFile("db-key.wrapped"), newFile("identity-key.wrapped"),
            listOf(newFile("mesh-chats.db")), deleter,
        ).wipe()
        assertEquals(1, dbKey.destroyCount)
        assertEquals(1, idKey.destroyCount)
    }

    @Test
    fun databaseCloseHappensAfterBothKeyAttemptsAndBeforeFirstDataDelete() {
        // H1 ordering seam: the close event must be recorded AFTER both Keystore
        // alias destructions and BEFORE the first data-file delete, so the
        // open-helper releases the file before we unlink it and a crash mid-close
        // still leaves data unrecoverable.
        val log = mutableListOf<String>()
        val deleter = RecordingDeleter(log)
        val dbBlob = newFile("db-key.wrapped")
        val idBlob = newFile("identity-key.wrapped")
        val data = listOf(newFile("mesh-chats.db"), newFile("prefs.pb"))

        coordinator(
            log,
            FakeKeyMaterialDestroyer("db", log),
            FakeKeyMaterialDestroyer("id", log),
            dbBlob, idBlob, data, deleter,
            close = { DatabaseCloseOutcome.RESTART_REQUIRED },
        ).wipe()

        val closeIdx = log.indexOf("close")
        val lastKeyAliasIdx = log.indexOfLast { it == "key:db" || it == "key:id" }
        val firstDataIdx = log.indexOfFirst { it == "file:mesh-chats.db" || it == "file:prefs.pb" }
        assertTrue("close must run", closeIdx >= 0)
        assertTrue("close must follow both key alias destructions", closeIdx > lastKeyAliasIdx)
        assertTrue("close must precede the first data-file delete", closeIdx < firstDataIdx)
    }

    @Test
    fun concurrentWipeCallsAreSerializedAndBothSucceed() {
        // L2: two concurrent wipe() calls must serialize under the coordinator's
        // monitor — never interleave — and both must observe a consistent result.
        val log = CopyOnWriteArrayList<String>()
        val active = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val overlaps = AtomicInteger(0)

        // A deleter that detects any overlap of two wipes inside the critical section.
        val deleter = object : SensitiveFileDeleter {
            override fun deleteConfirmingAbsent(file: File): Boolean {
                val now = active.incrementAndGet()
                maxConcurrent.updateAndGet { maxOf(it, now) }
                if (now > 1) overlaps.incrementAndGet()
                try {
                    Thread.sleep(2)
                    log.add("file:${file.name}")
                    if (file.exists()) file.delete()
                    return !file.exists()
                } finally {
                    active.decrementAndGet()
                }
            }
        }

        val dbBlob = newFile("db-key.wrapped")
        val idBlob = newFile("identity-key.wrapped")
        val data = listOf(newFile("mesh-chats.db"), newFile("prefs.pb"))
        val coord = DefaultPanicWipeCoordinator(
            databaseKeyDomain = KeyDomain(FakeKeyMaterialDestroyer("db", mutableListOf()), dbBlob, deleter),
            identityKeyDomain = KeyDomain(FakeKeyMaterialDestroyer("id", mutableListOf()), idBlob, deleter),
            closeDatabase = { DatabaseCloseOutcome.CLOSED_AND_KEY_CLEARED },
            sensitiveFiles = { data },
            deleter = deleter,
        )

        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val results = CopyOnWriteArrayList<PanicWipeReport>()
        repeat(2) {
            pool.submit {
                start.await()
                results.add(coord.wipe())
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        assertEquals(0, overlaps.get())
        assertEquals(1, maxConcurrent.get())
        assertEquals(2, results.size)
        // First to run wipes; second converges (files already gone). Both succeed.
        results.forEach { assertEquals(PanicWipeOutcome.COMPLETE, it.outcome) }
    }
}
