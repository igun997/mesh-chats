# BLE Peer Discovery Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Replace simulated Bluetooth state with real foreground BLE advertising and peer discovery on Android 8+ while keeping message transport, background service, and cryptographic sessions out of scope.

**Architecture:** Add a pure Kotlin discovery protocol and peer registry, then put Android Bluetooth APIs behind a small `BleRadio` boundary. `BleDiscoveryController` owns permission/capability state, scan/advertise lifecycle, deduplication, and expiry. Existing mesh repository consumes controller state to replace only the fake Bluetooth transport and Bluetooth-only peers; Wi-Fi, LoRa, and relay remain simulated for UI iteration.

**Tech Stack:** Kotlin 2.4, coroutines/StateFlow, Android Bluetooth LE APIs, Hilt, Jetpack Compose Activity Result APIs, JUnit/MockK, API 26 minimum.

**Scope constraints:**
- Discovery runs only while Mesh screen is visible. No background scan or foreground service yet.
- Advertisement contains protocol version, capability flags, and an ephemeral 64-bit node ID. No display name, fingerprint words, location, or message content.
- No GATT connection or chat payload transfer in this slice.
- Android 12+ requests Nearby Devices permissions. API 26-30 requests fine location for scanning.
- Do not use Bluetooth MAC addresses as identity; BLE addresses can rotate.

---

### Task 1: Discovery payload codec and expiring peer registry

**Files:**
- Create: `app/src/main/java/com/meshchats/app/core/transport/ble/BleDiscoveryProtocol.kt`
- Create: `app/src/main/java/com/meshchats/app/core/transport/ble/DiscoveredBlePeerRegistry.kt`
- Test: `app/src/test/java/com/meshchats/app/core/transport/ble/BleDiscoveryProtocolTest.kt`
- Test: `app/src/test/java/com/meshchats/app/core/transport/ble/DiscoveredBlePeerRegistryTest.kt`

**Step 1: Write failing codec tests**

Cover deterministic encode/decode, malformed payload rejection, unknown protocol rejection, and capability flag round-trip. Payload layout:

```text
byte 0     protocol version = 1
byte 1     capability bits: chat=1, relay=2, lora=4, sos=8
bytes 2-9  unsigned 64-bit ephemeral node ID, big-endian
```

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*BleDiscoveryProtocolTest'
```

Expected: FAIL because codec does not exist.

**Step 2: Implement minimal codec**

Expose:

```kotlin
data class BleBeacon(val nodeId: Long, val capabilities: Set<BleCapability>)
object BleDiscoveryProtocol {
    const val VERSION: Byte = 1
    const val PAYLOAD_SIZE = 10
    fun encode(beacon: BleBeacon): ByteArray
    fun decode(payload: ByteArray): BleBeacon?
}
```

Strictly reject incorrect length or version.

**Step 3: Run codec tests**

Expected: PASS.

**Step 4: Write failing registry tests**

Cover deduplication by node ID, RSSI/last-seen update, no identity based on MAC address, and expiry after 30 seconds using injected `Clock`.

**Step 5: Implement minimal registry**

Expose immutable `DiscoveredBlePeer(nodeId, rssiDbm, lastSeenMillis, capabilities)` and `upsert`, `activePeers`, `expire`.

**Step 6: Run tests and commit**

```bash
./gradlew :app:testDebugUnitTest --tests '*ble*'
git add app/src/main/java/com/meshchats/app/core/transport/ble app/src/test/java/com/meshchats/app/core/transport/ble
git commit -m "feat: add BLE discovery protocol and peer registry"
```

---

### Task 2: Android BLE permissions and manifest contract

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/meshchats/app/core/transport/ble/BlePermissionPolicy.kt`
- Test: `app/src/test/java/com/meshchats/app/core/transport/ble/BlePermissionPolicyTest.kt`

**Step 1: Write failing permission matrix tests**

Expected policy:

```text
API 31+ scan+advertise: BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT
API 26-30 scan+advertise: ACCESS_FINE_LOCATION
```

Test `requiredPermissions(sdkInt)` as a pure function.

**Step 2: Run test and verify RED**

```bash
./gradlew :app:testDebugUnitTest --tests '*BlePermissionPolicyTest'
```

**Step 3: Implement policy and manifest declarations**

Manifest:

```xml
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

Do not set `neverForLocation`: Android documents that it can filter some BLE beacons, which conflicts with arbitrary mesh-node discovery.

**Step 4: Run tests/lint and commit**

```bash
./gradlew :app:testDebugUnitTest --tests '*BlePermissionPolicyTest' :app:lintDebug
git add app/src/main/AndroidManifest.xml app/src/main/java/com/meshchats/app/core/transport/ble/BlePermissionPolicy.kt app/src/test/java/com/meshchats/app/core/transport/ble/BlePermissionPolicyTest.kt
git commit -m "feat: define BLE permission policy"
```

---

### Task 3: Testable BLE scan/advertise controller

**Files:**
- Create: `app/src/main/java/com/meshchats/app/core/transport/ble/BleDiscoveryState.kt`
- Create: `app/src/main/java/com/meshchats/app/core/transport/ble/BleRadio.kt`
- Create: `app/src/main/java/com/meshchats/app/core/transport/ble/AndroidBleRadio.kt`
- Create: `app/src/main/java/com/meshchats/app/core/transport/ble/BleDiscoveryController.kt`
- Create: `app/src/main/java/com/meshchats/app/core/transport/ble/DefaultBleDiscoveryController.kt`
- Test: `app/src/test/java/com/meshchats/app/core/transport/ble/DefaultBleDiscoveryControllerTest.kt`

**Step 1: Write failing controller tests with fake `BleRadio`**

Cover:
- unsupported hardware → `Unsupported`
- missing permissions → `PermissionRequired`
- disabled adapter → `BluetoothOff`
- ready controller starts advertising and filtered scanning
- duplicate scan results update one peer
- stop always stops both scan and advertise
- radio exception becomes `Error` and does not crash process

Run and verify RED:

```bash
./gradlew :app:testDebugUnitTest --tests '*DefaultBleDiscoveryControllerTest'
```

**Step 2: Implement boundary and state machine**

```kotlin
sealed interface BleDiscoveryState {
    data object Unsupported
    data class PermissionRequired(val permissions: Set<String>)
    data object BluetoothOff
    data object Idle
    data class Scanning(val peers: List<DiscoveredBlePeer>)
    data class Error(val message: String)
}

interface BleRadio {
    val isSupported: Boolean
    fun isEnabled(): Boolean
    fun missingPermissions(): Set<String>
    fun start(serviceUuid: UUID, payload: ByteArray, onResult: (ByteArray, Int) -> Unit)
    fun stop()
}
```

`AndroidBleRadio` is a thin adapter over `BluetoothLeAdvertiser` and `BluetoothLeScanner`, uses a service UUID filter, low-power scan mode, balanced advertising, and catches `SecurityException`/illegal state at the controller boundary.

**Step 3: Add periodic peer expiry**

Controller runs one coroutine while active, expiring peers every 5 seconds. Stop cancels it.

**Step 4: Run tests and commit**

```bash
./gradlew :app:testDebugUnitTest --tests '*DefaultBleDiscoveryControllerTest'
git add app/src/main/java/com/meshchats/app/core/transport/ble app/src/test/java/com/meshchats/app/core/transport/ble
git commit -m "feat: add BLE discovery controller"
```

---

### Task 4: Integrate real BLE state into mesh repository

**Files:**
- Modify: `app/src/main/java/com/meshchats/app/core/mesh/FakeMeshStateRepository.kt`
- Modify: `app/src/main/java/com/meshchats/app/core/mesh/MeshModels.kt`
- Modify: `app/src/main/java/com/meshchats/app/di/MeshModule.kt`
- Test: `app/src/test/java/com/meshchats/app/core/mesh/BleMeshStateMapperTest.kt`

**Step 1: Write failing mapping tests**

Map discovery states to existing UI model:

```text
Unsupported       → BT Absent
PermissionRequired→ BT Off, detail "Nearby devices permission required"
BluetoothOff      → BT Off, detail "Bluetooth is off"
Idle              → BT Idle
Scanning([])      → BT Idle, detail "Scanning · no peers"
Scanning(peers)   → BT Active(peer count, throughput 0), discovered peer rows
Error             → BT Off, detail with bounded user-safe message
```

Discovered peers use `ble-<unsigned node id hex>` conversation IDs and generated 4-glyph monograms. Mark them unverified. Never expose BLE MAC address.

**Step 2: Implement mapper and repository collection**

Rename repository to `HybridMeshStateRepository` if practical; otherwise document that only non-BLE paths remain fake. Hilt binds controller/radio and repository as singletons.

**Step 3: Run tests and commit**

```bash
./gradlew :app:testDebugUnitTest --tests '*BleMeshStateMapperTest' :app:compileDebugKotlin
git add app/src/main/java/com/meshchats/app/core/mesh app/src/main/java/com/meshchats/app/di app/src/test/java/com/meshchats/app/core/mesh
git commit -m "feat: expose real BLE discovery in mesh state"
```

---

### Task 5: Permission and lifecycle UI

**Files:**
- Modify: `app/src/main/java/com/meshchats/app/ui/mesh/MeshViewModel.kt`
- Modify: `app/src/main/java/com/meshchats/app/ui/mesh/MeshScreen.kt`
- Modify: `app/src/main/java/com/meshchats/app/ui/components/MeshRadioCard.kt`
- Test: `app/src/test/java/com/meshchats/app/ui/mesh/MeshViewModelTest.kt`

**Step 1: Write failing ViewModel lifecycle tests**

Verify `onScreenStarted()` calls controller start, `onScreenStopped()` calls stop, and permission result retries start.

**Step 2: Implement ViewModel delegation**

Expose BLE state and required permission strings. Keep Android permission launcher in Compose, not ViewModel.

**Step 3: Implement Compose permission flow**

Use `rememberLauncherForActivityResult(RequestMultiplePermissions())`. Show one inline permission card with honest copy and `Grant` action. `DisposableEffect(Unit)` starts discovery when Mesh screen enters composition and stops when it leaves. Show `Turn Bluetooth on` action using `Settings.ACTION_BLUETOOTH_SETTINGS`; do not toggle Bluetooth programmatically.

Add explicit copy: `Scanning while this screen is open`. No background-service promise.

**Step 4: Run tests, lint, install and commit**

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:installDebug
adb shell am start -n com.meshchats.app.debug/com.meshchats.app.MainActivity
adb logcat -d -v brief | grep -E 'FATAL EXCEPTION|AndroidRuntime.*com.meshchats'
```

Expected: all tests pass, lint has zero findings, release build succeeds, app launches on SM-A225F without crash, Mesh tab prompts Nearby Devices permission then reports live BLE state.

```bash
git add app/src/main/java/com/meshchats/app/ui/mesh app/src/main/java/com/meshchats/app/ui/components app/src/test/java/com/meshchats/app/ui/mesh
git commit -m "feat: add BLE discovery permission UX"
```

---

### Task 6: Final review and device evidence

**Files:**
- Modify only files required by review findings.

**Step 1: Run complete verification**

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:installDebug
```

**Step 2: Device checks**

- Deny permission → Mesh card says permission required, no crash.
- Grant permission → BT card says scanning.
- Disable Bluetooth → card says Bluetooth off and links to settings.
- Re-enable Bluetooth → scan resumes after returning to Mesh.
- Navigate away → scan and advertising stop.
- Confirm no display name, message, location, fingerprint, or MAC address appears in advertisement payload/logs.

A second physical device running the same build is required to prove peer-to-peer discovery. If unavailable, report this limitation explicitly; unit/controller tests are not proof of RF interoperability.

**Step 3: Code review and final commit**

Request spec review, then code-quality review. Fix Important findings and rerun verification before reporting completion.
