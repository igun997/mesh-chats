# Hybrid Multi-Transport Mesh Routing Design

**Date:** 2026-08-14  
**Status:** Approved  
**Scope:** Transport-agnostic routing core, shared-LAN and Wi-Fi Direct links, BLE data-link upgrade, foreground Mesh mode

## 1. Goal

Mesh Chats must route an E2E-encrypted packet across different physical transports in one path. A device reachable over BLE may relay to a peer reachable over shared Wi-Fi, Wi-Fi Direct, LoRa, or the optional global relay.

```text
Phone A ──BLE── Phone B ──shared LAN── Phone C ──Wi-Fi Direct── Phone D
```

Relays never receive message keys or plaintext. No server stores messages. Local relay nodes may hold bounded ciphertext briefly for store-and-forward.

## 2. Decisions

- Any nearby node may relay opaque ciphertext, including unverified nodes.
- Only verified contacts may be multi-hop destinations.
- Relay queue limit: 15 minutes or 5 MB per device, whichever is reached first.
- Routing strategy: proactive one-hop neighbors plus reactive multi-hop route discovery.
- Route selection is payload-aware:
  - text prefers reliable, low-energy paths;
  - large payloads prefer Wi-Fi bandwidth;
  - SOS duplicates across independent available routes;
  - ties prefer fewer hops, lower latency, stronger links.
- Wi-Fi supports both shared LAN and Wi-Fi Direct. LAN is preferred when peers share a router/hotspot; Direct is fallback without infrastructure.
- Wi-Fi Aware is a later adapter. Samsung A22 test hardware exposes Wi-Fi Direct but not `android.hardware.wifi.aware`.
- Persistent routing runs only in explicit Mesh mode, implemented as an Android foreground service with visible notification. Mesh mode defaults OFF.

## 3. Architecture

All radios implement one link contract. Routing never depends on Android BLE, Wi-Fi, or LoRa classes.

```kotlin
interface MeshLink {
    val id: TransportId
    val neighbors: Flow<Set<Neighbor>>
    val incoming: Flow<ReceivedPacket>
    suspend fun send(nextHop: NodeId, packet: MeshPacket): SendResult
}
```

Core components:

- **MeshPacketCodec:** deterministic, versioned wire encoding.
- **RouteEngine:** maintains neighbor graph, discovers routes, selects paths, forwards packets, repairs failed paths.
- **RouteDiscovery:** bounded request/reply exchange for destinations absent from route cache.
- **DuplicateFilter:** time-bounded packet/request IDs; drops replay and routing loops.
- **RelayQueue:** encrypted packet storage capped by age and bytes; removes on acknowledgement.
- **LinkRegistry:** merges live links and reports capability/quality changes.
- **MeshForegroundService:** owns active links, route engine, notification, wake/network locks only while needed.
- **MessageOutbox:** encrypts once, submits opaque packet, observes queued/sent/delivered state.

First implementation uses in-memory fakes for `MeshLink` and `RelayQueue` to prove routing. Durable queue storage follows after packet semantics stabilize.

## 4. Routing model

A route is a sequence of transport-specific hops, not one transport plus a hop count.

```kotlin
data class RouteHop(
    val from: NodeId,
    val to: NodeId,
    val transport: TransportId,
    val latencyMs: Int,
    val linkQuality: Int,
)

data class MeshRoute(
    val destination: NodeId,
    val hops: List<RouteHop>,
    val expiresAtMillis: Long,
)
```

One-hop neighbors are learned proactively from each link. For a missing remote route, sender emits a signed route request containing a rotating blinded destination tag. Relays deduplicate request IDs, decrement hop budget, append only routing metrics, and forward. Destination returns a route reply on reverse breadcrumbs. Cache entries expire quickly and invalidate on link loss.

Initial bounds:

- Data packet hop limit: 8.
- Route-request hop limit: 6.
- Route cache TTL: 60 seconds.
- Duplicate packet/request window: 10 minutes.
- Maximum discovered routes per destination: 3.
- Maximum route requests per origin: 6/minute.

## 5. Packet and privacy envelope

Routing metadata remains separate from encrypted message payload.

```kotlin
data class MeshPacket(
    val version: UByte,
    val packetId: PacketId,
    val kind: PacketKind,
    val destinationTag: ByteArray,
    val expiresAtMillis: Long,
    val hopsRemaining: UByte,
    val ciphertext: ByteArray,
    val originSignature: ByteArray,
)
```

Relays may read only version, packet ID, kind, blinded destination tag, expiry, and remaining hop budget. Relays cannot read contact identity, conversation ID, author, message body, attachments, or final public key. Destination tags rotate by epoch and derive from a pairwise routing secret established when contacts are verified.

Initial contact verification and pairwise-secret establishment require a direct link. Unverified nodes can relay but cannot become a multi-hop destination.

Every forwarded packet keeps original ciphertext and origin signature. Relays never re-encrypt message content. Per-hop link encryption may additionally protect local metadata.

## 6. Relay queue

Queue accepts only syntactically valid, unexpired packets that pass size, signature, rate, and duplicate checks.

- Global cap: 5 MB.
- Packet age cap: 15 minutes.
- Eviction: expired first, then earliest expiry, then oldest arrival.
- Per-origin byte/rate quotas prevent one sender monopolizing storage.
- Delivery acknowledgement removes matching packet immediately.
- Panic wipe and Mesh-mode disable clear relay queue.
- App lock does not expose queue plaintext because queue contains ciphertext only.

## 7. Link adapters

### Shared LAN

- Ephemeral local service discovery, no stable device name or MAC identity.
- Authenticated direct socket after discovery.
- Works through existing router or user hotspot; no internet required.
- Prefer for throughput and battery when already connected.

### Wi-Fi Direct

- Used when no shared LAN route exists.
- Deterministic group-owner preference based on capability/stability, never identity.
- Authenticated socket over P2P group network.
- Android permission and user-consent UI remain explicit.

### BLE

- Existing non-connectable advertisement remains neighbor discovery.
- Add GATT service for control packets and fragmented data transfer.
- Backpressure and payload fragmentation respect negotiated MTU.
- BLE remains preferred for tiny low-energy control/text packets, not bulk transfer.

### LoRa and global relay

Both later implement the same `MeshLink` contract. LoRa enforces regional duty-cycle limits. Global relay remains optional, stores no message payload, and is disabled by Local Mesh Only.

## 8. Runtime and UX

Mesh mode has a persistent switch in Mesh/Settings. Enabling it starts a foreground service with visible notification showing active transports, peer count, relay queue bytes, and estimated battery cost. Disabling stops links and clears relay queue. System Bluetooth/Wi-Fi are never toggled directly; missing system capabilities link to Settings.

Per-message route metadata displays the actual mixed path, for example:

```text
mesh · BLE → WIFI · 2 hops · E2E
```

Queued packets show expiry and current reason (`waiting for Wi-Fi bridge`, `route repair`, `peer offline`). SOS shows each independent route used.

## 9. First tracer slice

Before real Wi-Fi or BLE data channels, a pure JVM simulation proves the architecture:

1. Create nodes A, B, C.
2. Link A↔B with fake BLE and B↔C with fake Wi-Fi.
3. Discover one-hop neighbors.
4. Resolve A→C reactive route.
5. Forward one opaque packet BLE→Wi-Fi without mutating ciphertext.
6. Deduplicate replay and reject exhausted hop budget.
7. Break Wi-Fi link, queue ciphertext, restore link, deliver, acknowledge, remove queue entry.
8. Verify 15-minute/5-MB eviction and unverified-relay/verified-destination policy.

Only after this passes do real shared-LAN and Wi-Fi Direct adapters begin.

## 10. Verification

- Property tests: codec round trips, ciphertext remains byte-identical through relays, hop count only decreases, queue never exceeds cap.
- Graph tests: mixed transports, cycles, route expiry, route repair, equal-cost tie breaking, SOS disjoint paths.
- Security tests: duplicate requests, replayed packets, invalid signatures, unverified destination rejection, rate limits, oversized packets.
- Integration tests: three in-process nodes and deterministic virtual clock.
- Device tests: two Android devices plus laptop/shared LAN; Wi-Fi Direct requires two compatible Android devices.

## 11. Non-goals for first tracer

- Production cryptography/key storage implementation.
- Real Android foreground service.
- Real BLE GATT or Wi-Fi sockets.
- Durable SQLCipher relay queue.
- Internet relay or LoRa adapter.
- Group chat fan-out.
