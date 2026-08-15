package com.meshchats.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Static policy assertions over the app's manifest and backup/extraction XML: an
 * automated guard that the "no off-device copy of the encrypted database" posture
 * cannot silently regress. These read the source resource files directly rather
 * than the merged manifest so they run as fast host-JVM tests with no device.
 *
 * The database exclusions are **derived from [SecureStorageLayout] constants**, not
 * duplicated as string literals, so adding a new sensitive database sibling in the
 * single source of truth automatically requires it in both backup XMLs — the two
 * can never drift apart, and no test here merely re-lists hardcoded migration names.
 *
 * The paths are resolved relative to the module directory (the working directory
 * for unit tests), then fall back to the conventional `app/` layout, avoiding any
 * brittle absolute path.
 */
class BackupPolicyTest {

    private fun moduleFile(relative: String): File {
        val direct = File(relative)
        if (direct.exists()) return direct
        val underApp = File("app/$relative")
        if (underApp.exists()) return underApp
        error("could not locate $relative from ${File(".").absolutePath}")
    }

    private fun parse(relative: String): Element {
        val f = moduleFile(relative)
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(f)
        return doc.documentElement
    }

    private val androidNs = "http://schemas.android.com/apk/res/android"

    /**
     * Every sensitive domain/path pair that must be excluded, derived from the
     * centralized [SecureStorageLayout.DATABASE_RELATIVE_FILES] plus the two
     * catch-all `file`/`sharedpref` scopes. Derivation (not literal duplication) is
     * what keeps this in lockstep with the wipe path and the migration.
     */
    private val requiredExclusions: List<Pair<String, String>> =
        SecureStorageLayout.DATABASE_RELATIVE_FILES.map { "database" to it } +
            listOf("file" to ".", "sharedpref" to ".")

    @Test
    fun manifestDisablesBackupAndPointsAtBothRuleSets() {
        val manifest = parse("src/main/AndroidManifest.xml")
        val application = manifest.getElementsByTagName("application").item(0) as Element

        // Backup disabled outright: the primary defense.
        assertEquals(
            "false",
            application.getAttributeNS(androidNs, "allowBackup"),
        )
        // Both rule sets are wired so a future re-enable inherits the exclusions.
        assertEquals(
            "@xml/data_extraction_rules",
            application.getAttributeNS(androidNs, "dataExtractionRules"),
        )
        assertEquals(
            "@xml/backup_rules",
            application.getAttributeNS(androidNs, "fullBackupContent"),
        )
    }

    private fun exclusionsUnder(parent: Element): Set<Pair<String, String>> {
        val out = mutableSetOf<Pair<String, String>>()
        val excludes = parent.getElementsByTagName("exclude")
        for (i in 0 until excludes.length) {
            val e = excludes.item(i) as Element
            out.add(e.getAttribute("domain") to e.getAttribute("path"))
        }
        return out
    }

    private fun childElement(parent: Element, tag: String): Element {
        val list = parent.getElementsByTagName(tag)
        assertTrue("<$tag> missing", list.length > 0)
        return list.item(0) as Element
    }

    @Test
    fun legacyFullBackupExcludesEverySensitiveStore() {
        val root = parse("src/main/res/xml/backup_rules.xml")
        val got = exclusionsUnder(root)
        requiredExclusions.forEach { req ->
            assertTrue("full-backup-content missing exclusion $req", got.contains(req))
        }
    }

    @Test
    fun cloudBackupExcludesEverySensitiveStore() {
        val root = parse("src/main/res/xml/data_extraction_rules.xml")
        val cloud = childElement(root, "cloud-backup")
        val got = exclusionsUnder(cloud)
        requiredExclusions.forEach { req ->
            assertTrue("cloud-backup missing exclusion $req", got.contains(req))
        }
    }

    @Test
    fun deviceTransferExcludesEverySensitiveStore() {
        val root = parse("src/main/res/xml/data_extraction_rules.xml")
        val transfer = childElement(root, "device-transfer")
        val got = exclusionsUnder(transfer)
        requiredExclusions.forEach { req ->
            assertTrue("device-transfer missing exclusion $req", got.contains(req))
        }
    }

    @Test
    fun backupRulesCoverEveryCentralizedDatabaseSibling() {
        // The database exclusions must stay in lockstep with the SINGLE source of
        // truth (SecureStorageLayout.DATABASE_RELATIVE_FILES), which the migration,
        // the wipe path, and this test all derive from. Adding a sibling there forces
        // it into every backup flow here — no hardcoded migration-name list to drift.
        val centralizedSiblings = SecureStorageLayout.DATABASE_RELATIVE_FILES
        // Guard: the centralized list must actually include the journal + migration
        // lock the review required, so a regression that drops them fails loudly.
        assertTrue(centralizedSiblings.contains("mesh-chats.db-journal"))
        assertTrue(centralizedSiblings.contains("mesh-chats.db.migration.lock"))

        val legacy = exclusionsUnder(parse("src/main/res/xml/backup_rules.xml"))
        val cloud = exclusionsUnder(
            childElement(parse("src/main/res/xml/data_extraction_rules.xml"), "cloud-backup"),
        )
        val transfer = exclusionsUnder(
            childElement(parse("src/main/res/xml/data_extraction_rules.xml"), "device-transfer"),
        )
        centralizedSiblings.forEach { name ->
            val pair = "database" to name
            assertTrue("backup_rules.xml missing database exclusion for $name", legacy.contains(pair))
            assertTrue("cloud-backup missing database exclusion for $name", cloud.contains(pair))
            assertTrue("device-transfer missing database exclusion for $name", transfer.contains(pair))
        }
    }
}
