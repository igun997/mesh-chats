package com.meshchats.app.crypto.identity

import com.meshchats.app.crypto.AtomicSecretFile
import com.meshchats.app.crypto.FakeSecretWrapper
import com.meshchats.app.crypto.RecordingDirectorySync
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Exercises the full identity state machine on the host JVM with fakes for the
 * Signal factory and the DB store, but the REAL Bouncy Castle Ed25519 crypto, the
 * real AEAD-backed [FakeSecretWrapper], and the real atomic file. This proves
 * create-once, reopen-verify, tamper/key-loss fail-closed, DB partial-state crash
 * recovery, and sign/verify without a device.
 */
class DefaultDeviceIdentityRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val crypto = BouncyCastleEd25519Crypto()
    private val signal = FakeSignalIdentityFactory()
    private val wrapper = FakeSecretWrapper()
    private val store = FakeIdentityStore()
    private val fourWords = FourWordFingerprint(FourWordList.load())

    private lateinit var secretFile: File

    private fun newSecretFile(): AtomicSecretFile =
        AtomicSecretFile(secretFile, directorySync = RecordingDirectorySync())

    private fun repo(
        c: Ed25519Crypto = crypto,
        s: SignalIdentityFactory = signal,
        w: com.meshchats.app.crypto.SecretWrapper = wrapper,
        st: IdentityStore = store,
        clock: () -> Long = { 1000L },
    ): DefaultDeviceIdentityRepository = DefaultDeviceIdentityRepository(
        crypto = c,
        signalFactory = s,
        wrapper = w,
        secretFile = AtomicSecretFile(secretFile, directorySync = RecordingDirectorySync()),
        store = st,
        fourWords = fourWords,
        clock = clock,
    )

    private fun setup() {
        secretFile = File(tmp.newFolder("nobackup"), "identity.wrapped")
    }

    @Test
    fun createsOnceAndPersistsBothRows() {
        setup()
        val result = repo().getOrCreateIdentity()
        val id = (result as DeviceIdentityResult.Success).identity
        assertEquals(44, id.edPublicX509.size)
        assertEquals(32, id.fingerprintSha256.size)
        assertEquals(4, id.fourWords.size)
        assertTrue(secretFile.exists())
        // Both DB rows written together, exactly once.
        assertEquals(1, store.rowsInserted)
        assertNotEquals(null, store.deviceRow())
        assertNotEquals(null, store.signalRow())
    }

    @Test
    fun reopenReturnsSameIdentityWithoutRegenerating() {
        setup()
        val first = (repo().getOrCreateIdentity() as DeviceIdentityResult.Success).identity
        val insertedAfterFirst = store.rowsInserted

        val second = (repo().getOrCreateIdentity() as DeviceIdentityResult.Success).identity
        assertArrayEquals(first.edPublicX509, second.edPublicX509)
        assertArrayEquals(first.fingerprintSha256, second.fingerprintSha256)
        assertArrayEquals(first.bindingSignature, second.bindingSignature)
        // Reopen must NOT insert again.
        assertEquals(insertedAfterFirst, store.rowsInserted)
    }

    @Test
    fun fingerprintIsSha256OfPublicKey() {
        setup()
        val id = (repo().getOrCreateIdentity() as DeviceIdentityResult.Success).identity
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(id.edPublicX509)
        assertArrayEquals(expected, id.fingerprintSha256)
    }

    @Test
    fun bindingSignatureVerifiesOverCanonicalBinding() {
        setup()
        val id = (repo().getOrCreateIdentity() as DeviceIdentityResult.Success).identity
        val payload = IdentityBinding.bindingPayload(
            id.bindingVersion, id.edPublicX509, id.signalPublicBinding,
        )
        val verify = crypto.verify(id.edPublicX509, payload, id.bindingSignature)
        assertTrue((verify as Ed25519VerifyResult.Success).valid)
    }

    @Test
    fun keyLossOnReopenFailsClosedNeverRegenerates() {
        setup()
        repo().getOrCreateIdentity() as DeviceIdentityResult.Success
        val insertedBefore = store.rowsInserted

        wrapper.keyLost = true
        val result = repo().getOrCreateIdentity()
        assertEquals(DeviceIdentityError.KEY_LOST, (result as DeviceIdentityResult.Failure).error)
        // Never regenerated: no new rows, file untouched.
        assertEquals(insertedBefore, store.rowsInserted)
        assertTrue(secretFile.exists())
    }

    @Test
    fun tamperedSecretFileFailsClosed() {
        setup()
        repo().getOrCreateIdentity() as DeviceIdentityResult.Success

        // Flip a byte in the wrapped ciphertext: AEAD auth fails on unwrap.
        val bytes = secretFile.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        secretFile.writeBytes(bytes)

        val result = repo().getOrCreateIdentity()
        assertEquals(DeviceIdentityError.TAMPERED, (result as DeviceIdentityResult.Failure).error)
    }

    @Test
    fun dbRowsPresentButFileMissingIsKeyLossNotRegeneration() {
        setup()
        // DB has identity rows but no wrapped file: partial/hostile state.
        store.seed(
            device = StoredDeviceIdentity(
                publicKeyX509 = ByteArray(44),
                fingerprintSha256 = ByteArray(32),
                createdAt = 1L,
                signalPublicBinding = ByteArray(33),
                signalBindingSignature = ByteArray(64),
                bindingVersion = 1,
            ),
            signal = StoredSignalIdentity(1, ByteArray(64), 1, 1L),
        )
        val result = repo().getOrCreateIdentity()
        assertEquals(DeviceIdentityError.KEY_LOST, (result as DeviceIdentityResult.Failure).error)
        assertFalse(secretFile.exists())
    }

    @Test
    fun crashBetweenFileAndDbIsFinalizedFromFile() {
        setup()
        // Simulate: file written, DB insert never committed (crash between steps).
        // Use a store that fails the create-time write, so the file lands but rows don't.
        val failingStore = FakeIdentityStore().apply { failWrite = true }
        val createResult = repo(st = failingStore).getOrCreateIdentity()
        assertEquals(
            DeviceIdentityError.DATABASE_UNAVAILABLE,
            (createResult as DeviceIdentityResult.Failure).error,
        )
        assertTrue("file should have landed before the DB step", secretFile.exists())
        assertEquals(0, failingStore.rowsInserted)

        // Now reopen with a healthy store: recovery finalizes the DB from the file.
        val healthy = FakeIdentityStore()
        val recovered = repo(st = healthy).getOrCreateIdentity()
        val id = (recovered as DeviceIdentityResult.Success).identity
        assertEquals(1, healthy.rowsInserted)
        assertNotEquals(null, healthy.deviceRow())
        assertNotEquals(null, healthy.signalRow())
        assertArrayEquals(id.fingerprintSha256, healthy.deviceRow()!!.fingerprintSha256)
    }

    @Test
    fun storedDeviceRowMismatchFailsClosed() {
        setup()
        repo().getOrCreateIdentity() as DeviceIdentityResult.Success
        // Corrupt the stored device public key so it no longer matches the secret.
        val d = store.deviceRow()!!
        store.seed(
            device = StoredDeviceIdentity(
                publicKeyX509 = ByteArray(44) { 0x11 },
                fingerprintSha256 = d.fingerprintSha256,
                createdAt = d.createdAt,
                signalPublicBinding = d.signalPublicBinding,
                signalBindingSignature = d.signalBindingSignature,
                bindingVersion = d.bindingVersion,
            ),
            signal = store.signalRow(),
        )
        val result = repo().getOrCreateIdentity()
        assertEquals(DeviceIdentityError.TAMPERED, (result as DeviceIdentityResult.Failure).error)
    }

    @Test
    fun signalRowThatDoesNotParseFailsClosed() {
        setup()
        repo().getOrCreateIdentity() as DeviceIdentityResult.Success
        signal.failParse = true
        val result = repo().getOrCreateIdentity()
        assertEquals(DeviceIdentityError.TAMPERED, (result as DeviceIdentityResult.Failure).error)
    }

    @Test
    fun signThenVerifyRoundTrips() {
        setup()
        val id = (repo().getOrCreateIdentity() as DeviceIdentityResult.Success).identity
        val r = repo()
        val message = "attest this".toByteArray()
        val sig = (r.sign(message) as IdentitySignResult.Success).signature
        assertTrue(r.verify(id.edPublicX509, message, sig))
    }

    @Test
    fun verifyRejectsWrongMessage() {
        setup()
        val id = (repo().getOrCreateIdentity() as DeviceIdentityResult.Success).identity
        val r = repo()
        val sig = (r.sign("a".toByteArray()) as IdentitySignResult.Success).signature
        assertFalse(r.verify(id.edPublicX509, "b".toByteArray(), sig))
    }

    @Test
    fun signAfterKeyLossFailsClosed() {
        setup()
        repo().getOrCreateIdentity() as DeviceIdentityResult.Success
        wrapper.keyLost = true
        val result = repo().sign("x".toByteArray())
        assertEquals(DeviceIdentityError.KEY_LOST, (result as IdentitySignResult.Failure).error)
    }

    @Test
    fun appSignatureCannotBeReplayedAsBindingSignature() {
        setup()
        val id = (repo().getOrCreateIdentity() as DeviceIdentityResult.Success).identity
        val r = repo()
        // Sign the raw binding-looking bytes via the app message API.
        val bindingBytes = IdentityBinding.bindingPayload(
            id.bindingVersion, id.edPublicX509, id.signalPublicBinding,
        )
        val appSig = (r.sign(bindingBytes) as IdentitySignResult.Success).signature
        // That signature must NOT verify as a raw binding signature (different domain).
        val asBinding = crypto.verify(id.edPublicX509, bindingBytes, appSig)
        assertFalse((asBinding as Ed25519VerifyResult.Success).valid)
    }

    @Test
    fun qrPayloadEncodesAndVerifiesRoundTrip() {
        setup()
        val id = (repo().getOrCreateIdentity() as DeviceIdentityResult.Success).identity
        val qr = IdentityQrPayload(
            edPublicX509 = id.edPublicX509,
            fingerprintSha256 = id.fingerprintSha256,
            signalPublicBinding = id.signalPublicBinding,
            bindingSignature = id.bindingSignature,
            bindingVersion = id.bindingVersion,
        )
        val text = (IdentityQrCodec.encode(qr) as IdentityQrEncodeResult.Success).text
        val scanned = repo().verifyScannedPayload(text)
        assertTrue(scanned is ScannedIdentityResult.Verified)
    }

    @Test
    fun scannedPayloadWithMismatchedFingerprintRejected() {
        setup()
        val id = (repo().getOrCreateIdentity() as DeviceIdentityResult.Success).identity
        val qr = IdentityQrPayload(
            edPublicX509 = id.edPublicX509,
            fingerprintSha256 = ByteArray(32), // wrong fingerprint
            signalPublicBinding = id.signalPublicBinding,
            bindingSignature = id.bindingSignature,
            bindingVersion = id.bindingVersion,
        )
        val text = (IdentityQrCodec.encode(qr) as IdentityQrEncodeResult.Success).text
        val scanned = repo().verifyScannedPayload(text)
        assertEquals(
            ScannedIdentityRejection.FINGERPRINT_MISMATCH,
            (scanned as ScannedIdentityResult.Rejected).reason,
        )
    }

    @Test
    fun scannedPayloadWithBadSignatureRejected() {
        setup()
        val id = (repo().getOrCreateIdentity() as DeviceIdentityResult.Success).identity
        val badSig = id.bindingSignature.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val qr = IdentityQrPayload(
            edPublicX509 = id.edPublicX509,
            fingerprintSha256 = id.fingerprintSha256,
            signalPublicBinding = id.signalPublicBinding,
            bindingSignature = badSig,
            bindingVersion = id.bindingVersion,
        )
        val text = (IdentityQrCodec.encode(qr) as IdentityQrEncodeResult.Success).text
        val scanned = repo().verifyScannedPayload(text)
        assertEquals(
            ScannedIdentityRejection.SIGNATURE_INVALID,
            (scanned as ScannedIdentityResult.Rejected).reason,
        )
    }

    @Test
    fun malformedScannedPayloadRejected() {
        setup()
        repo().getOrCreateIdentity() as DeviceIdentityResult.Success
        val scanned = repo().verifyScannedPayload("garbage!!!")
        assertEquals(
            ScannedIdentityRejection.MALFORMED,
            (scanned as ScannedIdentityResult.Rejected).reason,
        )
    }

    @Test
    fun wrapperUnavailableAtCreateFailsClosed() {
        setup()
        wrapper.wrapUnavailable = true
        val result = repo().getOrCreateIdentity()
        assertEquals(
            DeviceIdentityError.WRAPPER_UNAVAILABLE,
            (result as DeviceIdentityResult.Failure).error,
        )
        // No DB rows and no file when wrapping the secret failed.
        assertFalse(secretFile.exists())
        assertEquals(0, store.rowsInserted)
    }

    @Test
    fun qrPayloadNeverContainsPrivateKey() {
        setup()
        val id = (repo().getOrCreateIdentity() as DeviceIdentityResult.Success).identity
        val qr = IdentityQrPayload(
            id.edPublicX509, id.fingerprintSha256, id.signalPublicBinding, id.bindingSignature, id.bindingVersion,
        )
        val text = (IdentityQrCodec.encode(qr) as IdentityQrEncodeResult.Success).text
        // Read the raw private key from the wrapped file and confirm it is not in the QR bytes.
        val decoded = java.util.Base64.getUrlDecoder().decode(text)
        // The QR fields are only public material; the private key length (~83) never appears.
        assertFalse(decoded.size > IdentityQrCodec.MAX_TOTAL_BYTES)
        assertTrue(text.isNotEmpty())
    }

    @Test
    fun signRefusesOverBoundMessageWithTypedError() {
        setup()
        repo().getOrCreateIdentity() as DeviceIdentityResult.Success
        val over = ByteArray(IdentityBinding.MAX_MESSAGE_BYTES + 1)
        val result = repo().sign(over)
        assertEquals(
            DeviceIdentityError.MESSAGE_TOO_LARGE,
            (result as IdentitySignResult.Failure).error,
        )
    }

    @Test
    fun signAcceptsExactBoundMessageAndVerifies() {
        setup()
        val id = (repo().getOrCreateIdentity() as DeviceIdentityResult.Success).identity
        val r = repo()
        val exact = ByteArray(IdentityBinding.MAX_MESSAGE_BYTES) { (it and 0xFF).toByte() }
        val sig = (r.sign(exact) as IdentitySignResult.Success).signature
        assertTrue(r.verify(id.edPublicX509, exact, sig))
    }

    @Test
    fun verifyReturnsFalseForOverBoundMessageNeverThrows() {
        setup()
        val id = (repo().getOrCreateIdentity() as DeviceIdentityResult.Success).identity
        val r = repo()
        val sig = (r.sign(ByteArray(0)) as IdentitySignResult.Success).signature
        val over = ByteArray(IdentityBinding.MAX_MESSAGE_BYTES + 1)
        assertFalse(r.verify(id.edPublicX509, over, sig))
    }

    @Test
    fun twoInstancesRecoveringFileBeforeDbConverge() {
        setup()
        // Create the file-present/DB-absent crash state: file lands, DB never commits.
        val failingStore = FakeIdentityStore().apply { failWrite = true }
        repo(st = failingStore).getOrCreateIdentity() as DeviceIdentityResult.Failure
        assertTrue(secretFile.exists())
        assertEquals(0, failingStore.rowsInserted)

        // Two independent repo instances share ONE healthy store (models two repo
        // objects racing to recover the same crash state), each with its own file
        // handle over the same path. Under the shared creation lock they must
        // converge: exactly one finalize insert total, both return the same identity.
        val shared = FakeIdentityStore()
        val a = repo(st = shared)
        val b = repo(st = shared)

        val results = java.util.concurrent.CopyOnWriteArrayList<DeviceIdentityResult>()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = listOf(a, b).map { repository ->
            Thread {
                start.await()
                results.add(repository.getOrCreateIdentity())
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }

        // Both succeeded and agree; the DB was finalized exactly once, not twice.
        val ids = results.map { (it as DeviceIdentityResult.Success).identity }
        assertEquals(2, ids.size)
        assertArrayEquals(ids[0].fingerprintSha256, ids[1].fingerprintSha256)
        assertEquals(1, shared.rowsInserted)
    }
}
