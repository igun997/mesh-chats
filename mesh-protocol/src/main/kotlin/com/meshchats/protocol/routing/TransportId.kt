package com.meshchats.protocol.routing

/**
 * Radios/paths a frame can travel over.
 *
 * This lives in the shared protocol module so both the Android app and a future
 * relay reason about the same transports. Declaration order is significant: it
 * is the UI display order and is also used as a stable ordinal in route
 * fingerprints, so entries must only be appended, never reordered.
 *
 * [label] and [shortLabel] are plain display strings with no Android
 * dependency, kept here so a single transport definition serves UI and routing.
 */
enum class TransportId(val label: String, val shortLabel: String) {
    WIFI("Wi-Fi Aware / Direct", "WIFI"),
    BT("Bluetooth LE mesh", "BT"),
    LORA("LoRa radio", "LORA"),
    RELAY("Relay (global)", "RELAY"),
}
