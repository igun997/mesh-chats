package com.meshchats.app.data.local

/**
 * The bounded, fixed set of reasons the blocking libsignal Room store can fail in
 * a way it must surface to the libsignal adapter. Each carries only a short,
 * static [label] — never a caller-supplied string, record blob, or key byte — so
 * a failure cannot leak secret material through a message, log, or stack trace.
 */
enum class SignalStoreReason(val label: String) {
    /** The singleton local identity row is absent when one was required. */
    MISSING_LOCAL_IDENTITY("missing local identity"),

    /**
     * A stored record (identity key pair, identity key, session, pre/signed/Kyber
     * prekey) could not be parsed back into its libsignal type. The corrupt bytes
     * are deliberately not included.
     */
    CORRUPT_RECORD("corrupt stored record"),
}

/**
 * A bounded runtime failure from the blocking Signal store. It exposes only a
 * fixed [SignalStoreReason]; it never chains a cause and never accepts a
 * free-text message, so no secret bytes or underlying SQL/parse detail can escape
 * through it. The libsignal adapter maps these to its own bounded engine errors.
 *
 * Unchecked because it flows through synchronous libsignal store callbacks, which
 * do not declare it; the adapter/engine layer catches it at the boundary.
 */
class SignalStoreException(
    val reason: SignalStoreReason,
) : RuntimeException(reason.label) {
    // No cause is ever retained: a chained SQL/parse exception could carry blob
    // fragments in its own message. fillInStackTrace still runs (default), but the
    // trace holds no record bytes.
}
