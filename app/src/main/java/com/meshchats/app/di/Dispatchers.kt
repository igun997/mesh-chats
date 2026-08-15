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
