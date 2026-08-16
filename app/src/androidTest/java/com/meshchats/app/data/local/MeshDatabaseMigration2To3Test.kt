package com.meshchats.app.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the explicit v2→v3 migration preserves existing rows and produces the
 * exact schema Room expects. Runs against a real SQLCipher-encrypted database
 * (the production open path), so the migration is exercised through the same
 * engine the app uses. The new `signal_kyber_base_keys` table is created empty
 * with its FK to `signal_kyber_prekeys`, and after migrating the database opens
 * through Room and the replay DAO operates end to end.
 */
@RunWith(AndroidJUnit4::class)
class MeshDatabaseMigration2To3Test {

    private companion object {
        const val TEST_DB = "mesh-migration-2to3-test.db"
        val KEY: ByteArray = ByteArray(32) { (it * 5 + 2).toByte() }
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

    @Test
    fun migrate2To3PreservesRowsAndCreatesReplayTable() {
        // Create the v2 schema (via 1→2), then seed a message, a Kyber prekey, and
        // a signed prekey — rows the v3 migration must preserve untouched.
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
            db.execSQL(
                "INSERT INTO messages (id, conversation_id, author_id, body, sent_at, is_outgoing, delivery_state) " +
                    "VALUES ('legacy1', 'c1', 'peer', 'kept message', 42, 0, 'DELIVERED')",
            )
            db.execSQL(
                "INSERT INTO signal_kyber_prekeys " +
                    "(kyber_prekey_id, record, used, last_resort, schema_version, created_at) " +
                    "VALUES (5, x'0102', 0, 0, 1, 7)",
            )
            db.execSQL(
                "INSERT INTO signal_signed_prekeys (signed_prekey_id, record, schema_version, created_at) " +
                    "VALUES (3, x'0304', 1, 7)",
            )
        }

        // Run 2→3; MigrationTestHelper validates the resulting schema matches the
        // exported v3 JSON exactly (or this call fails).
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            // v2 rows preserved.
            db.query("SELECT body FROM messages WHERE id = 'legacy1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("kept message", c.getString(0))
            }
            db.query("SELECT COUNT(*) FROM signal_kyber_prekeys WHERE kyber_prekey_id = 5").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }
            // New table exists and is empty.
            db.query("SELECT COUNT(*) FROM signal_kyber_base_keys").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun migratedDatabaseOpensWithRoomAndReplayDaoWorks() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
            db.execSQL(
                "INSERT INTO signal_kyber_prekeys " +
                    "(kyber_prekey_id, record, used, last_resort, schema_version, created_at) " +
                    "VALUES (11, x'09', 0, 0, 1, 1)",
            )
        }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).close()

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(ctx, MeshDatabase::class.java, TEST_DB)
            .openHelperFactory(SupportOpenHelperFactory(rawKey))
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
        try {
            val base = ByteArray(33) { 0x1 }
            val result = runBlocking {
                db.signalKyberBaseKeyDao().markKyberUsedWithBaseKey(11, 2, base, now = 100)
            }
            assertEquals(MarkKyberUsedResult.MARKED, result)
            val rows = runBlocking { db.signalKyberBaseKeyDao().baseKeysFor(11) }
            assertEquals(1, rows.size)
            assertArrayEquals(base, rows.first().baseKey)
            assertTrue(runBlocking { db.signalKyberPreKeyDao().load(11)!!.used })
        } finally {
            db.close()
            ctx.deleteDatabase(TEST_DB)
        }
    }
}
