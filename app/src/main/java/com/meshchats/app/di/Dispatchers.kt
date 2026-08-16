package com.meshchats.app.di

import javax.inject.Qualifier

/**
 * Marks the [kotlinx.coroutines.CoroutineDispatcher] used for blocking, disk- or
 * key-store-touching work (SQLCipher load, Keystore unwrap, plaintext migration,
 * the force-open at startup). Kept as a qualifier so tests can substitute a
 * controllable dispatcher and production code never hardcodes `Dispatchers.IO`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Marks the single-parallelism [kotlinx.coroutines.CoroutineDispatcher] on which
 * every libsignal native call and Signal Room store access runs. libsignal's
 * store callbacks are synchronous and its native operations are not thread-safe
 * across concurrent sessions, so all crypto work is confined to one worker
 * (`Dispatchers.IO.limitedParallelism(1)` in production). Kept as a qualifier so
 * tests can substitute a controllable dispatcher and production never hardcodes a
 * dispatcher. This is distinct from [IoDispatcher], which fans out general disk /
 * keystore work; the crypto dispatcher is deliberately serialized.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SignalCryptoDispatcher
