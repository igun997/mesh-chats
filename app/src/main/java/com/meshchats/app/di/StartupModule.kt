package com.meshchats.app.di

import com.meshchats.app.data.local.MeshDatabase
import com.meshchats.app.startup.DatabaseForceOpen
import com.meshchats.app.startup.DatabaseStartupCoordinator
import com.meshchats.app.startup.DefaultDatabaseStartupCoordinator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Wires the encrypted-storage startup gate: the IO dispatcher used for blocking
 * open work, the force-open seam, and the coordinator singleton.
 */
@Module
@InstallIn(SingletonComponent::class)
object StartupModule {

    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * The force-open seam. It resolves the [MeshDatabase] through a [Provider] so
     * the (expensive, disk-touching) Room build + SQLCipher open happens only when
     * a startup attempt runs — not at coordinator construction — and then forces an
     * actual writable connection open. Merely constructing Room is lazy: Room does
     * not touch the file until the first query, so we call
     * `openHelper.writableDatabase` to make a key-unavailable or migration failure
     * surface here (on the IO dispatcher) rather than later on the UI thread.
     *
     * Hilt/Dagger may wrap the underlying `EncryptedDatabaseException` in a
     * `ProvisionException`/`RuntimeException` when the provider resolves; the
     * coordinator's cause-chain classifier unwraps it to a bounded reason.
     */
    @Provides
    @Singleton
    fun provideDatabaseForceOpen(database: Provider<MeshDatabase>): DatabaseForceOpen =
        DatabaseForceOpen {
            // Resolve (builds Room + SQLCipher factory) then force an actual open.
            database.get().openHelper.writableDatabase
        }

    /**
     * The coordinator does not own a scope: its caller (the
     * [com.meshchats.app.startup.StorageStartupViewModel]) drives [initialize] from
     * its own lifecycle scope, so no [kotlinx.coroutines.CoroutineScope] is injected
     * here.
     */
    @Provides
    @Singleton
    fun provideDatabaseStartupCoordinator(
        forceOpen: DatabaseForceOpen,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): DatabaseStartupCoordinator =
        DefaultDatabaseStartupCoordinator(
            forceOpen = forceOpen,
            ioDispatcher = ioDispatcher,
        )
}
