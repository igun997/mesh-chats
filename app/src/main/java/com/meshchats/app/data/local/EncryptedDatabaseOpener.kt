package com.meshchats.app.data.local

import android.content.Context
import com.meshchats.app.crypto.DatabaseKeyProvider
import com.meshchats.app.crypto.DatabaseKeyResult
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/** A bounded reason the encrypted database could not be prepared for opening. */
enum class EncryptedDatabaseError {
    /** The database key could not be obtained (generation, key loss, tamper, storage). */
    KEY_UNAVAILABLE,

    /**
     * A plaintext database existed but could not be safely migrated to encrypted.
     * The plaintext database is preserved intact; the app must not open it as if
     * encrypted (that would fail) nor destroy it.
     */
    MIGRATION_FAILED,
}

/** Raised when the encrypted database cannot be prepared. Carries a bounded reason. */
class EncryptedDatabaseException(
    val reason: EncryptedDatabaseError,
    val keyError: com.meshchats.app.crypto.DatabaseKeyError? = null,
    val migrationError: DatabaseMigrationError? = null,
) : IllegalStateException(
    "encrypted database unavailable: $reason" +
        (keyError?.let { " keyError=$it" } ?: "") +
        (migrationError?.let { " migrationError=$it" } ?: ""),
)

/**
 * Prepares the SQLCipher [SupportOpenHelperFactory] Room will open the database
 * with, performing one-time plaintext→encrypted migration first.
 *
 * ### Order of operations (all before Room opens the file)
 * 1. Load the native SQLCipher library.
 * 2. Obtain the wrapped 256-bit database key from [DatabaseKeyProvider]
 *    (fail-closed: a key-loss/tamper failure aborts rather than regenerating).
 * 3. Encode it to the SQLCipher raw-key form and run [PlaintextDatabaseMigration],
 *    which converts any legacy plaintext `mesh-chats.db` to encrypted crash-safely
 *    or leaves an already-encrypted/absent file alone.
 * 4. Hand the raw-key bytes to [SupportOpenHelperFactory].
 *
 * ### Key lifetime
 * SQLCipher's `SupportOpenHelperFactory` stores the raw-key byte array by
 * reference and re-passes it to the native engine every time a connection opens
 * (verified against the 4.17.0 artifact: `SQLiteDatabaseConfiguration.password`
 * is retained for the database's lifetime and re-keyed on each open). It never
 * copies and never zeroes the array. Therefore the raw-key bytes **must not** be
 * zeroed after the factory is constructed — doing so would corrupt the key on the
 * next connection open. The array lives as long as the Room database singleton,
 * which is the process. The plaintext 32-byte key from the provider *is* zeroed
 * here once encoded, so only the ASCII raw-key form persists.
 */
class EncryptedDatabaseOpener(
    private val keyProvider: DatabaseKeyProvider,
    private val databaseFile: File,
    private val exporterFactory: () -> EncryptedExporter = { SqlCipherExporter() },
) {

    /**
     * Returns a SQLCipher open-helper factory ready to hand to Room's
     * `openHelperFactory`, after migrating any legacy plaintext database.
     *
     * @throws EncryptedDatabaseException if the key is unavailable or a plaintext
     * migration failed (the plaintext database is preserved in that case).
     */
    fun createFactory(): SupportOpenHelperFactory {
        SqlCipherNative.ensureLoaded()

        val key = when (val r = keyProvider.getOrCreateKey()) {
            is DatabaseKeyResult.Success -> r.key
            is DatabaseKeyResult.Failure ->
                throw EncryptedDatabaseException(EncryptedDatabaseError.KEY_UNAVAILABLE, keyError = r.error)
        }

        // Encode to the raw-key ASCII form SQLCipher consumes directly, then zero
        // the plaintext key copy: only the raw-key form needs to survive.
        val rawKeyAscii = try {
            SqlCipherRawKey.encode(key)
        } finally {
            key.fill(0)
        }

        val migration = PlaintextDatabaseMigration(databaseFile, exporterFactory())
        when (val m = migration.migrateIfNeeded(rawKeyAscii)) {
            is DatabaseMigrationResult.Failed -> {
                // Do NOT zero rawKeyAscii on abort paths that keep it out of use; it
                // is short-lived and about to be unreachable. The plaintext db is
                // preserved by the migration itself.
                throw EncryptedDatabaseException(
                    EncryptedDatabaseError.MIGRATION_FAILED,
                    migrationError = m.error,
                )
            }
            DatabaseMigrationResult.Migrated, DatabaseMigrationResult.NotNeeded -> Unit
        }

        // The factory retains this array for the database's lifetime; do not zero it.
        return SupportOpenHelperFactory(rawKeyAscii)
    }

    companion object {
        /** Resolves the app's Room database file path from [context]. */
        fun databaseFile(context: Context, name: String): File =
            context.getDatabasePath(name)
    }
}
