package com.meshchats.app.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Verifies [SensitiveFileDeleter.Default] is symlink-safe: it deletes the link
 * itself (never its target), removes a dangling link, and confirms absence with
 * `NOFOLLOW_LINKS`. Symlink tests are skipped on hosts that cannot create links.
 */
class SensitiveFileDeleterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val deleter = SensitiveFileDeleter.Default

    private fun trySymlink(link: File, target: File): Boolean =
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (_: IOException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: SecurityException) {
            false
        }

    @Test
    fun deletesPlainFileAndReportsAbsent() {
        val f = File(tmp.root, "secret.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(deleter.deleteConfirmingAbsent(f))
        assertFalse(f.exists())
    }

    @Test
    fun absentFileIsSuccess() {
        val f = File(tmp.root, "never-existed.bin")
        assertTrue(deleter.deleteConfirmingAbsent(f))
    }

    @Test
    fun deletesLinkItselfNeverFollowingToTarget() {
        val target = File(tmp.root, "target.bin").apply { writeBytes(byteArrayOf(9)) }
        val link = File(tmp.root, "link.bin")
        assumeTrue("host cannot create symlinks", trySymlink(link, target))

        assertTrue(deleter.deleteConfirmingAbsent(link))
        // The link entry is gone...
        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
        // ...but the target it pointed at must be untouched (never followed).
        assertTrue("deleter must never follow a symlink to its target", target.exists())
    }

    @Test
    fun deletesDanglingSymlink() {
        val missingTarget = File(tmp.root, "gone.bin")
        val link = File(tmp.root, "dangling.bin")
        assumeTrue("host cannot create symlinks", trySymlink(link, missingTarget))
        // Sanity: the link exists as a link even though its target does not.
        assertTrue(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(link.toPath()))

        // Must remove the dangling link and confirm the link entry is absent,
        // rather than being fooled into "success" by the missing target.
        assertTrue(deleter.deleteConfirmingAbsent(link))
        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
    }
}
