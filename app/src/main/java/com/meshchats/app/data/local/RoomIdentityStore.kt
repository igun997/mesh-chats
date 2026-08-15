package com.meshchats.app.data.local

import com.meshchats.app.crypto.identity.IdentityReadResult
import com.meshchats.app.crypto.identity.IdentityStore
import com.meshchats.app.crypto.identity.IdentityStoreError
import com.meshchats.app.crypto.identity.IdentityWriteResult
import com.meshchats.app.crypto.identity.StoredDeviceIdentity
import com.meshchats.app.crypto.identity.StoredIdentity
import com.meshchats.app.crypto.identity.StoredSignalIdentity
import kotlinx.coroutines.runBlocking

/**
 * SQLCipher/Room-backed [IdentityStore]. Maps the platform-free identity shapes to
 * the [DeviceIdentityEntity] / [SignalIdentityEntity] rows and delegates the
 * all-or-nothing insert to [IdentityProvisioningDao.provisionIdentity].
 *
 * The identity repository is a synchronous port (it is called once during app
 * initialization, off the main thread), while Room DAOs are `suspend`. This
 * adapter bridges the two with [runBlocking] on the calling worker thread. That is
 * safe here because identity provisioning is a rare, short, initialization-time
 * operation that never runs on the main thread; it is not a hot path.
 *
 * Every Room/SQLite exception is collapsed to a bounded
 * [IdentityStoreError.STORAGE_FAILED]; no raw SQL exception escapes to the
 * repository, and no identity secret is logged (only opaque blobs pass through).
 */
class RoomIdentityStore(
    private val dao: IdentityProvisioningDao,
) : IdentityStore {

    override fun read(): IdentityReadResult {
        return try {
            runBlocking {
                val device = dao.getDevice()
                val signal = dao.getSignal()
                IdentityReadResult.Success(
                    StoredIdentity(
                        device = device?.let {
                            StoredDeviceIdentity(
                                publicKeyX509 = it.publicKeyX509,
                                fingerprintSha256 = it.fingerprintSha256,
                                createdAt = it.createdAt,
                                // The device row's binding columns are non-null once bound.
                                // A partially bound row (created without a Signal binding)
                                // is treated as absent binding via empty arrays; identity
                                // provisioning always writes them together, so in practice
                                // they are present here.
                                signalPublicBinding = it.signalPublicBinding ?: ByteArray(0),
                                signalBindingSignature = it.signalBindingSignature ?: ByteArray(0),
                                bindingVersion = it.bindingVersion,
                            )
                        },
                        signal = signal?.let {
                            StoredSignalIdentity(
                                registrationId = it.registrationId,
                                serializedKeyPair = it.identityKeyPair,
                                schemaVersion = it.schemaVersion,
                                createdAt = it.createdAt,
                            )
                        },
                    ),
                )
            }
        } catch (_: Throwable) {
            IdentityReadResult.Failure(IdentityStoreError.STORAGE_FAILED)
        }
    }

    override fun insertBoth(
        device: StoredDeviceIdentity,
        signal: StoredSignalIdentity,
    ): IdentityWriteResult {
        return try {
            runBlocking {
                dao.provisionIdentity(
                    device = DeviceIdentityEntity(
                        publicKeyX509 = device.publicKeyX509,
                        fingerprintSha256 = device.fingerprintSha256,
                        createdAt = device.createdAt,
                        signalPublicBinding = device.signalPublicBinding,
                        signalBindingSignature = device.signalBindingSignature,
                        bindingVersion = device.bindingVersion,
                    ),
                    signal = SignalIdentityEntity(
                        registrationId = signal.registrationId,
                        identityKeyPair = signal.serializedKeyPair,
                        schemaVersion = signal.schemaVersion,
                        createdAt = signal.createdAt,
                    ),
                )
            }
            IdentityWriteResult.Success
        } catch (_: Throwable) {
            IdentityWriteResult.Failure(IdentityStoreError.STORAGE_FAILED)
        }
    }
}
