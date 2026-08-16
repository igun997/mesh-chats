# Phase 2C: Verified Encrypted Send and Durable Outbox

> **REQUIRED SUB-SKILL:** Execute task-by-task with TDD, device gates, and review checkpoints.

**Goal:** Turn `MessageRepository.send` from prototype plaintext insertion into verified-contact Signal encryption plus atomic durable ciphertext outbox state.

## Security invariants

- Only VERIFIED contacts with an Ed25519-verified Signal binding may be destinations.
- Stable protocol address derives from full 32-byte fingerprint; never display/four-word/BLE ids.
- Prekey bundles are recipient-specific and expire; stale/consumed bundles trigger fresh-bundle retry, never trust downgrade.
- Signal ratchet mutation and outbox insertion commit in one SQLCipher Room transaction.
- Outbox stores only typed Signal ciphertext envelope; plaintext exists only in visible SQLCipher `messages.body`.
- Delivery workers lease bounded batches and transitions are compare-and-set/idempotent.

## Task 1: Room v4 contact binding, bundle cache, reservations

- Extend `contact_identities` with device id, Signal identity key, binding signature/version; migrated rows remain unusable for encryption until re-verified.
- Add `contact_prekey_bundles` storing deterministic encoded public bundle, received/expiry timestamps, FK cascade.
- Extend EC/Kyber prekey rows with nullable reservation address/device/time and unique recipient reservation indices. Kyber consumption clears non-last-resort reservation.
- Explicit `MIGRATION_3_4`, schema export, SQLCipher migration/DAO tests.

## Task 2: Verified contact repository

- Accept only `DeviceIdentityRepository.verifyScannedPayload(...)=Verified`.
- Recompute canonical address/fingerprint, store full Ed key + Signal binding + signature/version as VERIFIED transactionally.
- Identity change never silently replaces verified state: mark conflict/revoked and require explicit re-verification.
- Cache remote bundles only when contact is verified, bundle Signal identity matches binding, device matches, and freshness policy passes.

## Task 3: Recipient-specific bundle reservation and freshness

- Replace generic publication at production boundary with `createPublishedBundleFor(VerifiedSignalPeer)`.
- Reuse active unexpired reservation for same recipient; otherwise reserve oldest unreserved EC and Kyber one-time keys atomically. Last-resort Kyber remains shared fallback.
- Reservation TTL and bundle validity: 24 hours, future skew max 5 minutes.
- Engine rejects stale/future bundles before session processing. Expired reservations release during replenishment.

## Task 4: Signal ciphertext envelope codec

- Pure JVM deterministic `SignalCiphertextEnvelope`: version, PREKEY/WHISPER type, ciphertext bytes.
- Strict bounds/canonical decode/no throw, exact fixtures, truncation/mutation fuzzing, defensive arrays/redacted strings.

## Task 5: Atomic encrypted send

- Add blocking outbox insert surface for native callback transaction.
- Engine internal `encryptAndPersist` runs SessionCipher + supplied blocking ciphertext sink inside one transaction; sink failure rolls ratchet state back.
- `MessageRepository.send` validates body/contact, establishes from fresh cached bundle when needed, creates ids/timestamps, encrypts UTF-8 body, wraps ciphertext envelope, then atomically inserts queued visible message + outbox row in same ratchet transaction.
- Return bounded result: queued, contact unverified, session bundle needed/stale, body invalid, crypto/store failure. UI may ignore result initially but no prototype fallback is allowed.

## Task 6: Outbox delivery lifecycle

- Transactional bounded claim/lease: QUEUED/FAILED → SENDING with attempt row; expired → EXPIRED.
- Success transitions SENT then DELIVERED by receipt; retryable failure uses capped exponential backoff + deterministic jitter; terminal failure/expiry updates visible message state.
- Enforce sender queue caps (1,024 packets / 5 MiB) independently from 15-minute relay queue policy; deterministic eviction refuses new sends rather than dropping silently.
- Tests for concurrent claims, stale leases, retries, receipt idempotence, panic wipe/mesh disable interaction hooks.

## Task 7: Verification

Two isolated SQLCipher parties: verify contact QR binding, exchange recipient bundle, send through real repository, assert PREKEY ciphertext-only outbox, simulate delivery/decrypt, receipt lifecycle, second WHISPER send, close/reopen durability. Run protocol/JVM/full A22 device suite, lint, release/R8, spec/security review.
