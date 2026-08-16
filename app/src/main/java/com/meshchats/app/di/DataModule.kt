package com.meshchats.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.meshchats.app.crypto.AndroidDirectorySync
import com.meshchats.app.crypto.AndroidKeystoreSecretWrapper
import com.meshchats.app.crypto.AtomicSecretFile
import com.meshchats.app.crypto.DatabaseCloseOutcome
import com.meshchats.app.crypto.DatabaseKeyProvider
import com.meshchats.app.crypto.DefaultPanicWipeCoordinator
import com.meshchats.app.crypto.KeyDomain
import com.meshchats.app.crypto.PanicWipeCoordinator
import com.meshchats.app.crypto.ProductionDatabaseClose
import com.meshchats.app.crypto.SecureStorageLayout
import com.meshchats.app.crypto.SensitiveFileDeleter
import com.meshchats.app.data.local.EncryptedDatabaseOpener
import com.meshchats.app.data.local.MeshDatabase
import com.meshchats.app.data.local.MIGRATION_1_2
import com.meshchats.app.data.local.MIGRATION_2_3
import com.meshchats.app.data.local.MessageDao
import com.meshchats.app.crypto.identity.BouncyCastleEd25519Crypto
import com.meshchats.app.crypto.identity.DefaultDeviceIdentityRepository
import com.meshchats.app.crypto.identity.DefaultIdentityPanicWipe
import com.meshchats.app.crypto.identity.DeviceIdentityRepository
import com.meshchats.app.crypto.identity.Ed25519Crypto
import com.meshchats.app.crypto.identity.FingerprintHasher
import com.meshchats.app.crypto.identity.FourWordFingerprint
import com.meshchats.app.crypto.identity.FourWordList
import com.meshchats.app.crypto.identity.IdentityPanicWipe
import com.meshchats.app.crypto.identity.IdentityStore
import com.meshchats.app.crypto.identity.LibsignalIdentityFactory
import com.meshchats.app.crypto.identity.SignalIdentityFactory
import com.meshchats.app.data.local.IdentityProvisioningDao
import com.meshchats.app.data.local.RoomIdentityStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabaseKeyProvider(@ApplicationContext context: Context): DatabaseKeyProvider {
        // The wrapped key lives in noBackupFilesDir: it must never be captured by
        // cloud/adb backup (the encrypted database it protects would otherwise be
        // decryptable off-device) and never leave this device. The alias and file
        // name come from SecureStorageLayout so provisioning and panic wipe agree.
        return DatabaseKeyProvider(
            wrapper = AndroidKeystoreSecretWrapper(alias = SecureStorageLayout.DB_KEY_ALIAS),
            file = AtomicSecretFile(
                target = SecureStorageLayout.dbKeyFile(context),
                directorySync = AndroidDirectorySync(),
            ),
        )
    }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyProvider: DatabaseKeyProvider,
    ): MeshDatabase {
        val opener = EncryptedDatabaseOpener(
            keyProvider = keyProvider,
            databaseFile = EncryptedDatabaseOpener.databaseFile(context, MeshDatabase.NAME),
        )
        return Room.databaseBuilder(context, MeshDatabase::class.java, MeshDatabase.NAME)
            // Open every connection through SQLCipher with the wrapped key. Migration
            // of any legacy plaintext database happens inside createFactory(), before
            // Room touches the file.
            .openHelperFactory(opener.createFactory())
            // Explicit, non-destructive schema upgrade. MIGRATION_1_2 creates the
            // Signal, identity, and outbox tables and extends messages additively.
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            // Never destroy data on an unexpected schema state. No fallback destructive
            // migration/downgrade: a missing migration or downgrade must surface loudly
            // rather than silently dropping user data.
            .build()
    }

    @Provides
    fun provideMessageDao(database: MeshDatabase): MessageDao = database.messageDao()

    @Provides
    @Singleton
    fun providePreferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // A corrupt preferences file must never take the app down on launch.
            // Reset to empty on a CorruptionException so callers fall back to
            // their defaults (e.g. BLE discovery re-enables) instead of crashing.
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ) {
            context.preferencesDataStoreFile(SecureStorageLayout.PREFS_FILE)
        }

    @Provides
    @Singleton
    fun provideSignalIdentityFactory(): SignalIdentityFactory = LibsignalIdentityFactory()

    @Provides
    @Singleton
    fun provideEd25519Crypto(): Ed25519Crypto = BouncyCastleEd25519Crypto()

    @Provides
    fun provideIdentityProvisioningDao(database: MeshDatabase): IdentityProvisioningDao =
        database.identityProvisioningDao()

    @Provides
    @Singleton
    fun provideIdentityStore(dao: IdentityProvisioningDao): IdentityStore = RoomIdentityStore(dao)

    /**
     * The wrapped Ed25519 identity secret lives in noBackupFilesDir under a
     * dedicated Keystore alias, independently of the database key. The file
     * carries the (device-key-wrapped) private key plus public recovery metadata
     * so the create protocol is crash-recoverable.
     */
    @Provides
    @Singleton
    fun provideDeviceIdentityRepository(
        @ApplicationContext context: Context,
        crypto: Ed25519Crypto,
        signalFactory: SignalIdentityFactory,
        store: IdentityStore,
    ): DeviceIdentityRepository {
        val secretFile = AtomicSecretFile(
            target = SecureStorageLayout.identityKeyFile(context),
            directorySync = AndroidDirectorySync(),
        )
        return DefaultDeviceIdentityRepository(
            crypto = crypto,
            signalFactory = signalFactory,
            wrapper = AndroidKeystoreSecretWrapper(alias = SecureStorageLayout.IDENTITY_KEY_ALIAS),
            secretFile = secretFile,
            store = store,
            fourWords = FourWordFingerprint(FourWordList.load()),
            hasher = FingerprintHasher.Sha256,
        )
    }

    /**
     * Lower-level Ed25519-only panic hook, retained for callers that only need to
     * delete the wrapped identity secret file. It deliberately makes no claim of
     * total destruction. Production duress handling should use the authoritative
     * [PanicWipeCoordinator] instead — this hook is not the app-level entry point.
     */
    @Provides
    @Singleton
    fun provideIdentityPanicWipe(@ApplicationContext context: Context): IdentityPanicWipe {
        val secretFile = AtomicSecretFile(
            target = SecureStorageLayout.identityKeyFile(context),
            directorySync = AndroidDirectorySync(),
        )
        return DefaultIdentityPanicWipe(secretFile = secretFile)
    }

    /**
     * Authoritative, key-first app-level panic wipe. On a duress signal it destroys
     * BOTH persistent key domains first — each dedicated Keystore alias plus its
     * sole wrapped blob — rendering all data at rest cryptographically
     * unrecoverable, closes the encrypted database to release its file handle, then
     * best-effort deletes the now-inert data files (including nondeterministic temp/
     * lock residues and the cache directory).
     *
     * ### Why it reports RESTART_REQUIRED for the database close
     * SQLCipher's `SupportOpenHelperFactory` retains the raw-key byte array by
     * reference for the database's lifetime and re-keys every connection from it
     * (see [EncryptedDatabaseOpener]); the array is never exposed for zeroing. We
     * therefore cannot zero the in-memory raw key in place, so the close step
     * ([ProductionDatabaseClose]) honestly reports
     * [DatabaseCloseOutcome.RESTART_REQUIRED] even on a clean close: data at rest is
     * unrecoverable once the wrapping keys are gone, but a process restart is
     * required to guarantee no key bytes linger in RAM. This caps the outcome at
     * [com.meshchats.app.crypto.PanicWipeOutcome.KEYS_DESTROYED_DATA_PARTIAL] rather
     * than falsely claiming COMPLETE in production.
     *
     * ### Caller MUST terminate the process
     * Because production always yields `processRestartRequired == true`, the duress
     * caller/UI MUST terminate the process immediately after this wipe returns
     * (e.g. `Runtime.getRuntime().halt(0)`). The coordinator returns a report rather
     * than killing the process itself — the UI trigger is out of scope here — so the
     * app CANNOT reach [com.meshchats.app.crypto.PanicWipeOutcome.COMPLETE] today; the
     * strongest honest production outcome is KEYS_DESTROYED_DATA_PARTIAL.
     *
     * The database is injected as a [Provider] so the panic-wipe graph does not force
     * the (expensive, disk-touching) database open at coordinator construction; the
     * close step resolves and closes it only when a wipe actually runs.
     */
    @Provides
    @Singleton
    fun providePanicWipeCoordinator(
        @ApplicationContext context: Context,
        database: Provider<MeshDatabase>,
    ): PanicWipeCoordinator {
        // One symlink-safe deleter instance threaded through both key domains AND
        // the coordinator's data cleanup, so every delete on the wipe path shares
        // the same (NOFOLLOW_LINKS) semantics and there is no second deleter to drift.
        val deleter = SensitiveFileDeleter.Default
        val dbDomain = KeyDomain(
            destroyer = AndroidKeystoreSecretWrapper(alias = SecureStorageLayout.DB_KEY_ALIAS),
            wrappedBlob = SecureStorageLayout.dbKeyFile(context),
            deleter = deleter,
        )
        val identityDomain = KeyDomain(
            destroyer = AndroidKeystoreSecretWrapper(alias = SecureStorageLayout.IDENTITY_KEY_ALIAS),
            wrappedBlob = SecureStorageLayout.identityKeyFile(context),
            deleter = deleter,
        )
        return DefaultPanicWipeCoordinator(
            databaseKeyDomain = dbDomain,
            identityKeyDomain = identityDomain,
            // Actually close the encrypted database so its open-helper releases the
            // file before we delete it. The retained SQLCipher raw key cannot be
            // exposed for in-place zeroing, so ProductionDatabaseClose always reports
            // RESTART_REQUIRED: the wrapping keys are already destroyed above, so data
            // at rest is unrecoverable, but the caller must terminate the process to
            // clear the in-RAM key.
            //
            // Provider pre-open concern (accepted low): resolving `database.get()`
            // here can, in the pathological case where a wipe fires before storage
            // ever opened, force a fresh open of an already-key-destroyed database.
            // That open may fail or briefly recreate a file — harmless because the
            // wrapping keys are gone (data at rest is already unrecoverable) and the
            // subsequent file deletes plus mandatory process termination remove any
            // residue. Keeping the Provider (vs. eager DB) is deliberate: it avoids
            // forcing an expensive disk-touching open at coordinator construction on
            // every launch, which is the common path; the wipe path is rare.
            closeDatabase = { ProductionDatabaseClose.run { database.get().close() } },
            sensitiveFiles = {
                buildList {
                    // Fixed database siblings (db/WAL/SHM/journal + migration side files).
                    addAll(SecureStorageLayout.databaseFiles(context))
                    // Nondeterministic migration-marker temp siblings.
                    addAll(SecureStorageLayout.databaseResidueSiblings(context))
                    // Preferences / DataStore.
                    addAll(SecureStorageLayout.preferenceFiles(context))
                    // Wrapped key blobs (also removed per key domain; included so the
                    // residue check covers them) plus their temp/lock siblings.
                    addAll(SecureStorageLayout.secretFiles(context))
                    addAll(SecureStorageLayout.secretResidueSiblings(context))
                    // Cache directory contents (bottom-up so children precede parents).
                    addAll(SecureStorageLayout.cacheResidues(context))
                }
            },
            deleter = deleter,
        )
    }
}
