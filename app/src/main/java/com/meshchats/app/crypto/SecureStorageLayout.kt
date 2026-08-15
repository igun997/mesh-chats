package com.meshchats.app.crypto

import android.content.Context
import java.io.File

/**
 * Single source of truth for every persistent name a panic wipe must be able to
 * destroy and that provisioning must create at the same location. Centralizing
 * these here means the create path (DI wiring, DatabaseKeyProvider, identity
 * repository) and the wipe path (PanicWipeCoordinator) can never drift apart:
 * if a new sensitive file is added, it is added here once and both paths see it.
 *
 * Two persistent key domains protect everything at rest:
 * - the **database key** domain: Keystore alias [DB_KEY_ALIAS] wrapping the blob
 *   at [DB_KEY_FILE]; destroying either renders the SQLCipher database undecryptable;
 * - the **identity key** domain: Keystore alias [IDENTITY_KEY_ALIAS] wrapping the
 *   blob at [IDENTITY_KEY_FILE]; destroying either renders the wrapped Ed25519 +
 *   Signal identity secret undecryptable.
 *
 * The two aliases are deliberately distinct so the domains are separated at the
 * key level: compromising (or, here, destroying) one never affects the other.
 *
 * ### Deterministic names vs. nondeterministic residues
 * The fixed file names live in [DATABASE_RELATIVE_FILES], [SECRET_FILE_NAMES], and
 * the preference names. On top of those, the atomic-write and crash-safe-migration
 * machinery creates **nondeterministic** siblings — per-write temp files named
 * `<base>.tmp-<nanoTime>` (see [TEMP_SIBLING_INFIX]) and persistent creation
 * `<base>.lock` files (see [LOCK_SIBLING_SUFFIX]). Those can never be enumerated by
 * a static list, so the discovery helpers ([secretResidueSiblings],
 * [databaseResidueSiblings], [cacheResidues]) scan the owning directories at wipe
 * time. A wipe is only COMPLETE when the deterministic blobs/aliases AND every
 * discovered residue are confirmed absent.
 */
object SecureStorageLayout {

    /** Keystore alias wrapping the database key. */
    const val DB_KEY_ALIAS = "mesh-chats.db-key.v1"

    /** Keystore alias wrapping the Ed25519 identity secret. */
    const val IDENTITY_KEY_ALIAS = "mesh-chats.identity-key.v1"

    /** Wrapped database-key record file name (inside `noBackupFilesDir`). */
    const val DB_KEY_FILE = "db-key.wrapped"

    /** Wrapped identity-secret record file name (inside `noBackupFilesDir`). */
    const val IDENTITY_KEY_FILE = "identity-key.wrapped"

    /** Preferences / DataStore file name. */
    const val PREFS_FILE = "mesh_chats_prefs"

    /** Room database name (matches [com.meshchats.app.data.local.MeshDatabase.NAME]). */
    const val DATABASE_NAME = "mesh-chats.db"

    /**
     * Infix that [AtomicSecretFile] and [com.meshchats.app.data.local.DurableMigrationMarkerWriter]
     * use for their per-write temp files: `<base>.tmp-<nanoTime>`. Matched as a
     * prefix (`<base>.tmp-`) because the numeric suffix is nondeterministic.
     */
    const val TEMP_SIBLING_INFIX = ".tmp-"

    /**
     * Suffix of the persistent creation-lock file [AtomicSecretFile] and
     * [com.meshchats.app.data.local.PlaintextDatabaseMigration] keep beside a
     * protected path: `<base>.lock`.
     */
    const val LOCK_SIBLING_SUFFIX = ".lock"

    /** Suffix of the crash-recovery phase marker the plaintext migration writes. */
    const val MIGRATION_MARKER_SUFFIX = ".migration"

    /**
     * Every fixed SQLCipher database sibling a wipe must remove and a backup flow
     * must exclude: the database itself, its WAL/SHM/journal, and the
     * plaintext-migration temp/backup/marker/lock side files created by
     * [com.meshchats.app.data.local.PlaintextDatabaseMigration]. This is the single
     * list the wipe path, the backup-exclusion XML, and [BackupPolicyTest] all
     * derive from, so they can never drift. `-journal` and `.migration.lock` are
     * included even though they are inert under WAL/normal operation, so a backup
     * or wipe can never miss them if they do appear.
     */
    val DATABASE_RELATIVE_FILES: List<String> = listOf(
        DATABASE_NAME,
        "$DATABASE_NAME-wal",
        "$DATABASE_NAME-shm",
        "$DATABASE_NAME-journal",
        "$DATABASE_NAME.enc-tmp",
        "$DATABASE_NAME.pt-bak",
        "$DATABASE_NAME-wal.pt-bak",
        "$DATABASE_NAME-shm.pt-bak",
        "$DATABASE_NAME$MIGRATION_MARKER_SUFFIX",
        "$DATABASE_NAME$MIGRATION_MARKER_SUFFIX$LOCK_SIBLING_SUFFIX",
    )

    /** The wrapped-secret blob file names (inside `noBackupFilesDir`). */
    val SECRET_FILE_NAMES: List<String> = listOf(DB_KEY_FILE, IDENTITY_KEY_FILE)

    /** The wrapped database-key file under [Context.getNoBackupFilesDir]. */
    fun dbKeyFile(context: Context): File = File(context.noBackupFilesDir, DB_KEY_FILE)

    /** The wrapped identity-secret file under [Context.getNoBackupFilesDir]. */
    fun identityKeyFile(context: Context): File = File(context.noBackupFilesDir, IDENTITY_KEY_FILE)

    /** The directory Room places the database (and its siblings) under. */
    fun databaseDir(context: Context): File? =
        context.getDatabasePath(DATABASE_NAME).parentFile

    /** The fixed database siblings a wipe must remove. Derived from [DATABASE_RELATIVE_FILES]. */
    fun databaseFiles(context: Context): List<File> {
        val db = context.getDatabasePath(DATABASE_NAME)
        val dir = db.parentFile
        return DATABASE_RELATIVE_FILES.map { if (dir != null) File(dir, it) else db.resolveSibling(it) }
    }

    /** The fixed wrapped-secret blob files a wipe must remove. */
    fun secretFiles(context: Context): List<File> =
        SECRET_FILE_NAMES.map { File(context.noBackupFilesDir, it) }

    /**
     * The DataStore / shared-preferences files a wipe should best-effort remove.
     * DataStore preferences live under `datastore/<name>.preferences_pb`.
     */
    fun preferenceFiles(context: Context): List<File> {
        val filesDir = context.filesDir
        val dataStoreDir = File(filesDir, "datastore")
        return listOf(
            File(dataStoreDir, "$PREFS_FILE.preferences_pb"),
            // The lock/temp DataStore keeps alongside its file.
            File(dataStoreDir, "$PREFS_FILE.preferences_pb.tmp"),
        )
    }

    // ---- Nondeterministic residue discovery (scanned at wipe time) --------------

    /**
     * Discovers the nondeterministic temp/lock siblings the wrapped-secret writer
     * leaves beside [DB_KEY_FILE] / [IDENTITY_KEY_FILE] in [noBackupDir]: any
     * `<base>.tmp-<nanoTime>` from an interrupted atomic write and the persistent
     * `<base>.lock`. Context-free so it is unit-testable against a temp directory.
     */
    fun secretResidueSiblings(noBackupDir: File): List<File> =
        listMatching(noBackupDir) { name ->
            SECRET_FILE_NAMES.any { base ->
                name != base &&
                    (name.startsWith("$base$TEMP_SIBLING_INFIX") || name == "$base$LOCK_SIBLING_SUFFIX")
            }
        }

    /** [secretResidueSiblings] rooted at the app's `noBackupFilesDir`. */
    fun secretResidueSiblings(context: Context): List<File> =
        secretResidueSiblings(context.noBackupFilesDir)

    /**
     * Discovers the nondeterministic migration-marker temp siblings
     * (`<db>.migration.tmp-<nanoTime>`) left in [databaseDir] by an interrupted
     * durable marker write. The fixed `.migration` and `.migration.lock` names are
     * already covered by [DATABASE_RELATIVE_FILES]. Context-free for unit tests.
     */
    fun databaseResidueSiblings(databaseDir: File): List<File> =
        listMatching(databaseDir) { name ->
            name.startsWith("$DATABASE_NAME$MIGRATION_MARKER_SUFFIX$TEMP_SIBLING_INFIX")
        }

    /** [databaseResidueSiblings] rooted at the Room database directory. */
    fun databaseResidueSiblings(context: Context): List<File> {
        val dir = databaseDir(context) ?: return emptyList()
        return databaseResidueSiblings(dir)
    }

    /**
     * Every entry under [cacheDir], returned **bottom-up** (deepest files/dirs
     * first) so a file-at-a-time deleter can remove children before their parent
     * directories. The cache root itself is not returned; the OS recreates it.
     * Best effort: unreadable subtrees are simply skipped. Context-free for tests.
     */
    fun cacheResidues(cacheDir: File): List<File> {
        if (!cacheDir.isDirectory) return emptyList()
        val out = mutableListOf<File>()
        try {
            cacheDir.walkBottomUp().forEach { f ->
                if (f != cacheDir) out.add(f)
            }
        } catch (_: Exception) {
            // Best effort: a partial listing is still worth deleting.
        }
        return out
    }

    /** [cacheResidues] rooted at the app cache directory. */
    fun cacheResidues(context: Context): List<File> = cacheResidues(context.cacheDir)

    private fun listMatching(dir: File, predicate: (String) -> Boolean): List<File> {
        if (!dir.isDirectory) return emptyList()
        val entries = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return emptyList()
        return entries.filter { predicate(it.name) }
    }
}
