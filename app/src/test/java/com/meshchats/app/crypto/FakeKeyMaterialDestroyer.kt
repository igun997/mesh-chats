package com.meshchats.app.crypto

/**
 * A host-JVM [KeyMaterialDestroyer] standing in for the Android Keystore alias
 * deletion. It records each destroy call (into a shared [log] so ordering across
 * multiple fakes can be asserted) and can be told to fail or to throw, letting the
 * coordinator's fail-closed and bounded-exception behavior be tested off-device.
 */
class FakeKeyMaterialDestroyer(
    private val id: String,
    private val log: MutableList<String>,
    /** When true, destroy reports a bounded failure instead of Deleted. */
    var fail: Boolean = false,
    /** When true, destroy throws to prove the coordinator bounds exceptions. */
    var throwOnDestroy: Boolean = false,
) : KeyMaterialDestroyer {

    var destroyCount: Int = 0
        private set

    override fun destroy(): WrappingKeyDeleteResult {
        destroyCount++
        log.add("key:$id")
        if (throwOnDestroy) throw RuntimeException("keystore blew up for $id")
        return if (fail) {
            WrappingKeyDeleteResult.Failure(WrappingKeyDeleteError.DELETE_FAILED)
        } else {
            WrappingKeyDeleteResult.Deleted
        }
    }
}
