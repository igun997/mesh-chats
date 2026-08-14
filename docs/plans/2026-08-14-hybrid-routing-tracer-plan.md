# Hybrid Routing Tracer Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Prove an opaque packet can discover and traverse a mixed BLE→Wi-Fi route with bounded forwarding and queue behavior in pure JVM tests.

**Architecture:** Add platform-free routing types under `core/routing`. Reactive discovery walks only live neighbor edges and returns a transport-per-hop route. Forwarding validates destination policy, expiry, hop budget and duplicate IDs before sending unchanged ciphertext. A bounded in-memory queue handles temporary route loss.

**Tech Stack:** Kotlin/JVM, JUnit 4, kotlinx-coroutines-test where needed; no Android APIs.

---

### Task 1: Routing and packet domain

**Files:**
- Create: `app/src/main/java/com/meshchats/app/core/routing/RoutingModels.kt`
- Create: `app/src/test/java/com/meshchats/app/core/routing/RoutingModelsTest.kt`

1. Write failing tests for route transport sequence, total latency, hop count, packet expiry, and immutable ciphertext copying.
2. Run focused test; confirm RED.
3. Add `NodeId`, `PacketId`, `PacketKind`, `RoutingProfile`, `LinkEdge`, `RouteHop`, `MeshRoute`, and `MeshPacket`.
4. Use defensive `ByteArray.copyOf()` at packet ingress/egress; do not rely on data-class ByteArray equality.
5. Run focused test; confirm GREEN.

### Task 2: Reactive mixed-route discovery

**Files:**
- Create: `app/src/main/java/com/meshchats/app/core/routing/ReactiveRouteDiscovery.kt`
- Create: `app/src/test/java/com/meshchats/app/core/routing/ReactiveRouteDiscoveryTest.kt`

1. Write failing tests for A─BLE─B─Wi-Fi─C, verified-destination requirement, cycle suppression, six-hop request limit, link removal, and deterministic tie breaking.
2. Add payload-aware edge costs: control/text balances reliability+energy; bulk heavily rewards Wi-Fi; tie prefers fewer hops, latency, stable node ID.
3. Return up to three routes; normal forwarding uses best. Add disjoint-route selection for SOS.
4. Run focused tests; confirm GREEN.

### Task 3: Safe forwarding

**Files:**
- Create: `app/src/main/java/com/meshchats/app/core/routing/PacketForwarder.kt`
- Create: `app/src/test/java/com/meshchats/app/core/routing/PacketForwarderTest.kt`

1. Write failing tests: exact ciphertext survives BLE→Wi-Fi, hops decrement once per relay, duplicate packet drops, expired packet drops, exhausted budget drops, route mismatch drops.
2. Implement `LinkSender` contract and bounded 10-minute duplicate filter.
3. Forward hop-by-hop without decrypting or modifying ciphertext/signature/destination tag.
4. Run focused tests; confirm GREEN.

### Task 4: Bounded relay queue

**Files:**
- Create: `app/src/main/java/com/meshchats/app/core/routing/RelayQueue.kt`
- Create: `app/src/test/java/com/meshchats/app/core/routing/RelayQueueTest.kt`

1. Write failing tests for 15-minute age cap, 5-MB byte cap, expired-first/earliest-expiry/oldest-arrival eviction, duplicate rejection, acknowledgement removal, and clear-on-disable.
2. Implement configurable bounds with production defaults; use monotonic injected clock for age.
3. Queue ciphertext only; expose counts/bytes, never payload text.
4. Run focused tests; confirm GREEN.

### Task 5: Three-node tracer integration

**Files:**
- Create: `app/src/test/java/com/meshchats/app/core/routing/HybridRoutingTracerTest.kt`

1. Build fake nodes A/B/C with A↔B BLE and B↔C Wi-Fi.
2. Prove A discovers C only when C is verified; B may remain unverified relay.
3. Prove packet sends BLE then Wi-Fi and ciphertext stays byte-identical.
4. Remove Wi-Fi, queue packet, restore Wi-Fi, rediscover, deliver, acknowledge, and empty queue.
5. Prove SOS selects independent routes when available.

### Task 6: Verification and commit

1. Run focused routing tests.
2. Run `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`.
3. Run complexity/security review: bounded graph exploration, queue, duplicates, packet sizes, and deterministic order.
4. Commit as `feat: add hybrid multi-transport routing tracer`.
