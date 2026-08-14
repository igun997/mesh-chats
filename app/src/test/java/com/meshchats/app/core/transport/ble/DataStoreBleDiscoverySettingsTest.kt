package com.meshchats.app.core.transport.ble

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the persistence contract for the BLE discovery preference: a fresh store
 * defaults to enabled, an explicit OFF survives being read back by a new
 * instance, and the pre-read state never reports enabled (so a stored OFF can't
 * briefly start discovery on a cold start).
 *
 * Uses a real file-backed [PreferenceDataStoreFactory] under a [TemporaryFolder]
 * on the [TestScope]. DataStore does its own IO, so tests await the observable
 * [BleDiscoverySettings.state] (via [first]) rather than pumping virtual time,
 * which would race the real read/write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreBleDiscoverySettingsTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun TestScope.newDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
        ) { tmpFolder.newFile("ble_prefs_${System.nanoTime()}.preferences_pb") }

    /**
     * A store over [file] using the same [ReplaceFileCorruptionHandler] the app
     * wires in production, so the corruption-recovery test exercises the real
     * path rather than a stand-in.
     */
    private fun TestScope.storeWithCorruptionHandler(file: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = backgroundScope,
        ) { file }

    /** A [DataStore] whose read flow always fails with an [IOException]. */
    private class ThrowingDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("disk gone") }
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = throw IOException("disk gone")
    }

    @Test
    fun `initial state is not loaded and not enabled`() = runTest {
        val store = newDataStore()

        val settings = DataStoreBleDiscoverySettings(store, backgroundScope)
        // Read before the collector runs: the true cold-start state that a
        // stored OFF must never escape as an accidental enabled=true.
        val initial = settings.state.value

        assertFalse("must not report loaded before the first read", initial.loaded)
        assertFalse("must not report enabled before the first read", initial.enabled)
    }

    @Test
    fun `defaults to enabled when no value is stored`() = runTest {
        val store = newDataStore()

        val settings = DataStoreBleDiscoverySettings(store, backgroundScope)

        val state = settings.state.first { it.loaded }
        assertTrue("absent key must default to enabled", state.enabled)
    }

    @Test
    fun `persists a disabled preference`() = runTest {
        val store = newDataStore()
        val settings = DataStoreBleDiscoverySettings(store, backgroundScope)
        settings.state.first { it.loaded }

        settings.setEnabled(false)

        assertEquals(
            BleDiscoveryPreference(loaded = true, enabled = false),
            settings.state.first { it.loaded && !it.enabled },
        )
    }

    @Test
    fun `restores a disabled preference in a fresh instance`() = runTest {
        val store = newDataStore()

        val first = DataStoreBleDiscoverySettings(store, backgroundScope)
        first.state.first { it.loaded }
        first.setEnabled(false)
        first.state.first { it.loaded && !it.enabled }

        // A new instance over the same store reads the persisted OFF.
        val restored = DataStoreBleDiscoverySettings(store, backgroundScope)

        assertEquals(
            BleDiscoveryPreference(loaded = true, enabled = false),
            restored.state.first { it.loaded },
        )
    }

    @Test
    fun `re-enabling persists true`() = runTest {
        val store = newDataStore()
        val settings = DataStoreBleDiscoverySettings(store, backgroundScope)
        settings.state.first { it.loaded }

        settings.setEnabled(false)
        settings.state.first { it.loaded && !it.enabled }
        settings.setEnabled(true)

        assertTrue(settings.state.first { it.loaded && it.enabled }.enabled)
    }

    @Test
    fun `recovers to default enabled when the stored file is corrupt`() = runTest {
        // Pre-corrupt the backing file with bytes that are not a valid
        // preferences proto, then open a store with the production corruption
        // handler over it: the read must recover to empty prefs, so the setting
        // loads the default ON instead of crashing.
        val file = tmpFolder.newFile("corrupt_${System.nanoTime()}.preferences_pb")
        file.writeBytes(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))

        val store = storeWithCorruptionHandler(file)
        val settings = DataStoreBleDiscoverySettings(store, backgroundScope)

        val state = settings.state.first { it.loaded }
        assertTrue("corrupt file must recover to the default enabled", state.enabled)
    }

    @Test
    fun `recovers to default enabled when the read flow throws IOException`() = runTest {
        // A store whose data flow fails with an IOException must not crash or
        // wedge the setting in a perpetual pre-loaded state: it recovers to the
        // default enabled and flips loaded true.
        val settings = DataStoreBleDiscoverySettings(ThrowingDataStore(), backgroundScope)

        val state = settings.state.first { it.loaded }
        assertTrue("an IO read failure must recover to the default enabled", state.enabled)
    }
}
