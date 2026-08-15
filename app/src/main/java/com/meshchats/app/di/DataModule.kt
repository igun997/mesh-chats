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
import com.meshchats.app.crypto.DatabaseKeyProvider
import com.meshchats.app.data.local.EncryptedDatabaseOpener
import com.meshchats.app.data.local.MeshDatabase
import com.meshchats.app.data.local.MIGRATION_1_2
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
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    /**
     * Keystore alias dedicated to wrapping the database key. It is deliberately
     * distinct from any identity-key alias so the two secrets are cryptographically
     * domain-separated at the key level (in addition to the AEAD associated-data
     * separation inside [DatabaseKeyProvider]).
     */
    private const val DB_KEY_ALIAS = "mesh-chats.db-key.v1"

    /** File name of the wrapped database-key record inside `noBackupFilesDir`. */
    private const val DB_KEY_FILE = "db-key.wrapped"

    /**
     * Keystore alias dedicated to wrapping the Ed25519 identity secret. It is
     * deliberately distinct from [DB_KEY_ALIAS] so the identity key and the
     * database key are domain-separated at the key level: compromising one alias
     * never yields the other secret.
     */
    private const val IDENTITY_KEY_ALIAS = "mesh-chats.identity-key.v1"

    /** File name of the wrapped identity-secret record inside `noBackupFilesDir`. */
    private const val IDENTITY_KEY_FILE = "identity-key.wrapped"

    @Provides
    @Singleton
    fun provideDatabaseKeyProvider(@ApplicationContext context: Context): DatabaseKeyProvider {
        // The wrapped key lives in noBackupFilesDir: it must never be captured by
        // cloud/adb backup (the encrypted database it protects would otherwise be
        // decryptable off-device) and never leave this device.
        val keyFile = File(context.noBackupFilesDir, DB_KEY_FILE)
        return DatabaseKeyProvider(
            wrapper = AndroidKeystoreSecretWrapper(alias = DB_KEY_ALIAS),
            file = AtomicSecretFile(target = keyFile, directorySync = AndroidDirectorySync()),
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
            .addMigrations(MIGRATION_1_2)
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
            context.preferencesDataStoreFile("mesh_chats_prefs")
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
            target = File(context.noBackupFilesDir, IDENTITY_KEY_FILE),
            directorySync = AndroidDirectorySync(),
        )
        return DefaultDeviceIdentityRepository(
            crypto = crypto,
            signalFactory = signalFactory,
            wrapper = AndroidKeystoreSecretWrapper(alias = IDENTITY_KEY_ALIAS),
            secretFile = secretFile,
            store = store,
            fourWords = FourWordFingerprint(FourWordList.load()),
            hasher = FingerprintHasher.Sha256,
        )
    }

    /**
     * Key-first panic wipe: deletes the wrapped identity secret file first (the
     * irreversible step), then best-effort clears derived DB state. Full duress UI
     * is wired later; this provides the authoritative, correctly ordered hook.
     */
    @Provides
    @Singleton
    fun provideIdentityPanicWipe(@ApplicationContext context: Context): IdentityPanicWipe {
        val secretFile = AtomicSecretFile(
            target = File(context.noBackupFilesDir, IDENTITY_KEY_FILE),
            directorySync = AndroidDirectorySync(),
        )
        return DefaultIdentityPanicWipe(secretFile = secretFile)
    }
}
