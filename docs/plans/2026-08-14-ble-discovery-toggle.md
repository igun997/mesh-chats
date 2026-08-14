# BLE Discovery Toggle Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Restore a persistent Bluetooth discovery switch while preserving foreground-only scanning and honest monochrome status.

**Architecture:** Store user intent in DataStore through a small `BleDiscoverySettings` abstraction. `MeshViewModel` combines saved intent with screen lifecycle to start/stop the controller; `HybridMeshStateRepository` combines the same intent with controller state so disabled BLE is shown as Off and BLE peers disappear. System Bluetooth is never changed.

**Tech Stack:** Kotlin 2.4, Jetpack Compose, DataStore Preferences, StateFlow, Hilt, JUnit/coroutines-test.

---

### Task 1: Persistent BLE discovery preference

**Files:**
- Create: `app/src/main/java/com/meshchats/app/core/transport/ble/BleDiscoverySettings.kt`
- Create: `app/src/test/java/com/meshchats/app/core/transport/ble/DataStoreBleDiscoverySettingsTest.kt`
- Modify: `app/src/main/java/com/meshchats/app/di/MeshModule.kt`

1. Write failing tests for default enabled, persisted disabled, and restored disabled state.
2. Run focused test and confirm RED.
3. Implement loading-aware state (`loaded`, `enabled`) backed by DataStore key `ble_discovery_enabled`.
4. Bind singleton in Hilt.
5. Run focused test and confirm GREEN.

### Task 2: Gate controller by preference and screen lifecycle

**Files:**
- Modify: `app/src/main/java/com/meshchats/app/ui/mesh/MeshViewModel.kt`
- Modify: `app/src/test/java/com/meshchats/app/ui/mesh/MeshViewModelTest.kt`

1. Write failing tests: disabled preference prevents start; disabling while visible stops; enabling while visible starts; enabling while hidden does not start; lifecycle stop always stops.
2. Run focused test and confirm RED.
3. Add settings collection and `setBleDiscoveryEnabled`; controller starts only when preference is loaded+enabled and screen is STARTED.
4. Run focused test and confirm GREEN.

### Task 3: Expose honest disabled state

**Files:**
- Modify: `app/src/main/java/com/meshchats/app/core/mesh/HybridMeshStateRepository.kt`
- Modify: `app/src/test/java/com/meshchats/app/core/mesh/HybridMeshStateRepositoryTest.kt`

1. Write failing tests: disabled setting maps BT to Off and removes BLE-only peers; re-enable resumes controller-derived mapping.
2. Run focused test and confirm RED.
3. Combine controller state with BLE setting in repository overlay.
4. Run focused test and confirm GREEN.

### Task 4: Restore switch and white enabled glyphs

**Files:**
- Modify: `app/src/main/java/com/meshchats/app/ui/mesh/MeshScreen.kt`
- Modify: `app/src/main/java/com/meshchats/app/ui/components/MeshRadioCard.kt`
- Modify: `app/src/main/java/com/meshchats/app/ui/components/TransportGlyph.kt`
- Modify: `docs/plans/2026-08-13-mesh-chats-ui-design.md`

1. Render BLE switch like other available transports and route it to `setBleDiscoveryEnabled`.
2. Hide permission/settings action cards while BLE discovery is user-disabled.
3. Render Idle/Scanning transport glyphs as white outline, Active as white filled, Off/Absent dim struck.
4. Update comments and design contract.

### Task 5: Verification

1. Run `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:installDebug`.
2. Device: switch BLE OFF, verify header glyph becomes dim+struck and laptop no longer receives phone beacon.
3. Restart app, verify BLE remains OFF.
4. Switch ON, verify scanning resumes only on Mesh screen and laptop/phone discover each other.
5. Commit as `feat: restore persistent BLE discovery toggle`.
