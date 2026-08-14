package com.meshchats.app.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A pure-JVM [SecretWrapper] backed by an in-memory AES-256-GCM key, standing in
 * for the Android Keystore in unit tests. It reproduces the real wrapper's
 * contract: AEAD with bound associated data, real tamper detection via the GCM
 * tag, and typed failures. [keyLost] simulates the wrapping key vanishing (device
 * credential reset) so key-loss handling can be tested off-device.
 */
class FakeSecretWrapper(
    keyBytes: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
) : SecretWrapper {

    private val key = SecretKeySpec(keyBytes, "AES")
    private val random = SecureRandom()

    /** When true, unwrap behaves as though the non-exportable key is gone. */
    var keyLost: Boolean = false

    /** When true, wrap reports the backing store as unavailable. */
    var wrapUnavailable: Boolean = false

    override fun wrap(plaintext: ByteArray, associatedData: ByteArray): WrapResult {
        if (wrapUnavailable) return WrapResult.Failure(SecretWrapError.KEY_UNAVAILABLE)
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(associatedData)
        val ct = cipher.doFinal(plaintext)
        return WrapResult.Success(WrappedSecret(nonce = nonce, ciphertext = ct))
    }

    override fun unwrap(nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): UnwrapResult {
        if (keyLost) return UnwrapResult.Failure(SecretUnwrapError.KEY_LOST)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(associatedData)
            UnwrapResult.Success(cipher.doFinal(ciphertext))
        } catch (_: AEADBadTagException) {
            UnwrapResult.Failure(SecretUnwrapError.TAMPERED)
        } catch (_: Exception) {
            UnwrapResult.Failure(SecretUnwrapError.UNWRAP_FAILED)
        }
    }
}
