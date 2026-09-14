package london.aipartner.echo.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        RecordingEntity::class,
        TranscriptRevisionEntity::class,
        TranscriptSegmentEntity::class,
        AiArtifactEntity::class,
        EmbeddingEntity::class,
        ConsentRecordEntity::class,
        SyncRefEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class EchoDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun aiArtifactDao(): AiArtifactDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun consentRecordDao(): ConsentRecordDao
    abstract fun syncRefDao(): SyncRefDao

    companion object {
        const val DB_NAME = "echo.db"

        /**
         * v1 → v2 (Phase 5): add the `embeddings` table for on-device semantic
         * search. **NON-DESTRUCTIVE BY CONSTRUCTION** — it only `CREATE TABLE`s a
         * new, additive table; it touches **no existing row** of recordings,
         * transcripts, AI artifacts, consent records, or sync refs. A user
         * upgrading from v1 keeps every recording and transcript intact; the
         * embeddings table simply starts empty and is (re)built lazily from the
         * existing transcripts (embeddings are a regenerable derivation, never
         * source-of-truth). There is deliberately **no** `fallbackToDestructive
         * Migration` anywhere — a missing/failed migration must crash loudly in
         * dev, never silently wipe a real user's library.
         *
         * The CREATE TABLE statement matches Room's generated schema exactly
         * (verified by [EmbeddingMigrationTest], which migrates a pre-populated v1
         * DB and lets Room validate the resulting schema on open).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `embeddings` (" +
                        "`recordingId` TEXT NOT NULL, " +
                        "`model` TEXT NOT NULL, " +
                        "`dim` INTEGER NOT NULL, " +
                        "`vector` BLOB NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`recordingId`), " +
                        "FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }

        /**
         * v2 → v3 (Phase 6 Step 4): add the `transcriptionStatus` column to
         * `recordings` so a recording's machine-transcription lifecycle (PENDING /
         * RUNNING / DONE / FAILED) is durable and honest. **NON-DESTRUCTIVE BY
         * CONSTRUCTION** — a single `ALTER TABLE … ADD COLUMN` with a `NOT NULL DEFAULT
         * 'PENDING'`, so every existing recording keeps all its data and simply reads as
         * PENDING (then re-transcribes on demand). Touches no other table, no other row.
         * No `fallbackToDestructiveMigration` anywhere — a missing migration crashes
         * loudly in dev, never wipes a real library. The default matches the Room schema
         * (`RecordingEntity.transcriptionStatus` default), validated on open.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `recordings` ADD COLUMN `transcriptionStatus` " +
                        "TEXT NOT NULL DEFAULT 'PENDING'",
                )
            }
        }

        /**
         * v3 → v4 (Phase 7 Layer 2): add the nullable `detectedLanguageTag` column to
         * `recordings` so a suspected mis-decode's auto-detected language is durable and the
         * UI can name it ("sounds like Slovenian"). **NON-DESTRUCTIVE BY CONSTRUCTION** — a
         * single `ALTER TABLE … ADD COLUMN` with no default (nullable), so every existing row
         * keeps all its data and simply reads `null` (no detection yet). Touches no other table.
         * No `fallbackToDestructiveMigration` — a missing migration crashes loudly, never wipes.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `recordings` ADD COLUMN `detectedLanguageTag` TEXT",
                )
            }
        }

        /**
         * v4 → v5 (Phase 10): add the `transcriptionAttempts` column to `recordings` so a
         * deterministically-crashing decode can be quarantined after N attempts instead of
         * looping forever (WorkManager reschedules a worker whose process died mid-decode).
         * **NON-DESTRUCTIVE BY CONSTRUCTION** — a single `ALTER TABLE … ADD COLUMN` with
         * `NOT NULL DEFAULT 0`, so every existing row keeps all its data and reads 0 attempts.
         * Touches no other table. No `fallbackToDestructiveMigration` — a missing migration
         * crashes loudly, never wipes.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `recordings` ADD COLUMN `transcriptionAttempts` " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v5 → v6 (item 4, rename + smart titles): add the nullable `userTitle` column to
         * `recordings` so a user's MANUAL rename is durable and provenance-bearing — it lives on
         * the recording row and is never overwritten by re-transcription or smart-title generation.
         * **NON-DESTRUCTIVE BY CONSTRUCTION** — a single `ALTER TABLE … ADD COLUMN` with no default
         * (nullable), so every existing row keeps all its data and reads `null` (no manual title →
         * falls through to the smart/date+time title). Touches no other table. No
         * `fallbackToDestructiveMigration` — a missing migration crashes loudly, never wipes.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `userTitle` TEXT")
            }
        }

        /**
         * Builds the encrypted database. The passphrase is a Keystore-wrapped
         * key (see [DbPassphraseProvider]) — never a literal, never logged. The
         * on-disk file is a SQLCipher database, not a readable plaintext SQLite
         * file.
         */
        fun build(context: Context, passphrase: ByteArray): EchoDatabase {
            // SQLCipher's native libs must be loaded before the factory is used.
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(context, EchoDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                )
                .build()
        }
    }
}
