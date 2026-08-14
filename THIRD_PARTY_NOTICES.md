# Third-Party Notices

mesh-chats is licensed under the GNU Affero General Public License v3.0
(see `LICENSE`). This file records third-party components the project depends
on, and cryptographic components planned for future vendoring.

## Planned (not yet vendored)

- **libsignal** (Signal Messenger, LLC) — the Signal protocol library intended
  for end-to-end encryption of mesh payloads. It is **not yet vendored or
  depended on** by this repository. When it is introduced, its license
  (AGPL-3.0) and attribution will be recorded here and its source made
  available per that license.

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
