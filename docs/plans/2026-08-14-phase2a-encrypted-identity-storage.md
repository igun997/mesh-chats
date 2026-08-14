# Phase 2A: Encrypted Identity and Storage Foundation

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Add verified cryptographic dependencies, SQLCipher-backed Room storage, device Ed25519 identity, and durable schema required by libsignal/outbox work.

**Architecture:** A random 256-bit database key is wrapped by non-exportable Android Keystore AES-GCM and stored in `noBackupFilesDir`. Existing plaintext Room databases are exported to an encrypted temporary database, verified, and atomically swapped. Device Ed25519 private bytes are independently wrapped. Room v2 stores opaque libsignal records and ciphertext-only outbox rows; no Signal API adapter yet.

**Pinned dependencies:** `org.signal:libsignal-android:0.100.0`, `org.signal:libsignal-client:0.100.0`, `net.zetetic:sqlcipher-android:4.17.0`, `org.bouncycastle:bcprov-jdk18on:1.85.2`.

---

### Task 1: Dependencies, licensing, ABI proof

- Add Signal Maven repository and pin both libsignal artifacts to identical 0.100.0.
- Add SQLCipher and Bouncy Castle versions to catalog.
- Update `THIRD_PARTY_NOTICES.md` from planned to included with licenses/source URLs.
- Add instrumentation smoke test loading SQLCipher native library and libsignal native class on device.
- Verify release/R8 and installed APK ABI on Samsung A22.

### Task 2: Keystore-wrapped secrets

- Create `SecretWrapper` interface and Android AES/GCM Keystore implementation.
- Create atomic versioned wrapped-secret file format: magic/version/nonce/ciphertext.
- Create `DatabaseKeyProvider` generating 32 random bytes once, wrapping into `noBackupFilesDir`, reopening across instances, failing closed on tamper/key loss.
- Add JVM tests for format/tamper using fake wrapper and Android instrumentation tests for Keystore recreation.

### Task 3: SQLCipher migration/opening

- Add `SupportOpenHelperFactory` with `sqlcipher-android`; load native library explicitly.
- Detect plaintext SQLite header before Room open.
- Export plaintext DB to encrypted temp using SQLCipher export, verify key/schema/row counts, fsync, atomically replace; preserve original on every failure.
- Handle WAL/SHM safely and keep recoverable migration marker.
- Add instrumentation tests: plaintext v1 survives, encrypted header differs, wrong key fails, interrupted migration preserves source.

### Task 4: Room v2 durable schema

Add entities/DAOs for:

- device identity and signed Signal binding;
- contact identity/trust state;
- Signal identity/session/prekey/signed-prekey/Kyber-prekey opaque blobs with schema versions;
- ciphertext outbox and delivery attempts.

Extend messages additively with delivery state, packet ID, expiry, route path, failure reason. Add explicit `MIGRATION_1_2`; remove destructive upgrade paths. Add transactional DAO method inserting queued visible message plus ciphertext outbox atomically. Test migration and DAO operations.

### Task 5: Ed25519 identity repository

- Generate Ed25519 with isolated Bouncy Castle provider.
- Wrap PKCS#8 private bytes independently of SQLCipher; persist public X.509 bytes and signed binding metadata.
- Fingerprint = SHA-256(public key); four-word display is explicitly short/non-authoritative; QR payload includes full public key and binding.
- Create-once, reopen, sign/verify, tamper, key-loss, and fixed-vector tests.
- Never replace rotating BLE discovery IDs with stable identity.

### Task 6: Backup/panic policy

- Set `android:allowBackup="false"`.
- Exclude database/files/shared preferences from existing extraction XML defensively.
- Add merged-manifest/resource policy tests.
- Define key-first panic wipe API hook; actual UI wiring later.

### Task 7: Verification

Run protocol, app JVM, instrumentation on Samsung A22, lint, release/R8, install. Review migration crash safety, private-key exposure, logs/backups, native ABI packaging. Commit as `feat: add encrypted identity storage foundation`.
