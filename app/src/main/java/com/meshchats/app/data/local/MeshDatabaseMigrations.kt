package com.meshchats.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit, non-destructive schema upgrade from v1 (messages only) to v2 (Signal
 * state, contact/device identity, and the ciphertext outbox).
 *
 * Every statement must reproduce Room's generated v2 schema exactly — column
 * order, affinities, `NOT NULL`, defaults, foreign keys, and index names — or
 * Room's post-migration `validateMigration` check will fail. The v1 `messages`
 * rows are preserved and only extended with additive columns carrying defaults
 * that match the current UI's steady state.
 *
 * There is no destructive fallback anywhere: a missing or failed migration must
 * surface loudly rather than drop user data.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // --- Extend messages additively. Defaults match the current UI: an existing
        //     message with no queued packet is DELIVERED, unexpired, no route/failure. ---
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `delivery_state` TEXT NOT NULL DEFAULT 'DELIVERED'")
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `packet_id` TEXT")
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `expires_at` INTEGER")
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `route_path` TEXT")
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `failure_reason` TEXT")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_packet_id` ON `messages` (`packet_id`)",
        )

        // --- device_identity (singleton) ---
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `device_identity` (" +
                "`id` INTEGER NOT NULL, " +
                "`public_key_x509` BLOB NOT NULL, " +
                "`fingerprint_sha256` BLOB NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`signal_public_binding` BLOB, " +
                "`signal_binding_signature` BLOB, " +
                "`binding_version` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )

        // --- contact_identities ---
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `contact_identities` (" +
                "`address` TEXT NOT NULL, " +
                "`public_key` BLOB NOT NULL, " +
                "`fingerprint_sha256` BLOB NOT NULL, " +
                "`trust_state` TEXT NOT NULL, " +
                "`first_seen_at` INTEGER NOT NULL, " +
                "`verified_at` INTEGER, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`address`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_contact_identities_trust_state` " +
                "ON `contact_identities` (`trust_state`)",
        )

        // --- signal_identity (singleton) ---
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `signal_identity` (" +
                "`id` INTEGER NOT NULL, " +
                "`registration_id` INTEGER NOT NULL, " +
                "`identity_key_pair` BLOB NOT NULL, " +
                "`schema_version` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )

        // --- signal_trusted_identities ---
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `signal_trusted_identities` (" +
                "`name` TEXT NOT NULL, " +
                "`device_id` INTEGER NOT NULL, " +
                "`identity_key` BLOB NOT NULL, " +
                "`schema_version` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`name`, `device_id`))",
        )

        // --- signal_sessions ---
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `signal_sessions` (" +
                "`name` TEXT NOT NULL, " +
                "`device_id` INTEGER NOT NULL, " +
                "`record` BLOB NOT NULL, " +
                "`schema_version` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`name`, `device_id`))",
        )

        // --- signal_prekeys ---
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `signal_prekeys` (" +
                "`prekey_id` INTEGER NOT NULL, " +
                "`record` BLOB NOT NULL, " +
                "`schema_version` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`prekey_id`))",
        )

        // --- signal_signed_prekeys ---
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `signal_signed_prekeys` (" +
                "`signed_prekey_id` INTEGER NOT NULL, " +
                "`record` BLOB NOT NULL, " +
                "`schema_version` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`signed_prekey_id`))",
        )

        // --- signal_kyber_prekeys ---
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `signal_kyber_prekeys` (" +
                "`kyber_prekey_id` INTEGER NOT NULL, " +
                "`record` BLOB NOT NULL, " +
                "`used` INTEGER NOT NULL, " +
                "`last_resort` INTEGER NOT NULL, " +
                "`schema_version` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`kyber_prekey_id`))",
        )

        // --- ciphertext_outbox (FK → messages, cascade) ---
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ciphertext_outbox` (" +
                "`packet_id` TEXT NOT NULL, " +
                "`message_id` TEXT NOT NULL, " +
                "`destination_address` TEXT NOT NULL, " +
                "`destination_device_id` INTEGER NOT NULL, " +
                "`ciphertext` BLOB NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`expires_at` INTEGER, " +
                "`priority` INTEGER NOT NULL, " +
                "`content_type` INTEGER NOT NULL, " +
                "`delivery_state` TEXT NOT NULL, " +
                "`attempt_count` INTEGER NOT NULL, " +
                "`next_attempt_at` INTEGER, " +
                "`route_metadata` TEXT, " +
                "PRIMARY KEY(`packet_id`), " +
                "FOREIGN KEY(`message_id`) REFERENCES `messages`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_ciphertext_outbox_packet_id` " +
                "ON `ciphertext_outbox` (`packet_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ciphertext_outbox_message_id` " +
                "ON `ciphertext_outbox` (`message_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ciphertext_outbox_delivery_state` " +
                "ON `ciphertext_outbox` (`delivery_state`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_ciphertext_outbox_delivery_state_priority_created_at` " +
                "ON `ciphertext_outbox` (`delivery_state` ASC, `priority` DESC, `created_at` ASC)",
        )

        // --- delivery_attempts (FK → ciphertext_outbox, cascade) ---
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `delivery_attempts` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`packet_id` TEXT NOT NULL, " +
                "`transport` TEXT NOT NULL, " +
                "`route` TEXT, " +
                "`attempted_at` INTEGER NOT NULL, " +
                "`completed_at` INTEGER, " +
                "`outcome` TEXT NOT NULL, " +
                "`failure_code` TEXT, " +
                "FOREIGN KEY(`packet_id`) REFERENCES `ciphertext_outbox`(`packet_id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_delivery_attempts_packet_id` " +
                "ON `delivery_attempts` (`packet_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_delivery_attempts_attempted_at` " +
                "ON `delivery_attempts` (`attempted_at`)",
        )
    }
}

/**
 * Explicit, non-destructive schema upgrade from v2 to v3. Adds the
 * `signal_kyber_base_keys` replay-state table (PQXDH Kyber base-key reuse
 * detection) with its foreign key to `signal_kyber_prekeys` (cascade) and the
 * exact-triple UNIQUE index plus the Kyber-id lookup index.
 *
 * Every statement reproduces Room's generated v3 schema exactly — column order,
 * affinities, `NOT NULL`, the autoincrement surrogate primary key, the foreign
 * key, and index names — or Room's post-migration `validateMigration` check will
 * fail. All v2 rows are untouched; this migration is purely additive.
 *
 * There is no destructive fallback: a missing or failed migration must surface
 * loudly rather than drop user data.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // --- signal_kyber_base_keys (FK → signal_kyber_prekeys, cascade) ---
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `signal_kyber_base_keys` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`kyber_prekey_id` INTEGER NOT NULL, " +
                "`signed_prekey_id` INTEGER NOT NULL, " +
                "`base_key` BLOB NOT NULL, " +
                "`first_seen_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`kyber_prekey_id`) REFERENCES `signal_kyber_prekeys`(`kyber_prekey_id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        // Exact-triple uniqueness at the DB level (concurrency-safe duplicate detection).
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_signal_kyber_base_keys_triple` " +
                "ON `signal_kyber_base_keys` (`kyber_prekey_id`, `signed_prekey_id`, `base_key`)",
        )
        // Kyber-id lookup index (cascade + audit queries).
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_signal_kyber_base_keys_kyber_prekey_id` " +
                "ON `signal_kyber_base_keys` (`kyber_prekey_id`)",
        )
    }
}
