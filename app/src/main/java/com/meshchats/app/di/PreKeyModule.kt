package com.meshchats.app.di

import com.meshchats.app.crypto.identity.DeviceIdentityRepository
import com.meshchats.app.crypto.prekey.DefaultSignalPreKeyManager
import com.meshchats.app.crypto.prekey.LibsignalKeyMaterialFactory
import com.meshchats.app.crypto.prekey.PreKeyIdGenerator
import com.meshchats.app.crypto.prekey.SignalKeyMaterialFactory
import com.meshchats.app.crypto.prekey.SignalPreKeyManager
import com.meshchats.app.crypto.prekey.SignalTransactionRunner
import com.meshchats.app.data.local.MeshDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.security.SecureRandom
import java.util.concurrent.Callable
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Wires the PQXDH prekey inventory manager.
 *
 * The crypto dispatcher is single-parallelism (`Dispatchers.IO.limitedParallelism(1)`)
 * so every libsignal native call and Signal Room access is serialized onto one
 * worker — libsignal's store callbacks are synchronous and its native sessions are
 * not safe to drive concurrently. The transaction runner wraps
 * `MeshDatabase.runInTransaction` so a whole inventory batch commits atomically.
 */
@Module
@InstallIn(SingletonComponent::class)
object PreKeyModule {

    @Provides
    @Singleton
    @SignalCryptoDispatcher
    fun provideSignalCryptoDispatcher(): CoroutineDispatcher =
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.IO.limitedParallelism(1)

    @Provides
    @Singleton
    fun provideSignalKeyMaterialFactory(): SignalKeyMaterialFactory = LibsignalKeyMaterialFactory()

    @Provides
    @Singleton
    fun provideSignalTransactionRunner(database: MeshDatabase): SignalTransactionRunner =
        object : SignalTransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T =
                database.runInTransaction(Callable { block() })
        }

    @Provides
    @Singleton
    fun provideSignalPreKeyManager(
        identityRepository: DeviceIdentityRepository,
        database: MeshDatabase,
        factory: SignalKeyMaterialFactory,
        transactionRunner: SignalTransactionRunner,
        @SignalCryptoDispatcher dispatcher: CoroutineDispatcher,
    ): SignalPreKeyManager =
        DefaultSignalPreKeyManager(
            identityRepository = identityRepository,
            dao = database.blockingSignalStoreDao(),
            factory = factory,
            // SecureRandom-backed positive-id source in production; tests inject a
            // deterministic source.
            idGenerator = PreKeyIdGenerator(randomInt = SecureRandom()::nextInt),
            transactionRunner = transactionRunner,
            dispatcher = dispatcher,
        )
}
