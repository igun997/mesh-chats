package com.meshchats.app.data.local

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore

/**
 * SQLCipher/Room-backed implementation of libsignal 0.100.0's protocol stores.
 *
 * Implements exactly five libsignal store interfaces — [IdentityKeyStore],
 * [SessionStore], [PreKeyStore], [SignedPreKeyStore], [KyberPreKeyStore] — and
 * deliberately **not** `SignalProtocolStore`, because that also requires the group
 * `SenderKeyStore` (out of scope; 1:1 sessions only for now). All methods are
 * synchronous because libsignal's native code invokes these callbacks
 * synchronously and expects a value back on the same thread.
 *
 * ## Threading contract
 * Every method here calls [BlockingSignalStoreDao] blocking queries. They MUST be
 * invoked only:
 *  1. off the main thread (Room enforces this — no `allowMainThreadQueries`);
 *  2. on the dedicated single-parallelism crypto dispatcher; and
 *  3. inside an outer Room transaction so a multi-callback libsignal operation
 *     (e.g. decrypt → load session → consume prekey → store session) is atomic.
 * Only obligation (1) is enforced here (by Room). Obligations (2) and (3) are
 * caller contracts owned and enforced/tested by the engine layer (a later task);
 * this class does not open its own transactions or bridge coroutines and cannot
 * prove either from inside a callback.
 *
 * ## Record parse failures
 * Every deserialization of an opaque libsignal blob (`SessionRecord(bytes)`,
 * `PreKeyRecord(bytes)`, `IdentityKey(bytes)`, …) is guarded and mapped to a
 * bounded [SignalStoreException] with [SignalStoreReason.CORRUPT_RECORD]. Parse
 * failures surface as libsignal's declared checked exceptions —
 * [InvalidMessageException] for the `*Record` blobs and [InvalidKeyException] for
 * the identity blobs — which are the exact contract for malformed serialized
 * input. Catches are scoped to those types so JVM [Error]s (OOM, stack overflow)
 * and unrelated runtime bugs propagate instead of being masked as corruption.
 *
 * ## Semantics (mirrors libsignal's official InMemory stores)
 *  - Local identity parsed from the singleton row; missing/corrupt surfaces a
 *    bounded [SignalStoreException] carrying no secret bytes.
 *  - Unknown remote identity is trusted (TOFU); a known exact match is trusted; a
 *    known but changed key is untrusted. `saveIdentity` always upserts and returns
 *    `NEW_OR_UNCHANGED` when the stored key was absent or identical, else
 *    `REPLACED_EXISTING`.
 *  - `loadSession` returns null when absent; bulk `loadExistingSessions` returns
 *    records in input order and throws [NoSessionException] if any is absent.
 *    `getSubDeviceSessions` returns sub-device ids ascending, excluding device 1.
 *  - Missing pre/signed/Kyber keys throw [InvalidKeyIdException]; a parse-corrupt
 *    record throws [SignalStoreException].
 *  - Only `record.serialize()` defensive bytes plus timestamps/schemaVersion are
 *    persisted; the store never logs or stringifies a record or key blob.
 *  - Kyber prekey replacement preserves existing `used` / `last_resort` metadata.
 *    `markKyberPreKeyUsed` delegates to the atomic v3 replay op: MARKED →ok,
 *    REUSED →[ReusedBaseKeyException], MISSING →[InvalidKeyIdException].
 *
 * App-internal adapter; no UI/transport code imports libsignal through it.
 */
class RoomSignalProtocolStore(
    private val dao: BlockingSignalStoreDao,
    private val schemaVersion: Int = 1,
    private val now: () -> Long = { System.currentTimeMillis() },
) : IdentityKeyStore, SessionStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore {

    // === IdentityKeyStore ==================================================

    override fun getIdentityKeyPair(): IdentityKeyPair {
        val row = dao.localIdentity() ?: throw SignalStoreException(SignalStoreReason.MISSING_LOCAL_IDENTITY)
        return try {
            IdentityKeyPair(row.identityKeyPair)
        } catch (_: Exception) {
            // libsignal's JNI boundary does not always honor checked exceptions
            // declared by Java wrappers (SessionRecord, for example, may throw
            // unchecked InvalidSessionException). Catch Exception—not Throwable—
            // at stored-record parse boundaries so failures stay bounded while
            // JVM Errors still propagate.
            throw SignalStoreException(SignalStoreReason.CORRUPT_RECORD)
        }
    }

    override fun getLocalRegistrationId(): Int {
        val row = dao.localIdentity() ?: throw SignalStoreException(SignalStoreReason.MISSING_LOCAL_IDENTITY)
        return row.registrationId
    }

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val name = address.name
        val deviceId = address.deviceId
        SignalStoreValidation.requireValidAddressName(name)
        SignalStoreValidation.requireValidDeviceId(deviceId)

        val existing = dao.trustedIdentity(name, deviceId)
        dao.upsertTrustedIdentity(
            SignalTrustedIdentityEntity(
                name = name,
                deviceId = deviceId,
                identityKey = identityKey.serialize(),
                schemaVersion = schemaVersion,
                updatedAt = now(),
            ),
        )
        // NEW_OR_UNCHANGED when there was no prior key or it is byte-identical;
        // REPLACED_EXISTING when a different key was already stored. Compare on the
        // opaque serialized bytes so a corrupt stored blob is simply "different".
        val unchanged = existing == null || existing.identityKey.contentEquals(identityKey.serialize())
        return if (unchanged) {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        } else {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        SignalStoreValidation.requireValidAddressName(address.name)
        SignalStoreValidation.requireValidDeviceId(address.deviceId)

        val existing = dao.trustedIdentity(address.name, address.deviceId)
            // Unknown identity: trust on first use (TOFU), exactly as InMemory.
            ?: return true
        // Known identity: trusted only if byte-identical to what we stored.
        return existing.identityKey.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        SignalStoreValidation.requireValidAddressName(address.name)
        SignalStoreValidation.requireValidDeviceId(address.deviceId)

        val row = dao.trustedIdentity(address.name, address.deviceId) ?: return null
        return try {
            IdentityKey(row.identityKey)
        } catch (_: Exception) {
            throw SignalStoreException(SignalStoreReason.CORRUPT_RECORD)
        }
    }

    // === SessionStore ======================================================

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? {
        SignalStoreValidation.requireValidAddressName(address.name)
        SignalStoreValidation.requireValidDeviceId(address.deviceId)

        val row = dao.session(address.name, address.deviceId) ?: return null
        return try {
            SessionRecord(row.record)
        } catch (_: Exception) {
            throw SignalStoreException(SignalStoreReason.CORRUPT_RECORD)
        }
    }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> {
        val out = ArrayList<SessionRecord>(addresses.size)
        for (address in addresses) {
            SignalStoreValidation.requireValidAddressName(address.name)
            SignalStoreValidation.requireValidDeviceId(address.deviceId)
            val row = dao.session(address.name, address.deviceId)
                // Bulk load is all-or-nothing on presence: any absent session is a
                // NoSessionException carrying the address and a bounded message.
                ?: throw NoSessionException(address, "no session for requested address")
            out += try {
                SessionRecord(row.record)
            } catch (_: Exception) {
                throw SignalStoreException(SignalStoreReason.CORRUPT_RECORD)
            }
        }
        return out
    }

    override fun getSubDeviceSessions(name: String): List<Int> {
        SignalStoreValidation.requireValidAddressName(name)
        // Ascending, excluding device 1 (the primary) — enforced by the query.
        return dao.subDeviceIds(name)
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        SignalStoreValidation.requireValidAddressName(address.name)
        SignalStoreValidation.requireValidDeviceId(address.deviceId)
        dao.upsertSession(
            SignalSessionEntity(
                name = address.name,
                deviceId = address.deviceId,
                record = record.serialize(),
                schemaVersion = schemaVersion,
                updatedAt = now(),
            ),
        )
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        SignalStoreValidation.requireValidAddressName(address.name)
        SignalStoreValidation.requireValidDeviceId(address.deviceId)
        return dao.sessionCount(address.name, address.deviceId) > 0
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        SignalStoreValidation.requireValidAddressName(address.name)
        SignalStoreValidation.requireValidDeviceId(address.deviceId)
        dao.deleteSession(address.name, address.deviceId)
    }

    override fun deleteAllSessions(name: String) {
        SignalStoreValidation.requireValidAddressName(name)
        dao.deleteAllSessions(name)
    }

    // === PreKeyStore =======================================================

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        SignalStoreValidation.requireValidKeyId(preKeyId)
        val row = dao.preKey(preKeyId) ?: throw InvalidKeyIdException("No such prekeyrecord!")
        return try {
            PreKeyRecord(row.record)
        } catch (_: Exception) {
            throw SignalStoreException(SignalStoreReason.CORRUPT_RECORD)
        }
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        SignalStoreValidation.requireValidKeyId(preKeyId)
        dao.upsertPreKey(
            SignalPreKeyEntity(
                preKeyId = preKeyId,
                record = record.serialize(),
                schemaVersion = schemaVersion,
                createdAt = now(),
            ),
        )
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        SignalStoreValidation.requireValidKeyId(preKeyId)
        return dao.preKeyCount(preKeyId) > 0
    }

    override fun removePreKey(preKeyId: Int) {
        SignalStoreValidation.requireValidKeyId(preKeyId)
        dao.deletePreKey(preKeyId)
    }

    // === SignedPreKeyStore =================================================

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        SignalStoreValidation.requireValidKeyId(signedPreKeyId)
        val row = dao.signedPreKey(signedPreKeyId) ?: throw InvalidKeyIdException("No such signedprekeyrecord!")
        return try {
            SignedPreKeyRecord(row.record)
        } catch (_: Exception) {
            throw SignalStoreException(SignalStoreReason.CORRUPT_RECORD)
        }
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        return dao.allSignedPreKeys().map { row ->
            try {
                SignedPreKeyRecord(row.record)
            } catch (_: Exception) {
                throw SignalStoreException(SignalStoreReason.CORRUPT_RECORD)
            }
        }
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        SignalStoreValidation.requireValidKeyId(signedPreKeyId)
        dao.upsertSignedPreKey(
            SignalSignedPreKeyEntity(
                signedPreKeyId = signedPreKeyId,
                record = record.serialize(),
                schemaVersion = schemaVersion,
                createdAt = now(),
            ),
        )
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        SignalStoreValidation.requireValidKeyId(signedPreKeyId)
        return dao.signedPreKeyCount(signedPreKeyId) > 0
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        SignalStoreValidation.requireValidKeyId(signedPreKeyId)
        dao.deleteSignedPreKey(signedPreKeyId)
    }

    // === KyberPreKeyStore ==================================================

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        SignalStoreValidation.requireValidKeyId(kyberPreKeyId)
        val row = dao.kyberPreKey(kyberPreKeyId) ?: throw InvalidKeyIdException("No such kyberprekeyrecord!")
        return try {
            KyberPreKeyRecord(row.record)
        } catch (_: Exception) {
            throw SignalStoreException(SignalStoreReason.CORRUPT_RECORD)
        }
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
        return dao.allKyberPreKeys().map { row ->
            try {
                KyberPreKeyRecord(row.record)
            } catch (_: Exception) {
                throw SignalStoreException(SignalStoreReason.CORRUPT_RECORD)
            }
        }
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        SignalStoreValidation.requireValidKeyId(kyberPreKeyId)
        // New rows default used=false, last_resort=false. Replacement preserves the
        // existing used/last_resort metadata (see the DAO's preserving upsert),
        // matching InMemory's independent used/base-key tracking.
        dao.storeKyberPreKeyPreservingMetadata(
            SignalKyberPreKeyEntity(
                kyberPreKeyId = kyberPreKeyId,
                record = record.serialize(),
                used = false,
                lastResort = false,
                schemaVersion = schemaVersion,
                createdAt = now(),
            ),
        )
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean {
        SignalStoreValidation.requireValidKeyId(kyberPreKeyId)
        return dao.kyberPreKeyCount(kyberPreKeyId) > 0
    }

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        SignalStoreValidation.requireValidKeyId(kyberPreKeyId)
        SignalStoreValidation.requireValidKeyId(signedPreKeyId)
        // Serialize the base key exactly; the DAO bounds-checks it before any write.
        val result = dao.markKyberUsedWithBaseKeyBlocking(
            kyberId = kyberPreKeyId,
            signedPreKeyId = signedPreKeyId,
            baseKey = baseKey.serialize(),
            now = now(),
        )
        when (result) {
            MarkKyberUsedResult.MARKED -> Unit
            MarkKyberUsedResult.REUSED -> throw ReusedBaseKeyException()
            MarkKyberUsedResult.MISSING -> throw InvalidKeyIdException("No such kyberprekeyrecord!")
        }
    }
}
