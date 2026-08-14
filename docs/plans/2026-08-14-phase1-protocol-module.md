# Phase 1: Shared Protocol Module Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Establish AGPL licensing and a pure JVM `:mesh-protocol` module containing shared transport/routing types plus a deterministic bounded packet codec.

**Architecture:** Android app and future Ktor relay consume one JVM protocol module. Move platform-free routing code/tests out of `:app`; keep a compatibility typealias for app mesh `TransportId` if needed to avoid broad churn. Packet codec validates header and lengths before allocation and fails closed.

**Tech Stack:** Gradle Kotlin DSL, Kotlin/JVM 2.4, JUnit 4, no Android APIs or serialization framework.

---

### Task 1: License and module skeleton

- Add root `LICENSE` containing official AGPL-3.0 text and `THIRD_PARTY_NOTICES.md` placeholder.
- Add `:mesh-protocol` to `settings.gradle.kts`.
- Create `mesh-protocol/build.gradle.kts` with Kotlin JVM and JUnit.
- Add `implementation(project(":mesh-protocol"))` to app.
- Add a JVM smoke test; confirm module has no Android dependency.

### Task 2: Move shared routing core

- Move `core/routing` production/tests from app to `mesh-protocol` under `com.meshchats.protocol.routing`.
- Move shared transport enum to protocol or provide a dependency-safe protocol transport enum with app adapter/typealias.
- Update Android imports and routing tests.
- Run protocol and app unit tests; preserve all routing behavior.

### Task 3: Versioned packet codec

- Add `MeshPacketCodec` and `PacketCodecResult`.
- Encode/decode deterministic big-endian frame: magic/version/kind/header length/payload length, packet ID, destination tag, expiry, hop budget, origin key ID, signature, ciphertext.
- Reject unknown magic/version/kind, truncated fields, trailing bytes, negative/overflow lengths, signature above bound, ciphertext above 1 MiB, and total frame above bound before allocation.
- Add round-trip tests for every kind, exact fixture bytes, boundary sizes, defensive copies, malformed/fuzz corpus, and deterministic output.

### Task 4: Verification

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew \
  :mesh-protocol:test \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleRelease
```

Review module boundaries and parser allocation safety. Commit as `feat: add shared mesh protocol module`.
