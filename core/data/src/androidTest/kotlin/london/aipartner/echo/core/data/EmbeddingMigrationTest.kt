package london.aipartner.echo.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one data-risky step of Phase 5, proven non-destructive on a **pre-populated
 * encrypted** database.
 *
 * Creates a real v1 SQLCipher DB (from the committed `1.json` schema) with an
 * existing recording + transcript, runs [EchoDatabase.MIGRATION_1_2], and asserts:
 *  1. **No data loss** — the recording and transcript rows survive verbatim.
 *  2. The new `embeddings` table exists and starts **empty** (embeddings are a
 *     regenerable derivation, not source-of-truth — a fresh upgrade simply has
 *     none yet and rebuilds them lazily from the surviving transcripts).
 *  3. The table is **rebuildable** — an embedding for the surviving recording
 *     inserts cleanly (the FK to `recordings` holds post-migration).
 *
 * `runMigrationsAndValidate` additionally makes Room validate the post-migration
 * schema against the generated `2.json`, so a migration SQL that drifts from
 * Room's expected schema fails this test rather than a user's device.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingMigrationTest {

    private val testDb = "echo-migration-test.db"
    private val passphrase = "migration-test-passphrase".toByteArray()

    init {
        // SQLCipher native libs must be loaded before its factory is used.
        System.loadLibrary("sqlcipher")
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EchoDatabase::class.java,
        emptyList(),
        SupportOpenHelperFactory(passphrase),
    )

    @Test
    fun migrate1To2_preservesExistingData_andAddsEmptyRebuildableEmbeddings() {
        // --- v1: pre-populate with real user data (no embeddings table exists yet) ---
        helper.createDatabase(testDb, 1).use { db ->
            db.execSQL(
                "INSERT INTO recordings " +
                    "(id, createdAt, durationMs, captureSource, fidelity, captureMode, " +
                    "contactHash, localAudioRef, encryptionMeta, syncState) " +
                    "VALUES ('rec1', 1000, 5000, 'MIC', 'HD', 'ON_DEMAND', NULL, " +
                    "'/enc/rec1.ogg', 'meta', 'LOCAL')",
            )
            db.execSQL(
                "INSERT INTO transcript_revisions " +
                    "(id, recordingId, rev, locus, languageTag, createdAt) " +
                    "VALUES ('trv1', 'rec1', 0, 'ON_DEVICE', 'en', 1000)",
            )
        }

        // --- migrate v1 → v2 (Room validates the resulting schema against 2.json) ---
        val db = helper.runMigrationsAndValidate(testDb, 2, true, EchoDatabase.MIGRATION_1_2)

        // 1. No data loss — the pre-existing rows survive verbatim.
        db.query("SELECT id, localAudioRef FROM recordings").use { c ->
            assertTrue("recording must survive the migration", c.moveToFirst())
            assertEquals("rec1", c.getString(0))
            assertEquals("/enc/rec1.ogg", c.getString(1))
            assertEquals("exactly one recording", 1, c.count)
        }
        db.query("SELECT recordingId, rev FROM transcript_revisions").use { c ->
            assertTrue("transcript must survive the migration", c.moveToFirst())
            assertEquals("rec1", c.getString(0))
            assertEquals(0, c.getInt(1))
        }

        // 2. The embeddings table exists and starts EMPTY.
        db.query("SELECT COUNT(*) FROM embeddings").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("embeddings start empty after upgrade", 0, c.getInt(0))
        }

        // 3. Embeddings are rebuildable for the surviving recording (FK holds).
        db.execSQL(
            "INSERT INTO embeddings (recordingId, model, dim, vector, createdAt) " +
                "VALUES ('rec1', 'use-1', 1, x'00000000', 2000)",
        )
        db.query("SELECT COUNT(*) FROM embeddings").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.close()
    }

    /**
     * Phase 6 Step 4 — v2 → v3 adds `recordings.transcriptionStatus`. Proven
     * **non-destructive** on a pre-populated v2 DB: a single `ALTER TABLE … ADD COLUMN`
     * with a `NOT NULL DEFAULT 'PENDING'`, so the existing recording survives verbatim
     * and reads as PENDING (then re-transcribes on demand). `runMigrationsAndValidate`
     * also makes Room validate the result against `3.json`, catching SQL/schema drift here
     * rather than on a user's device.
     */
    @Test
    fun migrate2To3_preservesData_andAddsPendingTranscriptionStatus() {
        // --- v2: a real recording (no transcriptionStatus column yet) ---
        helper.createDatabase(testDb, 2).use { db ->
            db.execSQL(
                "INSERT INTO recordings " +
                    "(id, createdAt, durationMs, captureSource, fidelity, captureMode, " +
                    "contactHash, localAudioRef, encryptionMeta, syncState) " +
                    "VALUES ('rec1', 1000, 5000, 'MIC', 'HD', 'ON_DEMAND', NULL, " +
                    "'/enc/rec1.ogg', 'meta', 'LOCAL')",
            )
        }

        // --- migrate v2 → v3 (Room validates against 3.json) ---
        val db = helper.runMigrationsAndValidate(testDb, 3, true, EchoDatabase.MIGRATION_2_3)

        // No data loss + the new column defaults to PENDING for the pre-existing row.
        db.query("SELECT id, localAudioRef, transcriptionStatus FROM recordings").use { c ->
            assertTrue("recording must survive v2→v3", c.moveToFirst())
            assertEquals("rec1", c.getString(0))
            assertEquals("/enc/rec1.ogg", c.getString(1))
            assertEquals("pre-existing rows read as PENDING", "PENDING", c.getString(2))
            assertEquals(1, c.count)
        }
        // The column is writable to the lifecycle states.
        db.execSQL("UPDATE recordings SET transcriptionStatus = 'DONE' WHERE id = 'rec1'")
        db.query("SELECT transcriptionStatus FROM recordings WHERE id = 'rec1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("DONE", c.getString(0))
        }
        db.close()
    }

    /**
     * v3 → v4 (Phase 7 Layer 2): adds the nullable `detectedLanguageTag` column to `recordings`.
     * Non-destructive: a single `ALTER TABLE … ADD COLUMN` with no default, so the pre-existing
     * recording survives verbatim and reads NULL (no detection yet). Room validates against
     * `4.json`. The column must be writable to an ISO code.
     */
    @Test
    fun migrate3To4_preservesData_andAddsNullableDetectedLanguage() {
        // --- v3: a real recording (no detectedLanguageTag column yet) ---
        helper.createDatabase(testDb, 3).use { db ->
            db.execSQL(
                "INSERT INTO recordings " +
                    "(id, createdAt, durationMs, captureSource, fidelity, captureMode, " +
                    "contactHash, localAudioRef, encryptionMeta, syncState, transcriptionStatus) " +
                    "VALUES ('rec1', 1000, 5000, 'MIC', 'HD', 'ON_DEMAND', NULL, " +
                    "'/enc/rec1.ogg', 'meta', 'LOCAL', 'DONE')",
            )
        }

        // --- migrate v3 → v4 (Room validates against 4.json) ---
        val db = helper.runMigrationsAndValidate(testDb, 4, true, EchoDatabase.MIGRATION_3_4)

        // No data loss + the new column is NULL for the pre-existing row.
        db.query("SELECT id, transcriptionStatus, detectedLanguageTag FROM recordings").use { c ->
            assertTrue("recording must survive v3→v4", c.moveToFirst())
            assertEquals("rec1", c.getString(0))
            assertEquals("DONE", c.getString(1))
            assertTrue("detectedLanguageTag defaults to NULL", c.isNull(2))
            assertEquals(1, c.count)
        }
        // The column is writable to a detected ISO code.
        db.execSQL("UPDATE recordings SET detectedLanguageTag = 'sl' WHERE id = 'rec1'")
        db.query("SELECT detectedLanguageTag FROM recordings WHERE id = 'rec1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("sl", c.getString(0))
        }
        db.close()
    }

    /**
     * Item 4 (rename + smart titles): v5 → v6 adds the nullable `userTitle` column to `recordings`.
     * Non-destructive: a single `ALTER TABLE … ADD COLUMN` with no default, so the pre-existing
     * recording survives verbatim and reads NULL (no manual title → falls through to the
     * smart/date+time title). Room validates against `6.json`. The column must be writable to a
     * manual title AND back to NULL (a blank rename reverts to the smart/default title).
     */
    @Test
    fun migrate5To6_preservesData_andAddsNullableUserTitle() {
        // --- v5: a real recording (no userTitle column yet) ---
        helper.createDatabase(testDb, 5).use { db ->
            db.execSQL(
                "INSERT INTO recordings " +
                    "(id, createdAt, durationMs, captureSource, fidelity, captureMode, " +
                    "contactHash, localAudioRef, encryptionMeta, syncState, transcriptionStatus, " +
                    "detectedLanguageTag, transcriptionAttempts) " +
                    "VALUES ('rec1', 1000, 5000, 'MIC', 'HD', 'ON_DEMAND', NULL, " +
                    "'/enc/rec1.ogg', 'meta', 'LOCAL', 'DONE', NULL, 0)",
            )
        }

        // --- migrate v5 → v6 (Room validates against 6.json) ---
        val db = helper.runMigrationsAndValidate(testDb, 6, true, EchoDatabase.MIGRATION_5_6)

        // No data loss + the new column is NULL for the pre-existing row.
        db.query("SELECT id, transcriptionStatus, userTitle FROM recordings").use { c ->
            assertTrue("recording must survive v5→v6", c.moveToFirst())
            assertEquals("rec1", c.getString(0))
            assertEquals("DONE", c.getString(1))
            assertTrue("userTitle defaults to NULL (no manual title)", c.isNull(2))
            assertEquals(1, c.count)
        }
        // The column is writable to a manual title and clearable back to NULL (blank rename).
        db.execSQL("UPDATE recordings SET userTitle = 'My meeting' WHERE id = 'rec1'")
        db.query("SELECT userTitle FROM recordings WHERE id = 'rec1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("My meeting", c.getString(0))
        }
        db.execSQL("UPDATE recordings SET userTitle = NULL WHERE id = 'rec1'")
        db.query("SELECT userTitle FROM recordings WHERE id = 'rec1'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("blank rename reverts to NULL", c.isNull(0))
        }
        db.close()
    }
}
