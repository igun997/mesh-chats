package com.meshchats.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the production database-close step ([ProductionDatabaseClose]) that the
 * DI wiring feeds into the coordinator. It must:
 * - actually invoke the real close (so the SQLCipher open-helper releases the file
 *   before the wipe deletes it), and
 * - always return [DatabaseCloseOutcome.RESTART_REQUIRED] — even on a clean close —
 *   because the retained raw SQLCipher key cannot be confirmed cleared without a
 *   process restart. This is what caps the real production wipe at
 *   [PanicWipeOutcome.KEYS_DESTROYED_DATA_PARTIAL]; the app CANNOT report COMPLETE.
 */
class ProductionDatabaseCloseTest {

    @Test
    fun invokesCloseExactlyOnceAndRequiresRestart() {
        val calls = AtomicInteger(0)
        val outcome = ProductionDatabaseClose.run { calls.incrementAndGet() }
        assertEquals(1, calls.get())
        assertEquals(DatabaseCloseOutcome.RESTART_REQUIRED, outcome)
    }

    @Test
    fun closeThatThrowsIsBoundedAndStillRequiresRestart() {
        // A failing close must not abort the wipe: the wrapping keys are already
        // gone, so data at rest is unrecoverable; we still require a restart.
        val outcome = ProductionDatabaseClose.run { throw RuntimeException("close blew up") }
        assertEquals(DatabaseCloseOutcome.RESTART_REQUIRED, outcome)
    }

    @Test
    fun realProductionCloseOutcomeCapsWipeAtPartialNeverComplete() {
        // Feed the REAL production close seam into the coordinator with everything
        // else succeeding, and prove the strongest achievable outcome in production
        // is KEYS_DESTROYED_DATA_PARTIAL with processRestartRequired — never COMPLETE.
        val log = mutableListOf<String>()
        val deleter = object : SensitiveFileDeleter {
            override fun deleteConfirmingAbsent(file: java.io.File): Boolean = true
        }
        val closed = AtomicInteger(0)
        val coordinator = DefaultPanicWipeCoordinator(
            databaseKeyDomain = KeyDomain(
                FakeKeyMaterialDestroyer("db", log), java.io.File("db.wrapped"), deleter,
            ),
            identityKeyDomain = KeyDomain(
                FakeKeyMaterialDestroyer("id", log), java.io.File("id.wrapped"), deleter,
            ),
            // Exactly the production wiring: ProductionDatabaseClose around a real close.
            closeDatabase = { ProductionDatabaseClose.run { closed.incrementAndGet() } },
            sensitiveFiles = { emptyList() },
            deleter = deleter,
        )

        val report = coordinator.wipe()
        assertEquals(1, closed.get())
        assertTrue(report.databaseKeyDestroyed)
        assertTrue(report.identityKeyDestroyed)
        assertTrue(report.filesRemoved)
        assertTrue("production close must force a restart", report.processRestartRequired)
        // The whole point: with the real close, production can only reach PARTIAL.
        assertEquals(PanicWipeOutcome.KEYS_DESTROYED_DATA_PARTIAL, report.outcome)
    }
}
