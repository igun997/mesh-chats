# Third-Party Notices

mesh-chats is licensed under the GNU Affero General Public License v3.0
(see `LICENSE`). This file records third-party components the project depends
on.

## Cryptographic components

The end-to-end encryption and encrypted-storage foundation depends on the
following components. Versions are pinned in `gradle/libs.versions.toml`.

- **libsignal** (Signal Messenger, LLC) — the Signal protocol library used for
  end-to-end encryption of mesh payloads. Consumed as the pinned artifacts
  `org.signal:libsignal-android:0.100.0` (Android native libraries) and
  `org.signal:libsignal-client:0.100.0` (Java protocol classes), both at the
  identical version. Licensed under the **AGPL-3.0**. These artifacts are
  published in Signal's official Maven repository
  (`https://build-artifacts.signal.org/libraries/maven/`), not Maven Central.
  Source: https://github.com/signalapp/libsignal (tag `v0.100.0`).

- **SQLCipher for Android** (Zetetic, LLC) — transparent 256-bit AES
  encryption for the Room/SQLite database. Consumed as
  `net.zetetic:sqlcipher-android:4.17.0`. Licensed under a **BSD-style
  license**. Source: https://github.com/sqlcipher/sqlcipher-android.

- **Bouncy Castle** (The Legion of the Bouncy Castle Inc.) — provider used for
  isolated Ed25519 key generation and signing. Consumed as
  `org.bouncycastle:bcprov-jdk18on:1.85.2`. Licensed under the **MIT-style
  Bouncy Castle license**. Source: https://github.com/bcgit/bc-java.

- **Android core library desugaring** (Google) — `com.android.tools:desugar_jdk_libs:2.1.5`,
  required by libsignal-android to backport `java.time`/`java.nio` APIs to the
  project's `minSdk`. Licensed under the **GNU GPL v2 with Classpath
  Exception**. Source: https://github.com/google/desugar_jdk_libs.

## Bundled data

- **BIP-39 English word list** — a fixed 2048-word English list bundled as the
  checked-in resource `app/src/main/resources/com/meshchats/app/crypto/fourword-english.txt`
  (SHA-256 `2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda`).
  It is used **only** to render the short, non-authoritative four-word display
  of a device fingerprint (see `FourWordFingerprint`); it is not used as a
  BIP-39 mnemonic and carries no checksum semantics here. The list originates
  from BIP-39 (Bitcoin Improvement Proposal 39) and its reference word lists,
  which are released into the **public domain (Creative Commons CC0 1.0)**.
  Source: https://github.com/bitcoin/bips/tree/master/bip-0039.

## Current dependencies

The Android application module (`:app`) depends on major third-party libraries
declared in `gradle/libs.versions.toml`, including:

- AndroidX (core, lifecycle, activity, window, navigation, DataStore, Room,
  Paging) — Apache License 2.0
- Jetpack Compose and Material 3 (via the Compose BoM) — Apache License 2.0
- Dagger Hilt — Apache License 2.0
- Kotlin standard library and kotlinx (coroutines, serialization) — Apache
  License 2.0
- Ktor client (HTTP + WebSocket) — Apache License 2.0
- Coil — Apache License 2.0
- Lottie for Android — Apache License 2.0
- Haze — Apache License 2.0
- Accompanist — Apache License 2.0
- JUnit 4 — Eclipse Public License 1.0 (test only)
- MockK, Turbine — Apache License 2.0 (test only)

The shared protocol module (`:mesh-protocol`) is pure Kotlin/JVM and depends
only on the Kotlin standard library (Apache License 2.0) and, for tests,
JUnit 4 (Eclipse Public License 1.0).

Refer to `gradle/libs.versions.toml` for the exact, pinned versions in use.
Individual library licenses are distributed with their respective artifacts.
