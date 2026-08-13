package com.meshchats.app.di

import android.content.Context
import android.os.SystemClock
import com.meshchats.app.core.mesh.HybridMeshStateRepository
import com.meshchats.app.core.mesh.MeshStateRepository
import com.meshchats.app.core.transport.ble.AndroidBleRadio
import com.meshchats.app.core.transport.ble.BleBeacon
import com.meshchats.app.core.transport.ble.BleCapability
import com.meshchats.app.core.transport.ble.BleDiscoveryController
import com.meshchats.app.core.transport.ble.BleRadio
import com.meshchats.app.core.transport.ble.DefaultBleDiscoveryController
import com.meshchats.app.core.transport.ble.DiscoveredBlePeerRegistry
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.SecureRandom
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    /** Application-lifetime scope for repositories that observe radios. */
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/**
 * Wires the BLE discovery graph and binds the mesh repository.
 *
 * Node identity is an **ephemeral, per-process** value: a fresh secure-random
 * 64-bit ID is drawn at graph construction and lives only for this process
 * lifetime. Nothing is persisted in this slice, so a restart looks like a new
 * node — deliberate, since there is no identity exchange or key material yet.
 */
@Module
@InstallIn(SingletonComponent::class)
object BleModule {

    @Provides
    @Singleton
    fun provideBleRadio(@ApplicationContext context: Context): BleRadio =
        AndroidBleRadio(context)

    /** Ephemeral local beacon advertised while scanning; not persisted. */
    @Provides
    @Singleton
    fun provideLocalBeacon(): BleBeacon =
        BleBeacon(
            nodeId = SecureRandom().nextLong(),
            capabilities = setOf(BleCapability.CHAT, BleCapability.SOS),
        )

    @Provides
    @Singleton
    fun provideBleDiscoveryController(
        radio: BleRadio,
        localBeacon: BleBeacon,
        scope: CoroutineScope,
    ): BleDiscoveryController =
        DefaultBleDiscoveryController(
            radio = radio,
            registry = DiscoveredBlePeerRegistry(clock = SystemClock::elapsedRealtime),
            scope = scope,
            localBeacon = localBeacon,
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MeshModule {

    /**
     * Real BLE discovery backs the Bluetooth transport and its peers; Wi-Fi,
     * LoRa, and Relay remain faked until those radios land.
     */
    @Binds
    @Singleton
    abstract fun bindMeshStateRepository(impl: HybridMeshStateRepository): MeshStateRepository
}
