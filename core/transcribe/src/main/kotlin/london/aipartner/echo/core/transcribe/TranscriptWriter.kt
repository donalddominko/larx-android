package london.aipartner.echo.core.transcribe

import london.aipartner.echo.core.data.TranscriptDao
import london.aipartner.echo.core.data.TranscriptRevisionEntity
import london.aipartner.echo.core.data.TranscriptSegmentEntity
import java.util.UUID

/**
 * Persists a [Transcript] as the immutable machine output: a single
 * `TranscriptRevisionEntity` at **rev 0** plus its timestamped
 * `TranscriptSegmentEntity` rows. rev 0 is the captured-truth derivation and is
 * never mutated — user edits append new revisions (a later phase), they do not
 * touch this. The source audio is never modified by transcription.
 *
 * A no-speech transcript still writes rev 0 with zero segments, so the recording
 * honestly records "transcribed, nothing said" rather than "not yet transcribed".
 */
class TranscriptWriter(
    private val dao: TranscriptDao,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Writes [transcript] as rev 0 for [recordingId], **idempotently** — any existing rev-0 machine
     * revision for the recording is replaced, so re-running transcription (notably the force-close
     * recovery re-run) leaves exactly ONE rev-0, never two. Returns the revision id.
     */
    suspend fun writeMachineRevision(recordingId: String, transcript: Transcript): String {
        val revisionId = idGen()
        val revision = TranscriptRevisionEntity(
            id = revisionId,
            recordingId = recordingId,
            rev = 0,
            locus = transcript.locus.name,
            languageTag = transcript.languageTag,
            createdAt = clock(),
        )
        val segments = transcript.segments.mapIndexed { idx, seg ->
            TranscriptSegmentEntity(
                id = idGen(),
                revisionId = revisionId,
                orderIdx = idx,
                text = seg.text,
                tStartMs = seg.tStartMs,
                tEndMs = seg.tEndMs,
                speaker = seg.speaker,
            )
        }
        dao.replaceMachineRevision(revision, segments)
        return revisionId
    }
}
