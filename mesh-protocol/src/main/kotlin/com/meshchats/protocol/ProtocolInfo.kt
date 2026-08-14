package com.meshchats.protocol

/** Static metadata about the shared mesh protocol module. */
object ProtocolInfo {
    const val NAME: String = "mesh-protocol"

    /**
     * Wire protocol version encoded in every packet frame. Bump only for a
     * breaking change to the on-wire layout; decoders reject unknown versions.
     */
    const val WIRE_VERSION: Int = 1
}
