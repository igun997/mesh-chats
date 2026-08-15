# Phase 2A Storage Startup Gate

**Goal:** Keep SQLCipher load, Keystore unwrap, and plaintext migration off UI thread; surface fail-closed storage errors without crash loops.

## Design

Add singleton `DatabaseStartupCoordinator` owning `StateFlow<DatabaseStartupState>`: `Idle`, `Initializing`, `Ready`, or `Failed(reason)`. Initialization resolves and force-opens `Provider<MeshDatabase>` only inside injected `Dispatchers.IO`, catches wrapped `EncryptedDatabaseException` by bounded cause traversal, and never destroys/regenerates data. Calls serialize and retry safely.

Add `StorageStartupViewModel` that starts initialization. Gate `MeshChatsApp`: show monochrome loading state while initializing, bounded recovery state on `KEY_UNAVAILABLE`/`MIGRATION_FAILED`/unexpected failure, and construct existing shell/navigation/Hilt chat ViewModels only after `Ready`. Recovery offers retry only; destructive reset/panic wipe requires separate explicit UX.

Tests prove database resolver executes off caller/main dispatcher, concurrent starts initialize once, retry works after failure, wrapped errors classify correctly, and existing navigation is not composed before ready. Device test proves app launches and encrypted DB opens.
