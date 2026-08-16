package com.meshchats.app.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the explicit v1→v2 migration preserves existing rows, applies additive
 * defaults, and produces the exact schema Room expects. Runs against a real
 * SQLCipher-encrypted database (the production open path), so the migration is
 * exercised through the same engine the app uses.
 */
@RunWith(AndroidJUnit4::class)
class MeshDatabaseMigrationTest {

    private companion object {
        const val TEST_DB = "mesh-migration-test.db"
        // Fixed test-only key; never touches the Keystore or production key file.
        val KEY: ByteArray = ByteArray(32) { (it * 7 + 3).toByte() }
    }

    private val rawKey = SqlCipherRawKey.encode(KEY)

    init {
        SqlCipherNative.ensureLoaded()
    }

    // A SQLCipher-backed factory so MigrationTestHelper creates and opens the test
    // database exactly like production (encrypted), not with plaintext framework SQLite.
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MeshDatabase::class.java,
        emptyList(),
        SupportOpenHelperFactory(rawKey),
    )

    @Test
    fun migrate1To2PreservesRowsAndAppliesDefaults() {
        // Create the v1 schema and insert a legacy message.
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO messages (id, conversation_id, author_id, body, sent_at, is_outgoing) " +
                    "VALUES ('legacy1', 'c1', 'peer', 'old message', 42, 0)",
            )
        }

        // Run the migration; MigrationTestHelper validates the resulting schema
        // matches the exported v2 JSON exactly (or the call fails).
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT * FROM messages WHERE id = 'legacy1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("old message", c.getString(c.getColumnIndexOrThrow("body")))
                // Additive defaults match the current UI steady state.
                assertEquals("DELIVERED", c.getString(c.getColumnIndexOrThrow("delivery_state")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("packet_id")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("expires_at")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("route_path")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("failure_reason")))
            }
            // The new tables exist and are empty.
            for (table in listOf(
                "device_identity", "contact_identities", "signal_identity",
                "signal_trusted_identities", "signal_sessions", "signal_prekeys",
                "signal_signed_prekeys", "signal_kyber_prekeys",
                "ciphertext_outbox", "delivery_attempts",
            )) {
                db.query("SELECT COUNT(*) FROM $table").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("$table should exist and be empty", 0, c.getInt(0))
                }
            }
        }
    }

    @Test
    fun migratedDatabaseOpensWithRoomAndKeepsData() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO messages (id, conversation_id, author_id, body, sent_at, is_outgoing) " +
                    "VALUES ('m1', 'c9', 'me', 'hello', 7, 1)",
            )
        }
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).close()

        // Open the migrated database through Room + SQLCipher and read the row back.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(ctx, MeshDatabase::class.java, TEST_DB)
            .openHelperFactory(SupportOpenHelperFactory(rawKey))
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
        try {
            val list = runBlocking { db.messageDao().observeConversation("c9").first() }
            assertEquals(1, list.size)
            assertEquals("hello", list.first().body)
            assertEquals("DELIVERED", list.first().deliveryState)
        } finally {
            db.close()
            ctx.deleteDatabase(TEST_DB)
        }
    }
}
