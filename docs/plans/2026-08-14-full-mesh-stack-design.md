# Full Mesh Stack Design

**Date:** 2026-08-14  
**Status:** Approved  
**License decision:** AGPL-3.0; official Signal `libsignal` permitted  
**Included:** BLE, shared LAN, Wi-Fi Direct, global relay, 1:1 chats, offline Mesh Maps  
**Excluded:** Physical LoRa adapter (interface remains)

## 1. Product outcome

Mesh Chats becomes an end-to-end system rather than a visual prototype:

- devices discover and exchange frames over BLE, shared LAN, or Wi-Fi Direct;
- packets may traverse different transports at each hop;
- verified contacts exchange Signal-style E2E messages;
- unverified nodes may relay opaque ciphertext but cannot become multi-hop destinations;
- an included Ktor WebSocket relay forwards live frames without a message database;
- optional foreground Mesh mode keeps routing active with explicit notification;
- Mesh Maps shows verified peers, mixed routes, coarse shared positions, and SOS positions over offline maps;
- LoRa remains a disabled `MeshLink` extension point until hardware exists.

## 2. Repository modules

```text
:app                 Android UI, service, links, chat, maps
:mesh-protocol       Pure JVM packet codec, routing envelope, shared relay frames
:relay-server        Ktor JVM stateless WebSocket relay
```

The existing platform-free routing tracer moves from `:app` into `:mesh-protocol` so Android and relay tests share wire types. Relay server does not depend on `libsignal` and cannot decrypt payloads.

Add root `LICENSE` with AGPL-3.0 and third-party notices before adding official `libsignal` artifacts.

## 3. Common link contract

```kotlin
interface MeshLink {
    val id: TransportId
    val status: StateFlow<LinkStatus>
    val neighbors: Flow<Set<Neighbor>>
    val incoming: Flow<ReceivedPacket>
    suspend fun send(nextHop: NodeId, packet: MeshPacket): SendResult
    suspend fun start()
    suspend fun stop()
}
```

`LinkRegistry` owns enabled links. `RouteEngine` observes neighbor changes, uses proactive one-hop knowledge and reactive multi-hop discovery, scores routes by payload, and forwards per-hop without decrypting.

## 4. Wire protocol

Every frame is deterministic and versioned. Bounds are checked before allocation.

```text
magic(4) | version(1) | kind(1) | headerLength(2) | payloadLength(4)
packetId(16) | destinationTag(16) | expiry(8) | hopsRemaining(1)
originKeyId(16) | signatureLength(2) | signature | ciphertext
```

Frame kinds: HELLO, ROUTE_REQUEST, ROUTE_REPLY, DATA, ACK, LOCATION, SOS, PING.

- Ciphertext maximum: 1 MiB at protocol layer; each link fragments further.
- Data hop limit: 8; route requests: 6.
- Duplicate window: 10 minutes, bounded count.
- Unknown versions/kinds and oversized lengths fail closed.
- Route metadata never contains conversation ID, display name, phone number, or plaintext location.

## 5. Identity and E2E chats

- Generate device-local Ed25519 identity in Android Keystore-compatible encrypted storage.
- Human identity remains four-word fingerprint plus QR/read-aloud verification.
- Official `libsignal` provides X3DH/session establishment and Double Ratchet for 1:1 messages.
- Signal identity/prekey material is signed by the device Ed25519 identity, binding messaging keys to the verified fingerprint.
- Session records, prekeys, messages, outbox, receipts, and relay queue use SQLCipher-backed Room.
- Relay nodes and global relay see only packet envelope metadata and ciphertext.
- Multi-hop destination tags derive from pairwise routing secret and rotate by epoch.
- Sender retains encrypted outbox item until delivered/expired. Stateless global relay never stores offline messages.

Message states: queued, discovering route, sending, sent-to-next-hop, delivered, expired, failed. Receipts are signed/E2E and remove queued copies.

## 6. Android links

### BLE

Keep existing privacy-safe service-data advertisement for neighbor discovery. Add authenticated GATT service:

- control characteristic for HELLO, route, ACK, and flow control;
- data characteristic for framed fragments;
- negotiated MTU and sliding-window backpressure;
- central/peripheral role symmetry;
- fragment CRC plus whole-packet signature/authentication;
- no MAC address exposed as identity.

BLE toggle controls app discovery/data only. System Bluetooth remains user-owned.

### Shared LAN

- Discover ephemeral service instances over NSD/mDNS and a signed UDP fallback.
- Never publish display name or stable identity in service name.
- Establish authenticated TCP socket using Ed25519 challenge and ephemeral session keys.
- Works on router or user hotspot without internet.
- Prefer for bandwidth when already connected.

### Wi-Fi Direct

- API 33+: request `NEARBY_WIFI_DEVICES` with `neverForLocation`; legacy versions use location permission where required.
- Discover peers with `WifiP2pManager` only when LAN route absent or insufficient.
- Deterministic group-owner preference based on power/stability capability, not identity.
- Reuse LAN authenticated framed socket over P2P group network.
- Never programmatically toggle system Wi-Fi.

### Global relay

- Configurable `wss://` endpoint.
- Ed25519 challenge-response proves key possession; no account directory.
- Live session registry maps rotating destination tags to sockets in memory.
- Frames are bounded, rate-limited, expiry-checked, and forwarded only while recipient is connected.
- No message table, disk spool, analytics payload logging, or plaintext.
- Local Mesh Only immediately disconnects and disables relay.

## 7. Foreground Mesh mode

Mesh mode defaults OFF. Explicit enable starts `MeshForegroundService` and visible notification showing active links, peer count, queued bytes, and battery estimate.

Service owns `LinkRegistry`, route engine, relay queue, and link lifecycle. It uses foreground-service types/permissions required by current Android versions. Wake/network locks are short and operation-scoped. Disable or panic wipe stops service, links, sockets, and clears relay queue.

Without Mesh mode, links may run only during explicit foreground screens/actions.

## 8. Chat integration

`MessageRepository.send` no longer writes a fake delivered row. It:

1. validates verified contact and active Signal session;
2. encrypts body/attachment descriptor;
3. persists encrypted outbox row;
4. creates blinded destination packet;
5. asks route engine for up to three routes;
6. sends best payload-aware route, with fallback on failure;
7. updates UI from durable delivery state;
8. processes E2E acknowledgement and removes relay/outbox ciphertext according to retention policy.

SOS sends independent copies over disjoint routes. Normal messages never duplicate simultaneously unless failover occurs.

Route UI displays actual mixed path, e.g. `BLE → WIFI · 2 hops · E2E`.

## 9. Mesh Maps

MapLibre replaces radar only when an offline map region covers current viewport. Radar remains fallback.

Offline sources:

- provider-agnostic style/tile URL configured locally, with credentials stored outside source control;
- MapLibre offline-region download with quota/progress/cancel/delete;
- local PMTiles import where supported by MapLibre protocol integration;
- MBTiles import through validated local tile-store adapter;
- no reliance on OpenStreetMap standard tile servers for bulk offline download.

Mesh overlays:

- verified contacts only;
- normal sharing quantized to coarse grid and E2E encrypted;
- exact coordinates only during explicit share session or SOS;
- marker age, uncertainty radius, and route freshness;
- mixed-transport route lines with monochrome patterns per transport;
- SOS marker and independent route list;
- expired location removed automatically.

Location permission is contextual. Normal coarse sharing requests coarse access. Exact share/SOS requests fine access only when activated. No unverified peer receives location.

## 10. Storage and limits

- SQLCipher Room for messages, Signal state, outbox, map metadata, and relay queue.
- Imported map files remain app-private and are size/hash validated.
- Relay queue: 15 minutes, 5 MiB, 1,024 packets.
- Map quota configurable with preflight free-space check.
- Attachments are chunked, encrypted, resumable, and size-limited.
- Panic wipe deletes keys first, then databases/files.

## 11. Delivery phases

1. AGPL license, module split, production packet codec.
2. Identity store, libsignal integration, encrypted Room schema.
3. Foreground Mesh service and link registry.
4. Shared-LAN link with two-device/laptop tests.
5. BLE GATT data link with two-Android tests.
6. Wi-Fi Direct fallback with two-Android tests.
7. Stateless Ktor relay server and client.
8. Durable outbox/receipts wired into chats.
9. MapLibre offline download/import.
10. E2E coarse/exact location and mesh route overlays.
11. Security, battery, chaos, migration, and release hardening.

Each phase is a vertical slice with TDD, spec review, code review, device proof, and its own commit(s). Later phases do not bypass failed earlier gates.

## 12. Verification matrix

- JVM: codec properties/fuzzing, routing, queue, rate limits, packet invariants.
- Android unit: permission policy, service state machine, repositories, migration.
- Instrumentation: SQLCipher persistence, process restart, foreground service, map imports.
- RF: two Android devices for BLE GATT and Wi-Fi Direct; laptop for LAN/relay interoperability.
- Chaos: link loss per hop, duplicate/reordered fragments, route cycles, process death, disk full, corrupt DB/map, clock jumps.
- Security: invalid signatures/prekeys, replay, oversized frames, destination-tag probing, relay flooding, unverified destination, panic wipe.
- Maps: offline-only airplane mode, imported region, provider download, coarse/exact privacy, expired marker removal.

## 13. Explicit non-goals

- LoRa hardware implementation.
- Group chats in first production release.
- Server-side offline message storage.
- Phone-number/account directory.
- Background location unless explicit active sharing/SOS requires it.
- Custom cryptographic primitives.
