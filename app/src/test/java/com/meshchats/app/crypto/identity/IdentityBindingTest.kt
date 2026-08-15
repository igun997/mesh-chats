package com.meshchats.app.crypto.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the canonical domain-separated payloads are deterministic, unambiguous
 * (length-prefixed so no field boundary can shift), and separated by context so a
 * binding signature cannot be replayed as a message signature.
 */
class IdentityBindingTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun bindingPayloadIsDeterministic() {
        val a = IdentityBinding.bindingPayload(1, byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
        val b = IdentityBinding.bindingPayload(1, byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
        assertEquals(hex(a), hex(b))
    }

    @Test
    fun bindingPayloadMatchesFixedVector() {
        val payload = IdentityBinding.bindingPayload(1, byteArrayOf(0xAA.toByte(), 0xBB.toByte()), byteArrayOf(0xCC.toByte()))
        // "mesh-chats/identity-binding/v1" ASCII, then 01, then 0002 AABB, then 0001 CC.
        val ctx = "mesh-chats/identity-binding/v1".toByteArray(Charsets.US_ASCII)
        val expected = hex(ctx) + "01" + "0002aabb" + "0001cc"
        assertEquals(expected, hex(payload))
    }

    @Test
    fun lengthPrefixMakesFieldBoundaryUnambiguous() {
        // Moving a byte across the boundary changes the payload.
        val a = IdentityBinding.bindingPayload(1, byteArrayOf(1, 2, 3), byteArrayOf(4))
        val b = IdentityBinding.bindingPayload(1, byteArrayOf(1, 2), byteArrayOf(3, 4))
        assertFalse(hex(a) == hex(b))
    }

    @Test
    fun bindingAndMessageDomainsDiffer() {
        val binding = IdentityBinding.bindingPayload(1, byteArrayOf(1), byteArrayOf(2))
        val message = (IdentityBinding.messagePayload(byteArrayOf(1, 2))
            as IdentityBinding.MessagePayloadResult.Success).bytes
        assertFalse(hex(binding) == hex(message))
    }

    @Test
    fun versionByteAffectsPayload() {
        val v1 = IdentityBinding.bindingPayload(1, byteArrayOf(1), byteArrayOf(2))
        val v2 = IdentityBinding.bindingPayload(2, byteArrayOf(1), byteArrayOf(2))
        assertFalse(hex(v1) == hex(v2))
    }

    @Test
    fun messagePayloadWrapsWithContext() {
        val p = (IdentityBinding.messagePayload(byteArrayOf(9))
            as IdentityBinding.MessagePayloadResult.Success).bytes
        val ctx = "mesh-chats/identity-message/v1".toByteArray(Charsets.US_ASCII)
        assertEquals(hex(ctx) + "0001" + "09", hex(p))
    }

    @Test
    fun emptyFieldsAreRepresentable() {
        val p = (IdentityBinding.messagePayload(ByteArray(0))
            as IdentityBinding.MessagePayloadResult.Success).bytes
        val ctx = "mesh-chats/identity-message/v1".toByteArray(Charsets.US_ASCII)
        assertEquals(hex(ctx) + "0000", hex(p))
        assertTrue(p.isNotEmpty())
    }

    @Test
    fun zeroLengthMessageFramesToExactlyContextPlusEmptyLength() {
        val r = IdentityBinding.messagePayload(ByteArray(0))
        val bytes = (r as IdentityBinding.MessagePayloadResult.Success).bytes
        val ctx = "mesh-chats/identity-message/v1".toByteArray(Charsets.US_ASCII)
        assertEquals(hex(ctx) + "0000", hex(bytes))
    }

    @Test
    fun exactBoundMessageFramesToSignerCeiling() {
        val exact = ByteArray(IdentityBinding.MAX_MESSAGE_BYTES)
        val r = IdentityBinding.messagePayload(exact)
        val bytes = (r as IdentityBinding.MessagePayloadResult.Success).bytes
        // The framed payload is exactly the signer's hard ceiling: bound is tight.
        assertEquals(Ed25519Crypto.MAX_MESSAGE_BYTES, bytes.size)
    }

    @Test
    fun oneOverBoundIsRefusedNotTruncated() {
        val over = ByteArray(IdentityBinding.MAX_MESSAGE_BYTES + 1)
        assertEquals(
            IdentityBinding.MessagePayloadResult.TooLarge,
            IdentityBinding.messagePayload(over),
        )
    }

    @Test
    fun hugeMessageIsRefusedWithoutThrowing() {
        val huge = ByteArray(Ed25519Crypto.MAX_MESSAGE_BYTES * 4)
        assertEquals(
            IdentityBinding.MessagePayloadResult.TooLarge,
            IdentityBinding.messagePayload(huge),
        )
    }

    @Test
    fun maxMessageBoundAccountsForFramingOverhead() {
        assertEquals(
            Ed25519Crypto.MAX_MESSAGE_BYTES - IdentityBinding.MESSAGE_FRAMING_OVERHEAD,
            IdentityBinding.MAX_MESSAGE_BYTES,
        )
    }
}
