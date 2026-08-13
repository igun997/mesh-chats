package com.meshchats.app.core.transport.ble

/**
 * The observable state of BLE peer discovery.
 *
 * The controller starts in [Idle], moves to [Scanning] once advertising and
 * scanning are running, and falls into one of the terminal-precondition states
 * ([Unsupported], [PermissionRequired], [BluetoothOff]) or [Error] when it
 * cannot proceed. States are plain immutable values so the UI can render them
 * directly and tests can assert on them without touching the radio.
 */
sealed interface BleDiscoveryState {

    /** This device has no BLE hardware capable of advertising + scanning. */
    data object Unsupported : BleDiscoveryState

    /** Discovery needs the given runtime [permissions] granted before it can run. */
    data class PermissionRequired(val permissions: Set<String>) : BleDiscoveryState

    /** BLE hardware exists and is permitted, but the adapter is turned off. */
    data object BluetoothOff : BleDiscoveryState

    /** Discovery has not been started, or was stopped. */
    data object Idle : BleDiscoveryState

    /** Discovery is running; [peers] is the current set of live peers. */
    data class Scanning(val peers: List<DiscoveredBlePeer>) : BleDiscoveryState

    /**
     * Discovery could not run or was torn down after a radio failure. [message]
     * is a short, bounded, non-sensitive description safe to surface in the UI.
     */
    data class Error(val message: String) : BleDiscoveryState
}
