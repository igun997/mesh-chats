package com.meshchats.app.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the pinned native cryptographic dependencies actually load and execute on the
 * target device (Task 1 of the encrypted identity storage plan). These tests run against
 * the debug variant (minification disabled), so they verify the JNI boundary and per-ABI
 * native packaging: that the device-matching `.so` files are present in the installed APK
 * and that a real call crosses into native code successfully. They do NOT exercise R8 —
 * that a release build keeps the JNI-referenced Java classes is a separate concern covered
 * by the ProGuard keep rules and validated on the release artifact/mapping.
 */
@RunWith(AndroidJUnit4::class)
class NativeCryptoSmokeTest {

    @Test
    fun sqlcipherNativeLibraryLoadsAndEncryptsInMemoryDatabase() {
        // The new net.zetetic:sqlcipher-android artifact loads its native code via
        // System.loadLibrary("sqlcipher"); calling it explicitly fails loudly if the
        // device-matching .so was dropped from the installed APK.
        System.loadLibrary("sqlcipher")

        val db = net.zetetic.database.sqlcipher.SQLiteDatabase.create(null)
        try {
            db.rawQuery("PRAGMA cipher_version", arrayOf<String>()).use { cursor ->
                assertTrue("expected a cipher_version row", cursor.moveToFirst())
                val version = cursor.getString(0)
                assertTrue(
                    "cipher_version must be populated, got '$version'",
                    !version.isNullOrBlank(),
                )
            }

            // Exercise a real round-trip through the native engine.
            db.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
            db.execSQL("INSERT INTO t (v) VALUES ('mesh')")
            db.rawQuery("SELECT v FROM t", arrayOf<String>()).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("row survives native write/read", cursor.getString(0) == "mesh")
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun libsignalNativeClassLoadsAndPerformsCurveOperation() {
        // Generating an identity key pair forces libsignal's native library to load and
        // exercises a real curve operation across the JNI boundary.
        val keyPair = org.signal.libsignal.protocol.IdentityKeyPair.generate()

        val serialized = keyPair.serialize()
        assertTrue("serialized identity key pair must not be empty", serialized.isNotEmpty())

        // Round-tripping proves the native serialization/deserialization path is intact.
        val restored = org.signal.libsignal.protocol.IdentityKeyPair(serialized)
        assertArrayEquals(
            "identity key pair must survive a native serialize/deserialize round-trip",
            keyPair.publicKey.serialize(),
            restored.publicKey.serialize(),
        )

        val message = "mesh".toByteArray()
        val signature = keyPair.privateKey.calculateSignature(message)
        assertTrue(
            "native signature must verify",
            keyPair.publicKey.publicKey.verifySignature(message, signature),
        )
    }

    @Test
    fun installedApkBundlesDeviceAbiNativeLibraries() {
        // A resolved dependency is not proof of a packaged native library. Modern AGP keeps
        // .so files compressed inside the APK (extractNativeLibs=false), so nativeLibraryDir
        // is empty; instead scan the installed APK(s) for lib/<abi>/ entries matching one of
        // the device's supported ABIs. This catches per-ABI packaging drops (e.g. a missing
        // arm64-v8a split on the Samsung A22). It does not assert anything about R8, which is
        // disabled for this debug variant.
        val supportedAbis = android.os.Build.SUPPORTED_ABIS.toList()
        assertTrue("device must report at least one ABI", supportedAbis.isNotEmpty())

        val appInfo = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationInfo
        val apkPaths = buildList {
            add(appInfo.sourceDir)
            appInfo.splitSourceDirs?.let { addAll(it) }
        }

        val libEntries = mutableListOf<String>()
        for (apkPath in apkPaths) {
            java.util.zip.ZipFile(apkPath).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    .forEach { libEntries.add(it.name) }
            }
        }

        val abiMatches: (String) -> Boolean = { entry ->
            supportedAbis.any { abi -> entry.startsWith("lib/$abi/") }
        }
        assertTrue(
            "sqlcipher native library must be packaged for a supported ABI $supportedAbis, found: $libEntries",
            libEntries.any { it.contains("sqlcipher") && abiMatches(it) },
        )
        assertTrue(
            "libsignal native library must be packaged for a supported ABI $supportedAbis, found: $libEntries",
            libEntries.any { it.contains("signal_jni") && abiMatches(it) },
        )
    }
}
