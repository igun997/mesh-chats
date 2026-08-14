package com.meshchats.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Confirms the module compiles and runs as a pure JVM library, and that no
 * Android class has leaked onto its runtime classpath.
 */
class ModuleSmokeTest {

    @Test
    fun `module runs on a plain JVM`() {
        assertTrue(ProtocolInfo.NAME.isNotEmpty())
    }

    @Test
    fun `no android class is present on the classpath`() {
        val androidOnClasspath = try {
            Class.forName("android.os.Build")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
        assertFalse("mesh-protocol must not depend on Android", androidOnClasspath)
    }
}
