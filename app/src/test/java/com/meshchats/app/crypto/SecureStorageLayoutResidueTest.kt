package com.meshchats.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verifies the centralized residue discovery in [SecureStorageLayout] finds the
 * NONDETERMINISTIC temp/lock siblings the atomic-write and migration machinery
 * leave behind, plus the recursive cache sweep. These are the residues a static
 * name list cannot enumerate, so a COMPLETE wipe depends on discovering them here.
 */
class SecureStorageLayoutResidueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(dir: File, name: String): File =
        File(dir, name).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }

    @Test
    fun secretResidueSiblingsFindsTempAndLockButNotBlobsOrUnrelated() {
        val dir = tmp.newFolder("noBackup")
        // The fixed blobs themselves are NOT residue (handled by secretFiles()).
        touch(dir, SecureStorageLayout.DB_KEY_FILE)
        touch(dir, SecureStorageLayout.IDENTITY_KEY_FILE)
        // Nondeterministic per-write temp files + persistent lock files ARE residue.
        val dbTemp = touch(dir, "${SecureStorageLayout.DB_KEY_FILE}.tmp-123456789")
        val dbLock = touch(dir, "${SecureStorageLayout.DB_KEY_FILE}.lock")
        val idTemp = touch(dir, "${SecureStorageLayout.IDENTITY_KEY_FILE}.tmp-987654321")
        val idLock = touch(dir, "${SecureStorageLayout.IDENTITY_KEY_FILE}.lock")
        // Unrelated file must be ignored.
        val unrelated = touch(dir, "something-else.txt")

        val found = SecureStorageLayout.secretResidueSiblings(dir).toSet()
        assertTrue(found.contains(dbTemp))
        assertTrue(found.contains(dbLock))
        assertTrue(found.contains(idTemp))
        assertTrue(found.contains(idLock))
        assertFalse(found.contains(File(dir, SecureStorageLayout.DB_KEY_FILE)))
        assertFalse(found.contains(File(dir, SecureStorageLayout.IDENTITY_KEY_FILE)))
        assertFalse(found.contains(unrelated))
    }

    @Test
    fun databaseResidueSiblingsFindsMigrationMarkerTempFiles() {
        val dir = tmp.newFolder("databases")
        val markerTemp = touch(dir, "${SecureStorageLayout.DATABASE_NAME}.migration.tmp-42")
        // Fixed names (.migration, .migration.lock) are covered by DATABASE_RELATIVE_FILES,
        // so residue discovery deliberately does NOT re-report them.
        touch(dir, "${SecureStorageLayout.DATABASE_NAME}.migration")
        touch(dir, "${SecureStorageLayout.DATABASE_NAME}.migration.lock")
        touch(dir, SecureStorageLayout.DATABASE_NAME)

        val found = SecureStorageLayout.databaseResidueSiblings(dir)
        assertEquals(listOf(markerTemp), found)
    }

    @Test
    fun cacheResiduesReturnsAllEntriesBottomUpExcludingRoot() {
        val cache = tmp.newFolder("cache")
        val nested = File(cache, "sub/deeper")
        nested.mkdirs()
        val f1 = touch(cache, "a.bin")
        val f2 = touch(File(cache, "sub"), "b.bin")
        val f3 = touch(nested, "c.bin")

        val residues = SecureStorageLayout.cacheResidues(cache)
        // Root itself is never returned; every child is.
        assertFalse(residues.contains(cache))
        assertTrue(residues.contains(f1))
        assertTrue(residues.contains(f2))
        assertTrue(residues.contains(f3))
        assertTrue(residues.contains(File(cache, "sub")))
        assertTrue(residues.contains(nested))

        // Bottom-up: a directory never precedes a file it contains, so a
        // file-at-a-time delete can empty directories before removing them.
        val idxDeepFile = residues.indexOf(f3)
        val idxDeepDir = residues.indexOf(nested)
        val idxMidDir = residues.indexOf(File(cache, "sub"))
        assertTrue(idxDeepFile < idxDeepDir)
        assertTrue(idxDeepDir < idxMidDir)
    }

    @Test
    fun residueDiscoveryOnMissingDirIsEmptyNotThrowing() {
        val missing = File(tmp.root, "does-not-exist")
        assertTrue(SecureStorageLayout.secretResidueSiblings(missing).isEmpty())
        assertTrue(SecureStorageLayout.databaseResidueSiblings(missing).isEmpty())
        assertTrue(SecureStorageLayout.cacheResidues(missing).isEmpty())
    }

    @Test
    fun databaseRelativeFilesIncludesJournalAndMigrationLock() {
        // The drift-fix requires these inert-but-possible siblings in the single
        // source of truth so both the wipe and the backup XML pick them up.
        assertTrue(SecureStorageLayout.DATABASE_RELATIVE_FILES.contains("mesh-chats.db-journal"))
        assertTrue(SecureStorageLayout.DATABASE_RELATIVE_FILES.contains("mesh-chats.db.migration.lock"))
    }
}
