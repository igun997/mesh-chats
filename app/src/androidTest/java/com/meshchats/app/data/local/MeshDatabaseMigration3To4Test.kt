package com.meshchats.app.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the explicit v3→v4 migration (verified-contact bundle schema) preserves
 * every existing row, applies the additive contact + reservation defaults, and
 * produces the exact schema Room expects. Runs against a real SQLCipher-encrypted
 * database (the production open path).
 *
 * The migration is purely additive:
 *  - `contact_identities` gains `device_id` (default 1), the nullable Signal
 *    identity/binding blobs, and `signal_binding_version` (default 0), so any
 *    contact migrated from v3 is left unusable-until-reverified (blobs null,
 *    version 0).
 *  - `signal_prekeys` / `signal_kyber_prekeys` gain nullable reservation columns
 *    plus a UNIQUE composite index over `(reserved_for_address,
 *    reserved_for_device_id)` (SQLite allows multiple nulls, so unreserved rows
 *    never collide).
 *  - The new `contact_prekey_bundles` table is created with its FK to
 *    `contact_identities` (cascade) and an `expires_at` index.
 *
 * There is no destructive fallback: a missing/failed migration surfaces loudly.
 */
@RunWith(AndroidJUnit4::class)
class MeshDatabaseMigration3To4Test {

    private companion object {
        const val TEST_DB = "mesh-migration-3to4-test.db"
        val KEY: ByteArray = ByteArray(32) { (it * 3 + 1).toByte() }
    }

    private val rawKey = SqlCipherRawKey.encode(KEY)

    init {
        SqlCipherNative.ensureLoaded()
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MeshDatabase::class.java,
        emptyList(),
        SupportOpenHelperFactory(rawKey),
    )

    private fun seedV3(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        // A pre-existing contact, EC prekey, and kyber prekey — rows v4 must keep.
        db.execSQL(
            "INSERT INTO contact_identities " +
                "(address, public_key, fingerprint_sha256, trust_state, first_seen_at, updated_at) " +
                "VALUES ('peer', x'0102', x'0304', 'VERIFIED', 10, 20)",
        )
        db.execSQL(
            "INSERT INTO signal_prekeys (prekey_id, record, schema_version, created_at) " +
                "VALUES (7, x'aa', 1, 5)",
        )
        db.execSQL(
            "INSERT INTO signal_kyber_prekeys " +
                "(kyber_prekey_id, record, used, last_resort, schema_version, created_at) " +
                "VALUES (9, x'bb', 0, 0, 1, 5)",
        )
    }

    @Test
    fun migrate3To4PreservesRowsAppliesDefaultsAndCreatesBundleTable() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).close()
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            seedV3(db)
        }

        helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4).use { db ->
            // Contact preserved, with additive defaults: device 1, null Signal blobs,
            // binding version 0 → unusable until reverified.
            db.query("SELECT * FROM contact_identities WHERE address = 'peer'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("VERIFIED", c.getString(c.getColumnIndexOrThrow("trust_state")))
                assertEquals(1, c.getInt(c.getColumnIndexOrThrow("device_id")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("signal_identity_key")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("signal_binding_signature")))
                assertEquals(0, c.getInt(c.getColumnIndexOrThrow("signal_binding_version")))
            }
            // EC prekey preserved; reservation columns default null.
            db.query("SELECT * FROM signal_prekeys WHERE prekey_id = 7").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(c.getColumnIndexOrThrow("reserved_for_address")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("reserved_for_device_id")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("reserved_at")))
            }
            // Kyber prekey preserved; reservation columns default null.
            db.query("SELECT * FROM signal_kyber_prekeys WHERE kyber_prekey_id = 9").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(c.getColumnIndexOrThrow("reserved_for_address")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("reserved_for_device_id")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("reserved_at")))
            }
            // New bundle table exists and is empty.
            db.query("SELECT COUNT(*) FROM contact_prekey_bundles").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun migratedDatabaseOpensWithRoomAndBundleDaoWorks() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).close()
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            db.execSQL(
                "INSERT INTO contact_identities " +
                    "(address, public_key, fingerprint_sha256, trust_state, first_seen_at, updated_at) " +
                    "VALUES ('peer', x'0102', x'0304', 'VERIFIED', 10, 20)",
            )
        }
        helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4).close()

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(ctx, MeshDatabase::class.java, TEST_DB)
            .openHelperFactory(SupportOpenHelperFactory(rawKey))
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
        try {
            val bundle = ContactPreKeyBundleEntity(
                contactAddress = "peer",
                deviceId = 1,
                encodedBundle = byteArrayOf(9, 8, 7),
                issuedAt = 100,
                receivedAt = 110,
                expiresAt = 200,
                schemaVersion = 1,
            )
            runBlocking { db.contactPreKeyBundleDao().upsert(bundle) }
            val loaded = runBlocking { db.contactPreKeyBundleDao().get("peer") }!!
            assertArrayEquals(byteArrayOf(9, 8, 7), loaded.encodedBundle)
            assertEquals(200L, loaded.expiresAt)

            // FK cascade: deleting the contact removes its bundle.
            runBlocking { db.contactIdentityDao().delete("peer") }
            assertNull(runBlocking { db.contactPreKeyBundleDao().get("peer") })
        } finally {
            db.close()
            ctx.deleteDatabase(TEST_DB)
        }
    }
}
