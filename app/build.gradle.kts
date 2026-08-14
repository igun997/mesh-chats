plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "com.meshchats.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.meshchats.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        // libsignal-android requires core library desugaring.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"

            // libsignal-client (the pure-JVM artifact) bundles desktop native binaries at
            // the jar root for Linux/macOS/Windows so the same jar works on server JVMs.
            // On Android these are dead weight (~180MB) and would otherwise be copied into
            // the APK as plain resources. Only the Android per-ABI lib/<abi>/libsignal_jni.so
            // from libsignal-android is needed at runtime, so drop every desktop variant.
            // These patterns are intentionally narrow: they match jar-root filenames only
            // and never touch lib/<abi>/*.so.
            excludes += "/libsignal_jni_aarch64.dylib"
            excludes += "/libsignal_jni_amd64.dylib"
            excludes += "/libsignal_jni_amd64.so"
            excludes += "/libsignal_jni_testing_aarch64.dylib"
            excludes += "/libsignal_jni_testing_amd64.dylib"
            excludes += "/libsignal_jni_testing_amd64.so"
            excludes += "/signal_jni_amd64.dll"
            excludes += "/signal_jni_testing_amd64.dll"
        }
        jniLibs {
            // libsignal-android ships a large test-only native library alongside the real
            // one. It must never reach a release APK (it adds hundreds of MB and is unused
            // in production), so exclude it from every variant.
            excludes += "**/libsignal_jni_testing.so"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Shared pure-JVM mesh protocol (routing types + packet codec)
    implementation(project(":mesh-protocol"))

    // Compose BOM keeps all compose artifacts on one version
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    // Foundation
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.window)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // UI: Compose + Material 3 + adaptive layouts
    implementation(libs.bundles.compose)
    implementation(libs.bundles.adaptive)
    implementation(libs.androidx.navigation.compose)

    // DI
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Data
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Cryptography: SQLCipher-backed storage + libsignal protocol + Bouncy Castle.
    // libsignal-android carries the Android native libraries; libsignal-client is
    // declared explicitly at the identical pinned version so the Java protocol
    // classes are on the compile classpath regardless of transitive resolution.
    implementation(libs.libsignal.android)
    implementation(libs.libsignal.client)
    implementation(libs.sqlcipher.android)
    implementation(libs.bouncycastle.bcprov)
    coreLibraryDesugaring(libs.android.desugar.jdk.libs)

    // Network + realtime transport
    implementation(libs.bundles.ktor)

    // UI kits
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.lottie.compose)
    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.accompanist.permissions)

    // Tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.mockk.android)
    kspAndroidTest(libs.hilt.compiler)
}
