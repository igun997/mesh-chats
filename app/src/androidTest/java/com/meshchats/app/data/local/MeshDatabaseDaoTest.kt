package com.meshchats.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CRUD + invariant coverage for the v2 DAOs, run against an in-memory
 * SQLCipher-encrypted Room database. Covers every Signal store (including blob
 * replacement and deletes), the verified-contact query, the atomic queued
 * message + outbox enqueue (success, rollback, and input rejection), foreign-key
 * cascade, and deterministic ordering.
 */
@RunWith(AndroidJUnit4::class)
class MeshDatabaseDaoTest {

    private lateinit var db: MeshDatabase

    private val rawKey = SqlCipherRawKey.encode(ByteArray(32) { (it + 5).toByte() })

    @Before
    fun setUp() {
        SqlCipherNative.ensureLoaded()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // In-memory database, encrypted through the same SQLCipher factory as prod.
        db = Room.inMemoryDatabaseBuilder(ctx, MeshDatabase::class.java)
            .openHelperFactory(SupportOpenHelperFactory(rawKey))
            .build()
        // No manual PRAGMA: Room's generated MeshDatabase_Impl.onOpen runs
        // `PRAGMA foreign_keys = ON` on every connection, so cascade constraints are
        // enforced through the same open path production uses. See the dedicated
        // foreignKeysEnforcedOnOpen assertion below.
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- Device + contact identity -----------------------------------------

    @Test
    fun deviceIdentityIsSingletonAndReplaceable() = runBlocking {
        val dao = db.deviceIdentityDao()
        assertNull(dao.get())
        dao.upsert(
            DeviceIdentityEntity(
                publicKeyX509 = byteArrayOf(1, 2, 3),
                fingerprintSha256 = byteArrayOf(9),
                createdAt = 1,
                bindingVersion = 0,
            ),
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), dao.get()!!.publicKeyX509)

        // Replace: still exactly one row, now with binding fields.
        dao.upsert(
            DeviceIdentityEntity(
                publicKeyX509 = byteArrayOf(4),
                fingerprintSha256 = byteArrayOf(8),
                createdAt = 2,
                signalPublicBinding = byteArrayOf(7),
                signalBindingSignature = byteArrayOf(6),
                bindingVersion = 1,
            ),
        )
        val row = dao.get()!!
        assertArrayEquals(byteArrayOf(4), row.publicKeyX509)
        assertArrayEquals(byteArrayOf(7), row.signalPublicBinding)
        assertEquals(1, row.bindingVersion)
    }

    @Test
    fun verifiedContactsQueryReturnsOnlyVerifiedOrdered() = runBlocking {
        val dao = db.contactIdentityDao()
        dao.upsert(contact("zeta", TrustState.VERIFIED))
        dao.upsert(contact("alpha", TrustState.VERIFIED))
        dao.upsert(contact("beta", TrustState.UNVERIFIED))
        dao.upsert(contact("gamma", TrustState.REVOKED))

        val verified = dao.byTrustState()
        assertEquals(listOf("alpha", "zeta"), verified.map { it.address })
    }

    private fun contact(address: String, trust: TrustState) = ContactIdentityEntity(
        address = address,
        publicKey = byteArrayOf(1),
        fingerprintSha256 = byteArrayOf(2),
        trustState = trust.name,
        firstSeenAt = 1,
        updatedAt = 1,
    )

    // --- Signal stores ------------------------------------------------------

    @Test
    fun signalIdentityStoreLoadStoreContainsDelete() = runBlocking {
        val dao = db.signalIdentityDao()
        dao.putLocal(
            SignalIdentityEntity(
                registrationId = 4242,
                identityKeyPair = byteArrayOf(10, 11),
                schemaVersion = 1,
                createdAt = 1,
            ),
        )
        assertEquals(4242, dao.getLocal()!!.registrationId)

        assertFalse(dao.containsTrusted("peer", 1))
        dao.putTrusted(
            SignalTrustedIdentityEntity("peer", 1, byteArrayOf(1), 1, 1),
        )
        assertTrue(dao.containsTrusted("peer", 1))
        // Blob replacement.
        dao.putTrusted(
            SignalTrustedIdentityEntity("peer", 1, byteArrayOf(2, 2), 1, 2),
        )
        assertArrayEquals(byteArrayOf(2, 2), dao.getTrusted("peer", 1)!!.identityKey)
        dao.deleteTrusted("peer", 1)
        assertFalse(dao.containsTrusted("peer", 1))
    }

    @Test
    fun signalSessionStoreOperations() = runBlocking {
        val dao = db.signalSessionDao()
        dao.store(SignalSessionEntity("peer", 1, byteArrayOf(1), 1, 1))
        dao.store(SignalSessionEntity("peer", 3, byteArrayOf(3), 1, 1))
        dao.store(SignalSessionEntity("peer", 2, byteArrayOf(2), 1, 1))

        assertTrue(dao.contains("peer", 2))
        assertEquals(listOf(1, 2, 3), dao.deviceIdsFor("peer"))

        // Replace blob for one device.
        dao.store(SignalSessionEntity("peer", 2, byteArrayOf(9, 9), 1, 2))
        assertArrayEquals(byteArrayOf(9, 9), dao.load("peer", 2)!!.record)

        dao.delete("peer", 2)
        assertFalse(dao.contains("peer", 2))
        dao.deleteAllFor("peer")
        assertTrue(dao.deviceIdsFor("peer").isEmpty())
    }

    @Test
    fun signalPreKeyStoreOperations() = runBlocking {
        val dao = db.signalPreKeyDao()
        dao.store(SignalPreKeyEntity(5, byteArrayOf(5), 1, 1))
        dao.store(SignalPreKeyEntity(1, byteArrayOf(1), 1, 1))
        assertEquals(listOf(1, 5), dao.allIds())
        assertTrue(dao.contains(5))
        dao.store(SignalPreKeyEntity(5, byteArrayOf(7), 1, 2))
        assertArrayEquals(byteArrayOf(7), dao.load(5)!!.record)
        dao.delete(5)
        assertFalse(dao.contains(5))
    }

    @Test
    fun signalSignedPreKeyStoreOperations() = runBlocking {
        val dao = db.signalSignedPreKeyDao()
        dao.store(SignalSignedPreKeyEntity(2, byteArrayOf(2), 1, 1))
        dao.store(SignalSignedPreKeyEntity(1, byteArrayOf(1), 1, 1))
        assertEquals(listOf(1, 2), dao.allIds())
        assertEquals(2, dao.loadAll().size)
        dao.delete(1)
        assertNull(dao.load(1))
    }

    @Test
    fun signalKyberPreKeyStoreOperationsAndUsedFlag() = runBlocking {
        val dao = db.signalKyberPreKeyDao()
        dao.store(SignalKyberPreKeyEntity(1, byteArrayOf(1), used = false, lastResort = false, schemaVersion = 1, createdAt = 1))
        dao.store(SignalKyberPreKeyEntity(2, byteArrayOf(2), used = false, lastResort = true, schemaVersion = 1, createdAt = 1))
        assertEquals(listOf(1, 2), dao.allIds())
        assertFalse(dao.load(1)!!.used)
        dao.markUsed(1)
        assertTrue(dao.load(1)!!.used)
        assertTrue(dao.load(2)!!.lastResort)
        dao.delete(1)
        assertFalse(dao.contains(1))
    }

    // --- Atomic outbound enqueue -------------------------------------------

    private fun outgoing(id: String, packetId: String?) = MessageEntity(
        id = id,
        conversationId = "c1",
        authorId = "me",
        body = "secret",
        sentAt = 100,
        isOutgoing = true,
        deliveryState = OutboxDeliveryState.QUEUED.name,
        packetId = packetId,
    )

    private fun packet(packetId: String, messageId: String, ciphertext: ByteArray = byteArrayOf(1, 2)) =
        CiphertextOutboxEntity(
            packetId = packetId,
            messageId = messageId,
            destinationAddress = "peer",
            destinationDeviceId = 1,
            ciphertext = ciphertext,
            createdAt = 100,
        )

    @Test
    fun enqueueOutboundCommitsBothRows() = runBlocking {
        val dao = db.outboxDao()
        val result = dao.enqueueOutbound(outgoing("m1", "p1"), packet("p1", "m1"))
        assertEquals(EnqueueResult.Success("p1"), result)

        // Visible message present.
        val list = db.messageDao().observeConversation("c1").first()
        assertEquals(1, list.size)
        assertEquals("p1", list.first().packetId)
        // Outbox packet present with only ciphertext.
        val stored = dao.getPacket("p1")!!
        assertArrayEquals(byteArrayOf(1, 2), stored.ciphertext)
        assertEquals("m1", stored.messageId)
    }

    @Test
    fun enqueueOutboundRejectsInvalidInputWritingNothing() = runBlocking {
        val dao = db.outboxDao()
        // Incoming message → NOT_OUTBOUND, nothing written.
        val incoming = outgoing("m2", "p2").copy(isOutgoing = false)
        val result = dao.enqueueOutbound(incoming, packet("p2", "m2"))
        assertEquals(EnqueueResult.Rejected(EnqueueRejection.NOT_OUTBOUND), result)
        assertNull(dao.getPacket("p2"))
        assertTrue(db.messageDao().observeConversation("c1").first().isEmpty())
    }

    @Test
    fun enqueueOutboundRollsBackWhenSecondInsertConflicts() = runBlocking {
        val dao = db.outboxDao()
        // Pre-insert a packet with the same packet_id to force a unique conflict on
        // the outbox insert, so the message insert in the same transaction rolls back.
        dao.enqueueOutbound(outgoing("m3", "p3"), packet("p3", "m3"))
        val before = db.messageDao().observeConversation("c1").first().size

        var threw = false
        try {
            // New message m4 but reusing packet id p3 → outbox unique conflict (ABORT).
            dao.enqueueOutbound(outgoing("m4", "p3"), packet("p3", "m4"))
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("conflicting insert must throw", threw)
        // m4 must NOT have been committed (transaction rolled back).
        val after = db.messageDao().observeConversation("c1").first()
        assertEquals(before, after.size)
        assertTrue(after.none { it.id == "m4" })
    }

    @Test
    fun deletingMessageCascadesToOutbox() = runBlocking {
        val dao = db.outboxDao()
        dao.enqueueOutbound(outgoing("m5", "p5"), packet("p5", "m5"))
        assertTrue(dao.getPacket("p5") != null)

        db.messageDao().clearConversation("c1")
        assertNull("outbox row must cascade-delete with its message", dao.getPacket("p5"))
    }

    @Test
    fun deletingPacketCascadesToAttempts() = runBlocking {
        val dao = db.outboxDao()
        dao.enqueueOutbound(outgoing("m6", "p6"), packet("p6", "m6"))
        dao.insertAttempt(
            DeliveryAttemptEntity(
                packetId = "p6",
                transport = "BLE",
                attemptedAt = 1,
                outcome = OutboxDeliveryState.FAILED.name,
                failureCode = "TIMEOUT",
            ),
        )
        assertEquals(1, dao.attemptsFor("p6").size)
        dao.delete("p6")
        assertTrue(dao.attemptsFor("p6").isEmpty())
    }

    @Test
    fun dueForDeliveryOrdersDeterministically() = runBlocking {
        val dao = db.outboxDao()
        // Three queued packets: differ by priority then created_at.
        dao.enqueueOutbound(
            outgoing("ma", "pa"),
            packet("pa", "ma").copy(priority = 1, createdAt = 200),
        )
        dao.enqueueOutbound(
            outgoing("mb", "pb"),
            packet("pb", "mb").copy(priority = 5, createdAt = 300),
        )
        dao.enqueueOutbound(
            outgoing("mc", "pc"),
            packet("pc", "mc").copy(priority = 5, createdAt = 100),
        )

        val order = dao.dueForDelivery(now = 1_000).map { it.packetId }
        // priority desc → pb & pc (5) before pa (1); within 5, created_at asc → pc, pb.
        assertEquals(listOf("pc", "pb", "pa"), order)
    }

    @Test
    fun dueForDeliveryExcludesExpiredAndFuture() = runBlocking {
        val dao = db.outboxDao()
        dao.enqueueOutbound(
            outgoing("mx", "px"),
            packet("px", "mx").copy(expiresAt = 50, createdAt = 10),
        )
        dao.enqueueOutbound(
            outgoing("my", "py"),
            packet("py", "my").copy(nextAttemptAt = 5_000),
        )
        dao.enqueueOutbound(outgoing("mz", "pz"), packet("pz", "mz"))

        val due = dao.dueForDelivery(now = 1_000).map { it.packetId }
        assertEquals(listOf("pz"), due)
    }

    @Test
    fun dueForDeliveryHonoursLimitAndRejectsNonPositive() = runBlocking {
        val dao = db.outboxDao()
        // Five due packets, all same priority; created_at asc breaks the tie.
        for (i in 1..5) {
            dao.enqueueOutbound(
                outgoing("ml$i", "pl$i"),
                packet("pl$i", "ml$i").copy(priority = 0, createdAt = i.toLong()),
            )
        }
        // limit bounds how many rows a single scan materialises.
        val firstTwo = dao.dueForDelivery(now = 1_000, limit = 2).map { it.packetId }
        assertEquals(listOf("pl1", "pl2"), firstTwo)

        val all = dao.dueForDelivery(now = 1_000, limit = 10).map { it.packetId }
        assertEquals(listOf("pl1", "pl2", "pl3", "pl4", "pl5"), all)

        // A non-positive limit is a caller bug and must fail fast.
        var threw = false
        try {
            dao.dueForDelivery(now = 1_000, limit = 0)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("non-positive limit must be rejected", threw)
    }

    // --- Foreign-key enforcement & non-destructive update -------------------

    @Test
    fun foreignKeysEnforcedOnOpenSoOrphanOutboxInsertFails() = runBlocking {
        // No manual PRAGMA is set anywhere; this asserts Room's generated onOpen
        // turned foreign_keys ON for this connection. Inserting an outbox row that
        // references a non-existent message must be rejected by the FK constraint.
        val cursor = db.openHelper.writableDatabase.query("PRAGMA foreign_keys")
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("foreign_keys must be ON via Room onOpen", 1, it.getInt(0))
        }

        val dao = db.outboxDao()
        var threw = false
        try {
            // Bypass enqueueOutbound's validator by inserting an orphan directly.
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO ciphertext_outbox " +
                    "(packet_id, message_id, destination_address, destination_device_id, " +
                    "ciphertext, created_at, priority, content_type, delivery_state, attempt_count) " +
                    "VALUES ('orphan', 'no-such-message', 'peer', 1, x'0102', 1, 0, 0, 'QUEUED', 0)",
            )
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("orphan outbox insert must violate the FK", threw)
        assertNull(dao.getPacket("orphan"))
    }

    @Test
    fun upsertingMessageDeliveryFieldsPreservesChildOutboxRow() = runBlocking {
        val dao = db.outboxDao()
        // Enqueue a queued message with its outbox packet.
        dao.enqueueOutbound(outgoing("mu", "pu"), packet("pu", "mu"))
        assertTrue("outbox present after enqueue", dao.getPacket("pu") != null)

        // Update the visible message's delivery fields via upsert (same id).
        val updated = outgoing("mu", "pu").copy(
            deliveryState = OutboxDeliveryState.SENT.name,
            failureReason = null,
        )
        db.messageDao().upsert(updated)

        // The message row reflects the update...
        val row = db.messageDao().observeConversation("c1").first().single { it.id == "mu" }
        assertEquals(OutboxDeliveryState.SENT.name, row.deliveryState)
        // ...and its child outbox packet must NOT have been cascade-deleted. A
        // REPLACE-based upsert would delete-then-insert the parent, dropping this.
        assertTrue("child outbox row must survive parent upsert", dao.getPacket("pu") != null)
    }

    @Test
    fun updateDeliveryChangesLifecycleFieldsOnly() = runBlocking {
        val dao = db.outboxDao()
        dao.enqueueOutbound(outgoing("mv", "pv"), packet("pv", "mv", ciphertext = byteArrayOf(7, 7, 7)))

        dao.updateDelivery(
            packetId = "pv",
            state = OutboxDeliveryState.SENT.name,
            attemptCount = 2,
            nextAttemptAt = 900,
            routeMetadata = "BLE:hop1",
        )

        val row = dao.getPacket("pv")!!
        assertEquals(OutboxDeliveryState.SENT.name, row.deliveryState)
        assertEquals(2, row.attemptCount)
        assertEquals(900L, row.nextAttemptAt)
        assertEquals("BLE:hop1", row.routeMetadata)
        // Ciphertext and identity untouched by the status update.
        assertArrayEquals(byteArrayOf(7, 7, 7), row.ciphertext)
        assertEquals("mv", row.messageId)
    }
}
