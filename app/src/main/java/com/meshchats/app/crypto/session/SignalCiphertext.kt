package com.meshchats.app.crypto.session

/**
 * The kind of serialized Signal message a [SignalCiphertext] carries.
 *
 * - [PREKEY]: a `PreKeySignalMessage` — the first message to a peer, which carries
 *   the sender's identity + prekey selection so the recipient can build the
 *   session. Maps to libsignal's `CiphertextMessage.PREKEY_TYPE`.
 * - [WHISPER]: a `SignalMessage` — a normal Double Ratchet message on an
 *   established session. Maps to libsignal's `CiphertextMessage.WHISPER_TYPE`.
 *
 * No other libsignal message type (sender-key, plaintext-content) is representable
 * here: this engine handles 1:1 sessions only.
 */
enum class SignalCiphertextType {
    PREKEY,
    WHISPER,
}

/**
 * An app-owned envelope over a serialized Signal message. It exposes only a
 * [SignalCiphertextType] and the opaque serialized [bytes], so transport code can
 * carry a ciphertext without importing any libsignal type.
 *
 * Bytes are copied on construction and every read so a holder cannot mutate the
 * stored ciphertext. [toString] reports the type and size only — never the bytes.
 */
class SignalCiphertext(
    val type: SignalCiphertextType,
    bytes: ByteArray,
) {
    private val messageBytes: ByteArray = bytes.copyOf()

    init {
        require(messageBytes.isNotEmpty()) { "ciphertext bytes must not be empty" }
    }

    /** Fresh copy of the serialized Signal message bytes. */
    val bytes: ByteArray get() = messageBytes.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalCiphertext) return false
        return type == other.type && messageBytes.contentEquals(other.messageBytes)
    }

    override fun hashCode(): Int = 31 * type.hashCode() + messageBytes.contentHashCode()

    /** Redacted summary: type and size only — never the ciphertext bytes. */
    override fun toString(): String = "SignalCiphertext(type=$type, bytes=${messageBytes.size}B)"
}
