package com.meshchats.app.core.transport.ble

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * The user's persisted intent for whether BLE discovery is allowed to run.
 *
 * [loaded] guards against acting before the stored value has been read: on a
 * cold start the preference is momentarily unknown, and treating "unknown" as
 * "enabled" would let a device that the user turned OFF briefly start
 * advertising/scanning on every process restart. So the initial, pre-read state
 * is `loaded = false, enabled = false`, and callers must wait for [loaded] before
 * starting discovery.
 */
data class BleDiscoveryPreference(
    val loaded: Boolean,
    val enabled: Boolean,
)

/**
 * Persists and observes the user's BLE-discovery intent, independent of the
 * radio lifecycle. The stored value survives process death; discovery honours
 * it via the ViewModel and the mesh repository so the UI stays truthful about
 * whether Bluetooth is participating in the mesh.
 */
interface BleDiscoverySettings {

    /** The current preference; starts un-[BleDiscoveryPreference.loaded]. */
    val state: StateFlow<BleDiscoveryPreference>

    /** Persist the user's intent. Absent key defaults to enabled. */
    suspend fun setEnabled(enabled: Boolean)
}

/**
 * [BleDiscoverySettings] backed by the app's shared [DataStore]. Reads the
 * stored key once on construction into [state] (flipping [loaded] true), then
 * keeps writing through [setEnabled]. Default when the key is absent is
 * **enabled**, so existing installs light up BLE as before; only an explicit
 * OFF is remembered.
 *
 * Reads are resilient: an [IOException] while reading the store (disk error, or
 * a corrupt file that slipped past the store's own corruption handler) is
 * swallowed and treated as absent preferences, so the flow still emits the
 * default and flips [loaded] true instead of crashing the app or wedging the UI
 * in a perpetual loading state. Non-IO throwables are genuinely unexpected and
 * propagate.
 */
@Singleton
class DataStoreBleDiscoverySettings @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : BleDiscoverySettings {

    private val _state = MutableStateFlow(BleDiscoveryPreference(loaded = false, enabled = false))
    override val state: StateFlow<BleDiscoveryPreference> = _state.asStateFlow()

    init {
        scope.launch {
            dataStore.data
                // A read failure must not crash or hang the app. Emit empty
                // preferences so the default below applies and [loaded] flips
                // true. Corruption is normally handled at the store level; this
                // covers residual IO faults (e.g. a failing disk).
                .catch { cause ->
                    if (cause is IOException) emit(emptyPreferences()) else throw cause
                }
                .collect { prefs ->
                    _state.value = BleDiscoveryPreference(
                        loaded = true,
                        enabled = prefs[KEY_ENABLED] ?: DEFAULT_ENABLED,
                    )
                }
        }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_ENABLED] = enabled }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("ble_discovery_enabled")
        const val DEFAULT_ENABLED = true
    }
}
