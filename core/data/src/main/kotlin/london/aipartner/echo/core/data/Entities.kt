package london.aipartner.echo.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The encrypted data model. Append-only intent: editing a transcript creates a
 * new [TranscriptRevisionEntity], it never mutates rev 0 (the captured truth).
 * AI artifacts are regenerable derivations, never the source of truth.
 * Foreign keys CASCADE so local deletion is clean; the delete-everywhere
 * contract (propagation to cloud sinks) is enforced above this layer.
 */
@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val durationMs: Long,
    // Resolved CaptureCapability snapshot — the honesty contract, frozen at capture.
    val captureSource: String,
    val fidelity: String,
    val captureMode: String,
    // Contact name/number, hashed for non-contacts. Null for memos.
    val contactHash: String?,
    val localAudioRef: String?,
    val encryptionMeta: String?,
    val syncState: String,
    /**
     * Lifecycle of the machine transcription for this recording (one of
     * [TranscriptionStatus]). The audio is the source of truth and is persisted FIRST;
     * this tracks the derivation separately so a failed/interrupted transcription is an
     * honest, retryable state — never a lost recording or a faked transcript. Defaults
     * to PENDING so existing rows (pre-v3) and new inserts read honestly until a pass runs.
     */
    val transcriptionStatus: String = TranscriptionStatus.PENDING.name,
    /**
     * ISO code whisper AUTO-DETECTED for this recording ("sl", "de", …), or null if detection
     * never ran / found nothing. Populated (Phase 7 Layer 2) **only when the repetition-loop
     * guard fires** — i.e. a suspected mis-decode — by a single bounded auto-detect pass. It is
     * a WARN signal only (never drives the decode), so the UI can name the likely language
     * ("sounds like Slovenian") instead of a generic failure notice. Null for the common case.
     */
    val detectedLanguageTag: String? = null,
    /**
     * How many times a transcription pass has been STARTED for this recording. Incremented
     * (and persisted) at the very start of each pass, BEFORE the crash-prone native decode, so
     * the count survives a process death. Once it reaches the quarantine cap the recording is
     * marked terminally [TranscriptionStatus.FAILED] and never re-attempted — a deterministically
     * crashing decode degrades to "one failed recording", never "the app crashes on every launch"
     * (guards the WorkManager auto-reschedule-on-process-death loop). Reset to 0 only on an
     * explicit user retry.
     */
    val transcriptionAttempts: Int = 0,
    /**
     * The user's MANUAL title for this recording, or null if they never renamed it. This is
     * **provenance-bearing, source-of-truth-adjacent**: unlike the smart TITLE `AiArtifact`
     * (a regenerable derivation replaced on every re-transcription), a manual rename lives on the
     * recording row and is NEVER overwritten by re-transcription or smart-title generation — a
     * manual name always wins and always survives. Display precedence: [userTitle] → smart TITLE
     * artifact → a date+time default. Blank/null ⇒ fall through to the smart/default title.
     */
    val userTitle: String? = null,
)

/**
 * Transcription lifecycle for a recording. The audio always survives independently;
 * this only describes the derivation so the UI can be honest about it.
 *  - [PENDING]  — persisted, transcription not started (or queued).
 *  - [RUNNING]  — the on-device pass is in progress ("Transcribing…").
 *  - [DONE]     — a machine revision was written (may be empty = no speech detected).
 *  - [FAILED]   — the pass errored; the audio is intact and the pass is retryable.
 */
enum class TranscriptionStatus { PENDING, RUNNING, DONE, FAILED }

@Entity(
    tableName = "transcript_revisions",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordingId")],
)
data class TranscriptRevisionEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val rev: Int,           // 0 = machine output; user edits append new revs
    val locus: String,      // ON_DEVICE | CLOUD
    val languageTag: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptRevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("revisionId")],
)
data class TranscriptSegmentEntity(
    @PrimaryKey val id: String,
    val revisionId: String,
    val orderIdx: Int,
    val text: String,
    val tStartMs: Long,
    val tEndMs: Long,
    val speaker: String?,
)

@Entity(
    tableName = "ai_artifacts",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordingId")],
)
data class AiArtifactEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val kind: String,       // SUMMARY | ACTIONS | TITLE
    val model: String,
    val content: String,
    val createdAt: Long,
)

/**
 * A semantic-search embedding for a recording's transcript. A **regenerable
 * derivation, never source-of-truth** (like [AiArtifactEntity]): the whole table
 * can be dropped and rebuilt from the transcripts at any time — deleting/rebuilding
 * it never touches audio or transcripts. One row per recording (the transcript is
 * embedded as a whole at v1 scale). [vector] is the embedding as little-endian
 * float32 bytes; [model] records provenance so a stale embedding (model change) is
 * detectable and re-indexable. FK CASCADE so deleting a recording drops its
 * embedding. Encrypted at rest like every other table.
 */
@Entity(
    tableName = "embeddings",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EmbeddingEntity(
    @PrimaryKey val recordingId: String,
    val model: String,
    val dim: Int,
    val vector: ByteArray,
    val createdAt: Long,
) {
    // ByteArray needs structural equals/hashCode for value semantics.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingEntity) return false
        return recordingId == other.recordingId &&
            model == other.model &&
            dim == other.dim &&
            vector.contentEquals(other.vector) &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = recordingId.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + dim
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

@Entity(
    tableName = "consent_records",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordingId")],
)
data class ConsentRecordEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val verdict: String,    // ALLOW | DENY
    val basis: String,
    val noticeEmitted: Boolean,
    val jurisdiction: String,
    val createdAt: Long,
)

@Entity(
    tableName = "sync_refs",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordingId")],
)
data class SyncRefEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val sinkId: String,
    val remoteRef: String?,
    val state: String,
    val lastError: String?,
)
