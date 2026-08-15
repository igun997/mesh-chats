# Phase 2B: libsignal PQXDH Sessions and Double Ratchet

> **REQUIRED SUB-SKILL:** Use executing-plans task-by-task with TDD and review checkpoints.

**Goal:** Implement app-owned libsignal 0.100.0 adapters, PQXDH prekey publication/session establishment, and durable 1:1 Double Ratchet encryption/decryption.

**Boundary:** This phase proves crypto/session state. Chat `MessageRepository.send` and ciphertext outbox wiring remain Phase 2C.

## Architecture

`SignalCryptoEngine` exposes bounded suspend APIs and app-owned DTOs; no UI/transport code imports libsignal. Every native libsignal operation runs on a dedicated single-parallelism crypto dispatcher and inside a Room transaction because libsignal store callbacks are synchronous. `RoomSignalProtocolStore` uses blocking DAO methods only inside that dispatcher/transaction. Corrupt/missing records become bounded engine failures.

Protocol addresses use full stable app-identity IDs plus device id; rotating BLE IDs are forbidden. Unknown Signal identities use TOFU inside libsignal, but session establishment is called only after app-level verified-contact policy passes at higher layer.

## Task 1: Room v3 replay state

- Add `signal_kyber_base_keys` keyed by Kyber id + signed-prekey id + serialized base key.
- Explicit `MIGRATION_2_3`, schema export, indexes/FKs, migration/device tests.
- Atomic DAO operation inserts seen base key and marks Kyber key used; duplicate maps to libsignal `ReusedBaseKeyException`.

## Task 2: Exact Room store adapters

Implement 0.100.0 `IdentityKeyStore`, `SessionStore`, `PreKeyStore`, `SignedPreKeyStore`, and `KyberPreKeyStore` using exact `javap` signatures. Match official in-memory semantics:

- absent session returns null; bulk load throws `NoSessionException` if any absent;
- unknown identity trusted; changed identity untrusted; `saveIdentity` returns `IdentityChange`;
- invalid/missing records throw expected bounded libsignal exceptions;
- records persist only `serialize()` bytes;
- Kyber base-key reuse detection is transactional.

No group `SenderKeyStore` implementation yet.

## Task 3: Publishable PQXDH bundle codec

Add pure JVM `PublishedPreKeyBundle` and strict deterministic codec to `:mesh-protocol`: registration/device ids, optional one-time EC prekey, signed EC prekey/signature, Signal identity key, Kyber prekey/signature, issued-at. Bound every field/total size; malformed input never throws. Add exact fixtures, boundaries, mutation fuzzing.

## Task 4: Prekey inventory

Create initial inventory idempotently in one transaction:

- one active signed EC prekey;
- bounded one-time EC prekeys;
- bounded one-time Kyber-1024 prekeys;
- one reusable last-resort Kyber-1024 prekey.

Use collision-checked random positive ids. Sign serialized EC/KEM public keys with local Signal identity private key. Publish unused one-time keys first, then last-resort Kyber. Never publish used Kyber keys. Add inventory threshold/replenishment API; rotation scheduling later.

## Task 5: Signal crypto engine

App-owned APIs:

- ensure/replenish local prekeys;
- create published bundle;
- establish outbound session from verified remote bundle;
- encrypt to typed serialized Signal ciphertext;
- decrypt PREKEY/WHISPER ciphertext.

Use constructors requiring local and remote `SignalProtocolAddress`. Validate stable names/device ids and payload bounds. Map libsignal exceptions to bounded errors (untrusted identity, no session, duplicate, malformed, missing prekey, reused base key, corrupt store, unavailable). Never log plaintext/key/ciphertext.

## Task 6: Alice/Bob proof

On Samsung A22 with two isolated SQLCipher Room databases:

- generate identities and Bob PQXDH bundle;
- Alice establishes and sends PREKEY message;
- Bob decrypts, consumes EC prekey, marks Kyber used/base key;
- Bob replies WHISPER; Alice decrypts;
- bidirectional multiple messages and out-of-order delivery work;
- duplicate/tamper/identity substitution fail closed;
- session survives database close/reopen;
- last-resort Kyber remains available while duplicate base key is rejected.

Run protocol/app JVM/device suites, lint, release/R8. Security/spec review before phase close.
