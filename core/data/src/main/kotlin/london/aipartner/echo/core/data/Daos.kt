package london.aipartner.echo.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(recording: RecordingEntity)

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: String): RecordingEntity?

    /** Reactive single-row observation. Emits on every write to this row (e.g. the
     *  service flipping `transcriptionStatus` PENDING→RUNNING→DONE), so a foregrounded
     *  detail screen flips from "Transcribing…" to the transcript IN PLACE — no manual
     *  poll, no navigation needed. Room's InvalidationTracker drives the re-emit. */
    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observeById(id: String): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    suspend fun getAll(): List<RecordingEntity>

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Update only the transcription lifecycle column (audio/other fields untouched). */
    @Query("UPDATE recordings SET transcriptionStatus = :status WHERE id = :id")
    suspend fun updateTranscriptionStatus(id: String, status: String)

    /**
     * Persist (or clear) the user's MANUAL title. A blank rename passes null here to REVERT to the
     * smart/date+time title. Touches only this column — the audio, transcript, and smart TITLE
     * artifact are untouched, so a manual name is independent of (and survives) re-transcription.
     */
    @Query("UPDATE recordings SET userTitle = :title WHERE id = :id")
    suspend fun updateUserTitle(id: String, title: String?)

    /** Persist the auto-detected language of a suspected mis-decode (Phase 7 Layer 2), or null. */
    @Query("UPDATE recordings SET detectedLanguageTag = :tag WHERE id = :id")
    suspend fun updateDetectedLanguage(id: String, tag: String?)

    /**
     * Atomically bump the transcription attempt counter and return the NEW value. Called (and
     * persisted) at the very start of a pass — BEFORE the crash-prone native decode — so a
     * process death still advances the count and the quarantine cap is eventually hit. Two
     * statements in one @Transaction so the increment and the read can't interleave.
     */
    @Transaction
    suspend fun incrementAndGetTranscriptionAttempts(id: String): Int {
        incrementTranscriptionAttempts(id)
        return getTranscriptionAttempts(id)
    }

    @Query("UPDATE recordings SET transcriptionAttempts = transcriptionAttempts + 1 WHERE id = :id")
    suspend fun incrementTranscriptionAttempts(id: String)

    @Query("SELECT transcriptionAttempts FROM recordings WHERE id = :id")
    suspend fun getTranscriptionAttempts(id: String): Int

    /** Explicit user retry: clear the attempt count so a quarantined recording can try again. */
    @Query("UPDATE recordings SET transcriptionAttempts = 0 WHERE id = :id")
    suspend fun resetTranscriptionAttempts(id: String)

    /**
     * Startup recovery: any recording left RUNNING is orphaned (transcription runs in-process, so a
     * RUNNING row at process start means the prior process died mid-pass — e.g. a hang was killed).
     * Reset it to FAILED so it shows an honest, retryable state instead of a permanent
     * "Transcribing…". Returns the number of rows recovered.
     */
    @Query("UPDATE recordings SET transcriptionStatus = 'FAILED' WHERE transcriptionStatus = 'RUNNING'")
    suspend fun failOrphanedRunningTranscriptions(): Int
}

/** Projection for keyword search: a recording's id + its latest-revision transcript text. */
data class RecordingTranscriptText(val recordingId: String, val text: String)

@Dao
interface TranscriptDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(revision: TranscriptRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegments(segments: List<TranscriptSegmentEntity>)

    @Query("DELETE FROM transcript_revisions WHERE recordingId = :recordingId AND rev = :rev")
    suspend fun deleteRevisionsAtRev(recordingId: String, rev: Int)

    /**
     * Idempotently (re)write a recording's machine revision at [TranscriptRevisionEntity.rev]:
     * delete any existing revision at that rev (its segments cascade via FK) THEN insert the new
     * one, atomically. A recording therefore has **exactly one** rev-0 machine revision no matter
     * how many times transcription runs — the fix for the recovery data bug where a force-close
     * *after* the transcript write but *before* the DONE status flip made WorkManager re-run the
     * pass and append a SECOND full rev-0 (doubling search text + double semantic indexing).
     * Because `insertRevision` uses a random-UUID PK it never conflicts on its own, so dedup MUST
     * be by (recordingId, rev), not by PK. Higher revs (future user edits) are untouched.
     */
    @Transaction
    suspend fun replaceMachineRevision(
        revision: TranscriptRevisionEntity,
        segments: List<TranscriptSegmentEntity>,
    ) {
        deleteRevisionsAtRev(revision.recordingId, revision.rev)
        insertRevision(revision)
        if (segments.isNotEmpty()) insertSegments(segments)
    }

    @Query("SELECT * FROM transcript_revisions WHERE recordingId = :recordingId ORDER BY rev")
    suspend fun revisionsFor(recordingId: String): List<TranscriptRevisionEntity>

    /** Reactive revisions observation — emits when rev 0 (machine transcript) is written
     *  after a recording stops, so the detail screen picks up the segments in place. */
    @Query("SELECT * FROM transcript_revisions WHERE recordingId = :recordingId ORDER BY rev")
    fun observeRevisions(recordingId: String): Flow<List<TranscriptRevisionEntity>>

    @Query("SELECT * FROM transcript_segments WHERE revisionId = :revisionId ORDER BY orderIdx")
    suspend fun segmentsFor(revisionId: String): List<TranscriptSegmentEntity>

    /**
     * Keyword search source (2026-07-21): the full transcript text of each recording's LATEST
     * revision, one row per recording, segments concatenated in order. Feeds the keyword-primary
     * library search (substring match over this text) — which gives true exclusion, unlike the
     * semantic embedder whose whole-document cosines can't be thresholded. No schema change: a
     * read-only projection over the existing tables (no DB-version bump).
     */
    @Query(
        """
        SELECT r.recordingId AS recordingId, GROUP_CONCAT(s.text, ' ') AS text
        FROM transcript_revisions r
        JOIN transcript_segments s ON s.revisionId = r.id
        WHERE r.rev = (
            SELECT MAX(r2.rev) FROM transcript_revisions r2 WHERE r2.recordingId = r.recordingId
        )
        GROUP BY r.recordingId
        """,
    )
    suspend fun latestTranscriptTexts(): List<RecordingTranscriptText>
}

@Dao
interface AiArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(artifact: AiArtifactEntity)

    @Query("SELECT * FROM ai_artifacts WHERE recordingId = :recordingId")
    suspend fun forRecording(recordingId: String): List<AiArtifactEntity>
}

@Dao
interface EmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(embedding: EmbeddingEntity)

    /** All embeddings — brute-force cosine over this is fine at v1 library scale. */
    @Query("SELECT * FROM embeddings")
    suspend fun all(): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings WHERE recordingId = :recordingId")
    suspend fun forRecording(recordingId: String): EmbeddingEntity?

    @Query("DELETE FROM embeddings WHERE recordingId = :recordingId")
    suspend fun deleteForRecording(recordingId: String)

    @Query("SELECT COUNT(*) FROM embeddings")
    suspend fun count(): Int
}

@Dao
interface ConsentRecordDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: ConsentRecordEntity)

    @Query("SELECT * FROM consent_records WHERE recordingId = :recordingId")
    suspend fun forRecording(recordingId: String): ConsentRecordEntity?
}

@Dao
interface SyncRefDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ref: SyncRefEntity)

    @Query("SELECT * FROM sync_refs WHERE recordingId = :recordingId")
    suspend fun forRecording(recordingId: String): List<SyncRefEntity>
}
