package com.meshchats.app.data.local

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fast, Room-free proof of the address / id bounds enforced before the blocking
 * Signal store touches the database. The exact `require` calls these tests
 * exercise run inside [RoomSignalProtocolStore] before any DB access, so proving
 * them here covers the input-rejection path without a device.
 *
 * Bounds are deliberately loose enough to admit every id libsignal's `KeyHelper`
 * produces (positive, in-range) and the app's stable fingerprint address names,
 * but reject the fail-fast garbage — non-positive ids, empty names, and
 * unbounded name blobs — before it can reach an encrypted row.
 */
class SignalStoreValidationTest {

    @Test
    fun typicalFingerprintAddressNameIsAccepted() {
        SignalStoreValidation.requireValidAddressName("a".repeat(64))
    }

    @Test
    fun singleCharAddressNameIsAccepted() {
        SignalStoreValidation.requireValidAddressName("x")
    }

    @Test
    fun maxLengthAddressNameIsAccepted() {
        SignalStoreValidation.requireValidAddressName("y".repeat(SignalStoreValidation.MAX_NAME_LENGTH))
    }

    @Test
    fun emptyAddressNameIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalStoreValidation.requireValidAddressName("")
        }
    }

    @Test
    fun oversizeAddressNameIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalStoreValidation.requireValidAddressName("z".repeat(SignalStoreValidation.MAX_NAME_LENGTH + 1))
        }
    }

    @Test
    fun primaryDeviceIdIsAccepted() {
        SignalStoreValidation.requireValidDeviceId(1)
    }

    @Test
    fun linkedDeviceIdIsAccepted() {
        SignalStoreValidation.requireValidDeviceId(42)
    }

    @Test
    fun zeroDeviceIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalStoreValidation.requireValidDeviceId(0)
        }
    }

    @Test
    fun negativeDeviceIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalStoreValidation.requireValidDeviceId(-1)
        }
    }

    @Test
    fun typicalKeyIdIsAccepted() {
        SignalStoreValidation.requireValidKeyId(1)
        SignalStoreValidation.requireValidKeyId(2_147_483_646)
    }

    @Test
    fun zeroKeyIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalStoreValidation.requireValidKeyId(0)
        }
    }

    @Test
    fun negativeKeyIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalStoreValidation.requireValidKeyId(-7)
        }
    }
}
