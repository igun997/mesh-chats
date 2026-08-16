package com.meshchats.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded store exception must never carry secret bytes, record blobs, or a
 * caller-supplied free-text message: it exposes only a fixed [SignalStoreReason]
 * so a corrupt/missing-record failure cannot leak key material through a log or
 * stack trace. These are pure JVM assertions on the exception's shape.
 */
class SignalStoreExceptionTest {

    @Test
    fun messageIsTheFixedReasonLabelOnly() {
        for (reason in SignalStoreReason.entries) {
            val ex = SignalStoreException(reason)
            // The message is exactly the reason's stable label — no dynamic content.
            assertEquals(reason.label, ex.message)
        }
    }

    @Test
    fun reasonIsPreserved() {
        val ex = SignalStoreException(SignalStoreReason.CORRUPT_RECORD)
        assertEquals(SignalStoreReason.CORRUPT_RECORD, ex.reason)
    }

    @Test
    fun causeIsNotRetainedSoUnderlyingBytesCannotLeak() {
        // Even if constructed from a raw throwable, the cause is dropped: a SQL or
        // parse exception can carry blob fragments in its message, so it must never
        // be chained into the bounded store exception.
        val ex = SignalStoreException(SignalStoreReason.CORRUPT_RECORD)
        assertNull(ex.cause)
    }

    @Test
    fun labelsCarryNoSecretPlaceholders() {
        // Guard: labels are short, fixed, human-readable, and contain no formatting
        // slots that a caller could smuggle bytes into.
        for (reason in SignalStoreReason.entries) {
            assertFalse(reason.label.contains("%"))
            assertFalse(reason.label.contains("{"))
            assertTrue(reason.label.isNotBlank())
        }
    }
}
