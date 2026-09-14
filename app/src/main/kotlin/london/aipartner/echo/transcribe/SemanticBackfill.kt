package london.aipartner.echo.transcribe

import android.util.Log
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import london.aipartner.echo.core.data.EmbeddingDao
import london.aipartner.echo.core.data.RecordingDao
import london.aipartner.echo.core.data.TranscriptDao
import london.aipartner.echo.core.data.TranscriptionStatus
import london.aipartner.echo.core.transcribe.Locus
import london.aipartner.echo.core.transcribe.SemanticIndex
import london.aipartner.echo.core.transcribe.Transcript
import london.aipartner.echo.core.transcribe.TranscriptSegment

/**
 * Startup backfill for on-device semantic search (2026-07-20, Donald).
 *
 * WHY: indexing was ONLY ever done at transcription time ([TranscribingPostProcessor]). So any
 * recording whose one-shot index failed — transcribed on a build where the embedder was broken
 * (the R8/Flogger blocker), a transient embedder error, or transcribed before search existed —
 * stayed **permanently unsearchable**, silently. Upgrading past a broken build would otherwise
 * lose search over everything recorded on it. This pass re-derives the embedding from the ALREADY
 * STORED transcript segments (no whisper re-run) for every DONE recording that lacks a
 * current-model embedding, making the store self-healing.
 *
 * The embedding store is a regenerable derivation (never source-of-truth), so re-indexing is
 * always safe. This is idempotent: it no-ops once every DONE recording has a current-model vector.
 *
 * Runs off the main thread inside [SemanticBackfillWorker]. It is:
 *  - **bounded/batched** — indexes one recording at a time, `yield()`s between each, and pauses
 *    [BATCH_PAUSE_MS] every [BATCH_SIZE] so a large library can't hammer the embedder in one
 *    startup burst;
 *  - **collision-safe** — all embedder access (this backfill, live transcription's index, and UI
 *    query) is serialized by the [SemanticIndex]'s internal embedder Mutex, so a backfill pass and
 *    a fresh-recording index can never race or contend for the single native embedder context.
 */
class SemanticBackfill @Inject constructor(
    private val recordingDao: RecordingDao,
    private val transcriptDao: TranscriptDao,
    private val embeddingDao: EmbeddingDao,
    private val semanticIndex: SemanticIndex,
) {
    /**
     * Index every DONE recording that has transcript segments but no current-model embedding.
     * Returns the number of recordings (re)indexed. Best-effort: a single recording's failure is
     * logged loudly and does not abort the pass.
     */
    suspend fun run(): Int {
        // One cheap read of the whole (small, v1-scale) store → recordingId -> model provenance.
        val existingModel = embeddingDao.all().associate { it.recordingId to it.model }
        val currentModel = semanticIndex.model

        val done = recordingDao.getAll().filter {
            it.transcriptionStatus == TranscriptionStatus.DONE.name
        }

        var indexed = 0
        var seen = 0
        for (recording in done) {
            // Already indexed with the CURRENT embedder → nothing to do. (A row from a different
            // model falls through and gets re-indexed — the future model-migration case.)
            if (existingModel[recording.id] == currentModel) continue

            // Reconstruct the transcript from the latest stored revision's segments — no whisper.
            val latest = transcriptDao.revisionsFor(recording.id).maxByOrNull { it.rev } ?: continue
            val segments = transcriptDao.segmentsFor(latest.id)
            // Zero-segment == no-speech ("transcribed, nothing said"). Legitimately has no vector;
            // skip WITHOUT touching the embedder so it never thrashes on these (only two cheap DB
            // reads recur next launch; the index() no-op is never invoked).
            if (segments.isEmpty()) continue

            val transcript = Transcript(
                locus = runCatching { Locus.valueOf(latest.locus) }.getOrDefault(Locus.ON_DEVICE),
                languageTag = latest.languageTag,
                segments = segments.map {
                    TranscriptSegment(it.text, it.tStartMs, it.tEndMs, it.speaker)
                },
            )

            runCatching { semanticIndex.index(recording.id, transcript) }
                .onSuccess { indexed++ }
                .onFailure {
                    Log.e(
                        TAG,
                        "SEMANTIC BACKFILL FAILED for ${recording.id} — it stays unsearchable. If this " +
                            "recurs, the on-device embedder is broken (check MediaPipe/protobuf/Flogger " +
                            "keep rules on release).",
                        it,
                    )
                }

            // Pace the pass so a large library doesn't monopolise the embedder at startup.
            yield()
            if (++seen % BATCH_SIZE == 0) delay(BATCH_PAUSE_MS)
        }

        if (indexed > 0) Log.i(TAG, "Semantic backfill indexed $indexed recording(s) missing an embedding.")
        return indexed
    }

    private companion object {
        const val TAG = "EchoSemanticBackfill"

        /** Recordings per burst before a short pause — keeps the startup pass from hammering the
         *  embedder on a large library. */
        const val BATCH_SIZE = 20
        const val BATCH_PAUSE_MS = 250L
    }
}
