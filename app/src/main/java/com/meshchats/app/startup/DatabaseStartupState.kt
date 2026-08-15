package com.meshchats.app.startup

/**
 * A bounded, human-mappable reason the encrypted database could not be brought
 * online at startup. Deliberately small and closed: the recovery UI switches on
 * these values, and none of them carries exception text or secret material.
 */
enum class StorageStartupReason {
    /**
     * The database key could not be obtained — it was never created, its wrapping
     * key is gone (its Keystore alias was deleted or the app's Keystore material
     * was lost with its app data), or the key store was unavailable. Data at rest
     * is intact but currently unreadable.
     */
    KEY_UNAVAILABLE,

    /**
     * A legacy plaintext database existed but could not be safely migrated to the
     * encrypted form. The plaintext database is preserved untouched.
     */
    MIGRATION_FAILED,

    /**
     * Any other failure while opening storage. Reported without detail so no
     * exception text or secret leaks into the UI or logs.
     */
    UNEXPECTED,
}

/**
 * The lifecycle of encrypted-storage startup, owned by [DatabaseStartupCoordinator]
 * and observed by the UI gate. The app content is composed only in [Ready];
 * [Idle]/[Initializing] show a neutral loading state, and [Failed] shows a
 * non-destructive recovery screen offering retry only.
 */
sealed interface DatabaseStartupState {
    /** No attempt has started yet. */
    data object Idle : DatabaseStartupState

    /** An open attempt is running off the main thread. */
    data object Initializing : DatabaseStartupState

    /** The encrypted database is open and the app may compose its content. */
    data object Ready : DatabaseStartupState

    /** The open attempt failed with a bounded [reason]; retry is allowed. */
    data class Failed(val reason: StorageStartupReason) : DatabaseStartupState
}
