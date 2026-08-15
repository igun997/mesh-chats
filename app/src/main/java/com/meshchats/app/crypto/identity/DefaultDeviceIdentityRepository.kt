package com.meshchats.app.crypto.identity

import com.meshchats.app.crypto.AtomicSecretFile
import com.meshchats.app.crypto.SecretFileReadError
import com.meshchats.app.crypto.SecretFileReadResult
import com.meshchats.app.crypto.SecretFileWriteResult
import com.meshchats.app.crypto.SecretUnwrapError
import com.meshchats.app.crypto.SecretWrapper
import com.meshchats.app.crypto.UnwrapResult
import com.meshchats.app.crypto.WrapResult

/**
 * Platform-free implementation of [DeviceIdentityRepository]. All device-specific
 * concerns are injected as ports ([Ed25519Crypto], [SignalIdentityFactory],
 * [SecretWrapper], [AtomicSecretFile], [IdentityStore], [FingerprintHasher]), so
 * the entire create / reopen / verify / crash-recovery state machine is exercised
 * on the host JVM with fakes and proven on-device with the real backends.
 *
 * ## Create protocol and crash consistency
 *
 * Creation persists the wrapped identity-secret **file first**, then inserts the
 * device + Signal DB rows in one transaction. The file is the single source of
 * truth and carries every field of both rows (see [IdentitySecretPayload]), so a
 * crash at any point is recoverable:
 *
 * - crash before the file lands  -> no file, no rows: a clean first run, create;
 * - crash after the file lands, before the DB commit -> file present, rows absent:
 *   reopen verifies the file and **finalizes** the DB rows from it (no regeneration);
 * - normal steady state -> file present, rows present: reopen verifies both and
 *   cross-checks they agree.
 *
 * If the DB rows exist but the file is missing or unwrappable, the private keys
 * are gone: that is [DeviceIdentityError.KEY_LOST] / [DeviceIdentityError.TAMPERED],
 * never a reason to regenerate over the partial state.
 *
 * ## Concurrency
 *
 * A same-process monitor plus the file's cross-process creation lock
 * ([AtomicSecretFile.withCreationLock]) serialize create/reopen so two callers —
 * even in separate OS processes — cannot both mint an identity.
 */
class DefaultDeviceIdentityRepository(
    private val crypto: Ed25519Crypto,
    private val signalFactory: SignalIdentityFactory,
    private val wrapper: SecretWrapper,
    private val secretFile: AtomicSecretFile,
    private val store: IdentityStore,
    private val fourWords: FourWordFingerprint,
    private val hasher: FingerprintHasher = FingerprintHasher.Sha256,
    private val clock: () -> Long = System::currentTimeMillis,
    private val signalSchemaVersion: Int = 1,
) : DeviceIdentityRepository {

    private val monitor = Any()

    private companion object {
        /**
         * AEAD associated data binding the wrapped identity secret to its logical
         * purpose. Distinct from the database-key AAD, so an identity record can
         * never be unwrapped as a database-key record or vice versa.
         */
        val IDENTITY_AAD: ByteArray = "mesh-chats/identity-secret/v1".toByteArray(Charsets.US_ASCII)
    }

    override fun getOrCreateIdentity(): DeviceIdentityResult {
        // Serialize the ENTIRE create / recover / finalize path — not just first
        // creation — across threads AND OS processes. Two instances that open a
        // file-present/DB-absent crash state would otherwise BOTH run the finalize
        // insert concurrently and conflict; under the shared lock they converge:
        // the first finalizes the DB, the second sees the rows present and simply
        // re-verifies. The same-process monitor guards the file lock itself
        // (a second overlapping OS lock in one JVM would throw).
        synchronized(monitor) {
            return secretFile.withCreationLock {
                if (secretFile.exists()) {
                    // A wrapped file is authoritative: recover and fully verify,
                    // finalizing the DB from it if a crash landed file-before-DB.
                    // Never regenerate.
                    recoverAndVerify()
                } else {
                    createNew()
                }
            }
        }
    }

    // ---- Creation ---------------------------------------------------------

    private fun createNew(): DeviceIdentityResult {
        // Defend against a half-created prior attempt: if DB rows already exist but
        // there is no wrapped file, the private keys are gone. Never regenerate.
        when (val existing = store.read()) {
            is IdentityReadResult.Failure -> return fail(DeviceIdentityError.DATABASE_UNAVAILABLE)
            is IdentityReadResult.Success -> {
                if (existing.identity.device != null || existing.identity.signal != null) {
                    return DeviceIdentityResult.Failure(DeviceIdentityError.KEY_LOST)
                }
            }
        }

        val keyPair = when (val g = crypto.generate()) {
            is Ed25519GenerateResult.Success -> g.keyPair
            is Ed25519GenerateResult.Failure -> return fail(DeviceIdentityError.CRYPTO_UNAVAILABLE)
        }

        val signal = when (val s = signalFactory.create()) {
            is SignalIdentityResult.Success -> s.identity
            is SignalIdentityResult.Failure -> {
                keyPair.privatePkcs8.fill(0)
                return fail(DeviceIdentityError.CRYPTO_UNAVAILABLE)
            }
        }

        // From here the Signal serialized key pair (a secret) is live. Zero the
        // ORIGINAL buffer on every exit — success and failure alike — once the
        // file write and DB insert below have consumed it. The wrapped file and
        // the SQLCipher row each hold their own protected copy, so zeroing this
        // in-memory original is safe and leaves no plaintext Signal private key
        // lingering on the heap after creation.
        return try {
            val fingerprint = hasher.fingerprint(keyPair.publicX509)
            val bindingVersion = IdentityBinding.BINDING_VERSION
            val bindingPayload = IdentityBinding.bindingPayload(
                version = bindingVersion,
                edPublicX509 = keyPair.publicX509,
                signalPublicBytes = signal.publicIdentityBytes,
            )
            val bindingSignature = when (val sig = crypto.sign(keyPair.privatePkcs8, bindingPayload)) {
                is Ed25519SignResult.Success -> sig.signature
                is Ed25519SignResult.Failure -> {
                    keyPair.privatePkcs8.fill(0)
                    return fail(DeviceIdentityError.CRYPTO_UNAVAILABLE)
                }
            }

            val createdAt = clock()

            // 1) Wrap and durably persist the identity secret FIRST. It carries every
            //    field of both DB rows, so a crash after this point is finalizable.
            val payload = IdentitySecretPayload(
                version = IdentitySecretCodec.VERSION,
                privatePkcs8 = keyPair.privatePkcs8.copyOf(),
                edPublicX509 = keyPair.publicX509,
                fingerprintSha256 = fingerprint,
                signalPublicBinding = signal.publicIdentityBytes,
                bindingSignature = bindingSignature,
                bindingVersion = bindingVersion,
                signalRegistrationId = signal.registrationId,
                signalSerializedKeyPair = signal.serializedKeyPair.copyOf(),
                signalSchemaVersion = signalSchemaVersion,
                createdAt = createdAt,
            )
            keyPair.privatePkcs8.fill(0)
            val persisted = persistSecret(payload)
            if (persisted != null) {
                payload.zeroPrivate()
                return DeviceIdentityResult.Failure(persisted)
            }

            // 2) Insert both DB rows atomically. On failure the wrapped file already
            //    exists; the next open finalizes the DB rows from it (crash recovery).
            val device = StoredDeviceIdentity(
                publicKeyX509 = payload.edPublicX509,
                fingerprintSha256 = fingerprint,
                createdAt = createdAt,
                signalPublicBinding = signal.publicIdentityBytes,
                signalBindingSignature = bindingSignature,
                bindingVersion = bindingVersion,
            )
            val signalRow = StoredSignalIdentity(
                registrationId = signal.registrationId,
                // Hand the store its OWN copy: the original in-memory buffer is
                // zeroed in the finally below, so the row must not alias it.
                serializedKeyPair = signal.serializedKeyPair.copyOf(),
                schemaVersion = signalSchemaVersion,
                createdAt = createdAt,
            )
            val writeResult = store.insertBoth(device, signalRow)
            payload.zeroPrivate()
            if (writeResult is IdentityWriteResult.Failure) {
                return DeviceIdentityResult.Failure(DeviceIdentityError.DATABASE_UNAVAILABLE)
            }

            DeviceIdentityResult.Success(
                toDeviceIdentity(
                    edPublicX509 = device.publicKeyX509,
                    fingerprint = fingerprint,
                    signalPublicBinding = signal.publicIdentityBytes,
                    bindingSignature = bindingSignature,
                    bindingVersion = bindingVersion,
                    createdAt = createdAt,
                ),
            )
        } finally {
            // The DB insert (and file write) have copied the secret into their own
            // protected stores; zero the in-memory original last, on every path.
            signal.serializedKeyPair.fill(0)
        }
    }

    // ---- Recovery + full verification ------------------------------------

    private fun recoverAndVerify(): DeviceIdentityResult {
        val payload = when (val r = loadAndVerifySecret()) {
            is SecretLoad.Ok -> r.payload
            is SecretLoad.Fail -> return DeviceIdentityResult.Failure(r.error)
        }

        // Read the DB rows. If absent, this is a crash between file-write and
        // DB-commit: finalize the DB from the verified file. Never regenerate.
        val stored = when (val s = store.read()) {
            is IdentityReadResult.Success -> s.identity
            is IdentityReadResult.Failure -> {
                payload.zeroPrivate()
                return DeviceIdentityResult.Failure(DeviceIdentityError.DATABASE_UNAVAILABLE)
            }
        }

        if (stored.device == null || stored.signal == null) {
            val result = finalizeDbFromPayload(payload)
            payload.zeroPrivate()
            return result
        }

        // The stored DB device row must be consistent with the verified secret.
        if (!stored.device.publicKeyX509.contentEquals(payload.edPublicX509) ||
            !stored.device.fingerprintSha256.contentEquals(payload.fingerprintSha256) ||
            !stored.device.signalBindingSignature.contentEquals(payload.bindingSignature) ||
            !stored.device.signalPublicBinding.contentEquals(payload.signalPublicBinding)
        ) {
            payload.zeroPrivate()
            return DeviceIdentityResult.Failure(DeviceIdentityError.TAMPERED)
        }

        // The stored Signal row must parse and its public identity bytes must match
        // the bound Signal public in the verified secret.
        val signalParsed = when (
            val p = signalFactory.parse(stored.signal.serializedKeyPair, stored.signal.registrationId)
        ) {
            is SignalIdentityResult.Success -> p.identity
            is SignalIdentityResult.Failure -> {
                payload.zeroPrivate()
                return DeviceIdentityResult.Failure(DeviceIdentityError.TAMPERED)
            }
        }
        if (!signalParsed.publicIdentityBytes.contentEquals(payload.signalPublicBinding)) {
            payload.zeroPrivate()
            return DeviceIdentityResult.Failure(DeviceIdentityError.TAMPERED)
        }

        val identity = toDeviceIdentity(
            edPublicX509 = payload.edPublicX509,
            fingerprint = payload.fingerprintSha256,
            signalPublicBinding = payload.signalPublicBinding,
            bindingSignature = payload.bindingSignature,
            bindingVersion = payload.bindingVersion,
            createdAt = stored.device.createdAt,
        )
        payload.zeroPrivate()
        return DeviceIdentityResult.Success(identity)
    }

    /**
     * Rebuilds and commits the DB rows from a fully verified secret payload. Used
     * only on the crash-recovery path (file present, DB rows absent). Because the
     * file carries the Signal serialized key pair and registration id, the whole
     * identity is reconstructed without regenerating any key.
     */
    private fun finalizeDbFromPayload(payload: IdentitySecretPayload): DeviceIdentityResult {
        val device = StoredDeviceIdentity(
            publicKeyX509 = payload.edPublicX509,
            fingerprintSha256 = payload.fingerprintSha256,
            createdAt = payload.createdAt,
            signalPublicBinding = payload.signalPublicBinding,
            signalBindingSignature = payload.bindingSignature,
            bindingVersion = payload.bindingVersion,
        )
        val signalRow = StoredSignalIdentity(
            registrationId = payload.signalRegistrationId,
            serializedKeyPair = payload.signalSerializedKeyPair.copyOf(),
            schemaVersion = payload.signalSchemaVersion,
            createdAt = payload.createdAt,
        )
        return when (store.insertBoth(device, signalRow)) {
            is IdentityWriteResult.Success -> DeviceIdentityResult.Success(
                toDeviceIdentity(
                    edPublicX509 = payload.edPublicX509,
                    fingerprint = payload.fingerprintSha256,
                    signalPublicBinding = payload.signalPublicBinding,
                    bindingSignature = payload.bindingSignature,
                    bindingVersion = payload.bindingVersion,
                    createdAt = payload.createdAt,
                ),
            )
            is IdentityWriteResult.Failure ->
                DeviceIdentityResult.Failure(DeviceIdentityError.DATABASE_UNAVAILABLE)
        }
    }

    // ---- Secret load + full crypto verification --------------------------

    private sealed interface SecretLoad {
        data class Ok(val payload: IdentitySecretPayload) : SecretLoad
        data class Fail(val error: DeviceIdentityError) : SecretLoad
    }

    /**
     * Reads, unwraps, decodes, and fully verifies the identity-secret file:
     * derives and matches the public key, recomputes and matches the fingerprint,
     * and verifies the binding signature over the canonical binding. Returns a
     * verified payload (private bytes intact — caller must zero) or a bounded
     * failure.
     */
    private fun loadAndVerifySecret(): SecretLoad {
        val record = when (val r = secretFile.read()) {
            is SecretFileReadResult.Success -> r
            is SecretFileReadResult.Failure -> return SecretLoad.Fail(
                when (r.error) {
                    SecretFileReadError.CORRUPT, SecretFileReadError.UNSAFE_PATH -> DeviceIdentityError.TAMPERED
                    SecretFileReadError.IO_FAILED -> DeviceIdentityError.STORAGE_FAILED
                    SecretFileReadError.NOT_FOUND -> DeviceIdentityError.STORAGE_FAILED
                },
            )
        }

        val plaintext = when (val u = wrapper.unwrap(record.nonce, record.ciphertext, IDENTITY_AAD)) {
            is UnwrapResult.Success -> u.plaintext
            is UnwrapResult.Failure -> return SecretLoad.Fail(
                when (u.error) {
                    SecretUnwrapError.KEY_LOST -> DeviceIdentityError.KEY_LOST
                    SecretUnwrapError.TAMPERED -> DeviceIdentityError.TAMPERED
                    SecretUnwrapError.UNWRAP_FAILED -> DeviceIdentityError.WRAPPER_UNAVAILABLE
                },
            )
        }

        val payload = when (val d = IdentitySecretCodec.decode(plaintext)) {
            is IdentitySecretDecodeResult.Success -> d.payload
            is IdentitySecretDecodeResult.Failure -> {
                plaintext.fill(0)
                return SecretLoad.Fail(DeviceIdentityError.TAMPERED)
            }
        }
        plaintext.fill(0)

        // (a) The recovered private key must derive the stored public key.
        val derived = when (val d = crypto.derivePublic(payload.privatePkcs8)) {
            is Ed25519DeriveResult.Success -> d.publicX509
            is Ed25519DeriveResult.Failure -> {
                payload.zeroPrivate()
                return SecretLoad.Fail(DeviceIdentityError.TAMPERED)
            }
        }
        if (!derived.contentEquals(payload.edPublicX509)) {
            payload.zeroPrivate()
            return SecretLoad.Fail(DeviceIdentityError.TAMPERED)
        }

        // (b) The fingerprint must match SHA-256 of the public key.
        val recomputed = hasher.fingerprint(payload.edPublicX509)
        if (!recomputed.contentEquals(payload.fingerprintSha256)) {
            payload.zeroPrivate()
            return SecretLoad.Fail(DeviceIdentityError.TAMPERED)
        }

        // (c) The binding signature must verify over the canonical binding.
        val bindingPayload = IdentityBinding.bindingPayload(
            version = payload.bindingVersion,
            edPublicX509 = payload.edPublicX509,
            signalPublicBytes = payload.signalPublicBinding,
        )
        val bindingOk = when (
            val v = crypto.verify(payload.edPublicX509, bindingPayload, payload.bindingSignature)
        ) {
            is Ed25519VerifyResult.Success -> v.valid
            is Ed25519VerifyResult.Failure -> {
                payload.zeroPrivate()
                return SecretLoad.Fail(DeviceIdentityError.TAMPERED)
            }
        }
        if (!bindingOk) {
            payload.zeroPrivate()
            return SecretLoad.Fail(DeviceIdentityError.TAMPERED)
        }

        return SecretLoad.Ok(payload)
    }

    // ---- Signing / verifying ---------------------------------------------

    override fun sign(message: ByteArray): IdentitySignResult {
        // Frame first (and bound-check) before touching any secret: an over-bound
        // message is refused with a typed error and never truncated to fit.
        val wrapped = when (val f = IdentityBinding.messagePayload(message)) {
            is IdentityBinding.MessagePayloadResult.Success -> f.bytes
            IdentityBinding.MessagePayloadResult.TooLarge ->
                return IdentitySignResult.Failure(DeviceIdentityError.MESSAGE_TOO_LARGE)
        }
        val payload = when (val r = loadAndVerifySecret()) {
            is SecretLoad.Ok -> r.payload
            is SecretLoad.Fail -> return IdentitySignResult.Failure(r.error)
        }
        return try {
            when (val s = crypto.sign(payload.privatePkcs8, wrapped)) {
                is Ed25519SignResult.Success -> IdentitySignResult.Success(s.signature)
                is Ed25519SignResult.Failure -> IdentitySignResult.Failure(DeviceIdentityError.CRYPTO_UNAVAILABLE)
            }
        } finally {
            payload.zeroPrivate()
        }
    }

    override fun verify(publicKeyX509: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        // A message too large to have been framed by sign() cannot have a valid
        // signature under this domain: fail closed to false, never throw.
        val wrapped = when (val f = IdentityBinding.messagePayload(message)) {
            is IdentityBinding.MessagePayloadResult.Success -> f.bytes
            IdentityBinding.MessagePayloadResult.TooLarge -> return false
        }
        return when (val v = crypto.verify(publicKeyX509, wrapped, signature)) {
            is Ed25519VerifyResult.Success -> v.valid
            is Ed25519VerifyResult.Failure -> false
        }
    }

    // ---- QR ---------------------------------------------------------------

    override fun qrPayload(): DeviceIdentityResult = getOrCreateIdentity()

    override fun verifyScannedPayload(text: String): ScannedIdentityResult {
        val payload = when (val d = IdentityQrCodec.decode(text)) {
            is IdentityQrDecodeResult.Success -> d.payload
            is IdentityQrDecodeResult.Failure ->
                return ScannedIdentityResult.Rejected(ScannedIdentityRejection.MALFORMED)
        }

        val recomputed = hasher.fingerprint(payload.edPublicX509)
        if (!recomputed.contentEquals(payload.fingerprintSha256)) {
            return ScannedIdentityResult.Rejected(ScannedIdentityRejection.FINGERPRINT_MISMATCH)
        }

        val bindingPayload = IdentityBinding.bindingPayload(
            version = payload.bindingVersion,
            edPublicX509 = payload.edPublicX509,
            signalPublicBytes = payload.signalPublicBinding,
        )
        return when (val v = crypto.verify(payload.edPublicX509, bindingPayload, payload.bindingSignature)) {
            is Ed25519VerifyResult.Success ->
                if (v.valid) {
                    ScannedIdentityResult.Verified(payload)
                } else {
                    ScannedIdentityResult.Rejected(ScannedIdentityRejection.SIGNATURE_INVALID)
                }
            is Ed25519VerifyResult.Failure ->
                ScannedIdentityResult.Rejected(ScannedIdentityRejection.VERIFY_FAILED)
        }
    }

    // ---- Helpers ----------------------------------------------------------

    /** Wraps [payload] and durably writes it; returns a bounded error or null on success. */
    private fun persistSecret(payload: IdentitySecretPayload): DeviceIdentityError? {
        val encoded = when (val e = IdentitySecretCodec.encode(payload)) {
            is IdentitySecretEncodeResult.Success -> e.bytes
            is IdentitySecretEncodeResult.Failure -> return DeviceIdentityError.STORAGE_FAILED
        }
        val wrapped = when (val w = wrapper.wrap(encoded, IDENTITY_AAD)) {
            is WrapResult.Success -> w.wrapped
            is WrapResult.Failure -> {
                encoded.fill(0)
                return DeviceIdentityError.WRAPPER_UNAVAILABLE
            }
        }
        encoded.fill(0)
        return when (secretFile.write(wrapped.nonce, wrapped.ciphertext)) {
            is SecretFileWriteResult.Success -> null
            is SecretFileWriteResult.Failure -> DeviceIdentityError.STORAGE_FAILED
        }
    }

    private fun toDeviceIdentity(
        edPublicX509: ByteArray,
        fingerprint: ByteArray,
        signalPublicBinding: ByteArray,
        bindingSignature: ByteArray,
        bindingVersion: Int,
        createdAt: Long,
    ): DeviceIdentity = DeviceIdentity(
        edPublicX509 = edPublicX509,
        fingerprintSha256 = fingerprint,
        signalPublicBinding = signalPublicBinding,
        bindingSignature = bindingSignature,
        bindingVersion = bindingVersion,
        createdAt = createdAt,
        fourWords = fourWords.words(fingerprint),
    )

    private fun fail(error: DeviceIdentityError) = DeviceIdentityResult.Failure(error)
}
