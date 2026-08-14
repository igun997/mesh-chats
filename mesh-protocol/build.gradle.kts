plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM shared protocol module. Contains no Android or serialization
// framework dependencies so it can be consumed by both the Android app and a
// future Ktor relay, and proven in fast JVM unit tests.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test> {
    useJUnit()
}
