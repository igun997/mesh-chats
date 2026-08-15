package com.meshchats.app.crypto.identity

import org.bouncycastle.jcajce.interfaces.EdDSAPrivateKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Provider
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Production [Ed25519Crypto] backed by an **isolated** [BouncyCastleProvider]
 * instance.
 *
 * ## Provider isolation (security-critical)
 *
 * A single `BouncyCastleProvider` is constructed here and passed explicitly to
 * every `KeyPairGenerator`, `KeyFactory`, and `Signature` factory call. This code
 * never calls `Security.addProvider(...)` or `Security.insertProviderAt(...)`, so
 * it never mutates the process-global JCA provider list. On Android that global
 * list is the platform's Conscrypt/AndroidKeyStore set; globally inserting
 * Bouncy Castle could silently re-route platform TLS or Keystore operations
 * through BC and has caused real regressions. Explicit per-call provider
 * selection keeps this component's Ed25519 usage hermetic.
 *
 * The provider instance is a plain object with no static registration, so two
 * instances of this class never interfere and nothing outside sees it.
 *
 * The algorithm name `"Ed25519"` is resolved against the BC instance directly, so
 * it does not depend on any platform alias.
 */
class BouncyCastleEd25519Crypto(
    private val provider: Provider = BouncyCastleProvider(),
    private val secureRandom: SecureRandom = SecureRandom(),
) : Ed25519Crypto {

    private companion object {
        const val ALGORITHM = "Ed25519"
    }

    override fun generate(): Ed25519GenerateResult {
        return try {
            val kpg = KeyPairGenerator.getInstance(ALGORITHM, provider)
            kpg.initialize(255, secureRandom) // Ed25519 curve size; BC ignores value but keeps RNG.
            val kp = kpg.generateKeyPair()
            Ed25519GenerateResult.Success(
                Ed25519KeyPair(
                    privatePkcs8 = kp.private.encoded,
                    publicX509 = kp.public.encoded,
                ),
            )
        } catch (_: Exception) {
            Ed25519GenerateResult.Failure(Ed25519Error.PROVIDER_UNAVAILABLE)
        }
    }

    override fun derivePublic(privatePkcs8: ByteArray): Ed25519DeriveResult {
        val priv = try {
            val kf = KeyFactory.getInstance(ALGORITHM, provider)
            kf.generatePrivate(PKCS8EncodedKeySpec(privatePkcs8))
        } catch (_: Exception) {
            return Ed25519DeriveResult.Failure(Ed25519Error.INVALID_KEY)
        }
        val edPriv = priv as? EdDSAPrivateKey
            ?: return Ed25519DeriveResult.Failure(Ed25519Error.INVALID_KEY)
        return try {
            Ed25519DeriveResult.Success(edPriv.publicKey.encoded)
        } catch (_: Exception) {
            Ed25519DeriveResult.Failure(Ed25519Error.OPERATION_FAILED)
        }
    }

    override fun sign(privatePkcs8: ByteArray, message: ByteArray): Ed25519SignResult {
        if (message.size > Ed25519Crypto.MAX_MESSAGE_BYTES) {
            return Ed25519SignResult.Failure(Ed25519Error.INVALID_INPUT)
        }
        val priv = try {
            val kf = KeyFactory.getInstance(ALGORITHM, provider)
            kf.generatePrivate(PKCS8EncodedKeySpec(privatePkcs8))
        } catch (_: Exception) {
            return Ed25519SignResult.Failure(Ed25519Error.INVALID_KEY)
        }
        return try {
            val sig = Signature.getInstance(ALGORITHM, provider)
            sig.initSign(priv)
            // Defensive copy so a caller cannot mutate the buffer mid-operation.
            sig.update(message.copyOf())
            Ed25519SignResult.Success(sig.sign())
        } catch (_: Exception) {
            Ed25519SignResult.Failure(Ed25519Error.OPERATION_FAILED)
        }
    }

    override fun verify(
        publicX509: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Ed25519VerifyResult {
        if (message.size > Ed25519Crypto.MAX_MESSAGE_BYTES) {
            return Ed25519VerifyResult.Failure(Ed25519Error.INVALID_INPUT)
        }
        // A well-formed Ed25519 signature is exactly 64 bytes; a wrong-sized blob
        // is a non-match, not a fault.
        if (signature.size != Ed25519Crypto.SIGNATURE_BYTES) {
            return Ed25519VerifyResult.Success(false)
        }
        val pub = try {
            val kf = KeyFactory.getInstance(ALGORITHM, provider)
            kf.generatePublic(X509EncodedKeySpec(publicX509))
        } catch (_: Exception) {
            return Ed25519VerifyResult.Failure(Ed25519Error.INVALID_KEY)
        }
        return try {
            val sig = Signature.getInstance(ALGORITHM, provider)
            sig.initVerify(pub)
            sig.update(message.copyOf())
            Ed25519VerifyResult.Success(sig.verify(signature.copyOf()))
        } catch (_: Exception) {
            Ed25519VerifyResult.Failure(Ed25519Error.OPERATION_FAILED)
        }
    }
}
