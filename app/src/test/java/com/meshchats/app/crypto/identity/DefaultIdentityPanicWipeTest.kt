package com.meshchats.app.crypto.identity

import com.meshchats.app.crypto.AtomicSecretFile
import com.meshchats.app.crypto.RecordingDirectorySync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verifies the panic wipe deletes the wrapped identity secret first (the
 * irreversible step) and reports a bounded, HONEST result. Ordering matters: the
 * key file must be gone even if the derived-state clear fails. Honesty matters:
 * with no derived-state cleanup injected the wipe must NOT claim [WIPED], because
 * derived state was not actually cleared.
 */
class DefaultIdentityPanicWipeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fileWith(bytes: ByteArray): Pair<File, AtomicSecretFile> {
        val f = File(tmp.newFolder("nobackup"), "identity.wrapped")
        f.writeBytes(bytes)
        return f to AtomicSecretFile(f, directorySync = RecordingDirectorySync())
    }

    @Test
    fun deletesKeyFileButReportsPartialWithoutInjectedCleanup() {
        val (raw, atomic) = fileWith(ByteArray(64) { 1 })
        // No derived-state cleanup injected: the key is gone, but we cannot claim
        // derived state was cleared, so the honest result is PARTIAL, not WIPED.
        val result = DefaultIdentityPanicWipe(atomic).wipe()
        assertEquals(PanicWipeResult.PARTIAL, result)
        assertFalse(raw.exists())
    }

    @Test
    fun reportsWipedOnlyWhenInjectedCleanupSucceeds() {
        val (raw, atomic) = fileWith(ByteArray(64) { 1 })
        val result = DefaultIdentityPanicWipe(atomic, clearDerivedState = { true }).wipe()
        assertEquals(PanicWipeResult.WIPED, result)
        assertFalse(raw.exists())
    }

    @Test
    fun keyDeletedEvenWhenDerivedClearFails() {
        val (raw, atomic) = fileWith(ByteArray(64) { 2 })
        var derivedRan = false
        val result = DefaultIdentityPanicWipe(atomic, clearDerivedState = {
            derivedRan = true
            false // derived-state clear failed
        }).wipe()
        // Key file gone regardless (key-first), but result flags the partial cleanup.
        assertFalse(raw.exists())
        assertTrue(derivedRan)
        assertEquals(PanicWipeResult.PARTIAL, result)
    }

    @Test
    fun keyDeletedEvenWhenDerivedClearThrows() {
        val (raw, atomic) = fileWith(ByteArray(64) { 5 })
        val result = DefaultIdentityPanicWipe(atomic, clearDerivedState = {
            throw RuntimeException("cleanup blew up")
        }).wipe()
        assertFalse(raw.exists())
        assertEquals(PanicWipeResult.PARTIAL, result)
    }

    @Test
    fun keyDeletedBeforeDerivedStateRuns() {
        val (raw, atomic) = fileWith(ByteArray(64) { 3 })
        var keyGoneWhenDerivedRan = false
        DefaultIdentityPanicWipe(atomic, clearDerivedState = {
            keyGoneWhenDerivedRan = !raw.exists()
            true
        }).wipe()
        assertTrue("key file must be deleted before derived-state clear runs", keyGoneWhenDerivedRan)
    }

    @Test
    fun absentFileWithoutCleanupIsStillPartialNotFalseWiped() {
        val f = File(tmp.newFolder("nobackup"), "missing.wrapped")
        // The key file was already absent (delete() -> true), but with no cleanup
        // injected we must not overclaim WIPED.
        val result = DefaultIdentityPanicWipe(
            AtomicSecretFile(f, directorySync = RecordingDirectorySync()),
        ).wipe()
        assertEquals(PanicWipeResult.PARTIAL, result)
    }

    @Test
    fun absentFileWithSucceedingCleanupIsWiped() {
        val f = File(tmp.newFolder("nobackup"), "missing.wrapped")
        val result = DefaultIdentityPanicWipe(
            AtomicSecretFile(f, directorySync = RecordingDirectorySync()),
            clearDerivedState = { true },
        ).wipe()
        assertEquals(PanicWipeResult.WIPED, result)
    }
}
