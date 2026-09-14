package london.aipartner.echo.transcribe

import android.util.Log
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import london.aipartner.echo.core.capture.AudioEncryptor
import london.aipartner.echo.core.capture.RecordingPostProcessor
import london.aipartner.echo.core.data.AiArtifactDao
import london.aipartner.echo.core.data.AiArtifactEntity
import london.aipartner.echo.core.data.RecordingDao
import london.aipartner.echo.core.data.TranscriptionStatus
import london.aipartner.echo.core.transcribe.AudioRef
import london.aipartner.echo.core.transcribe.DisplayTitle
import london.aipartner.echo.core.transcribe.SemanticIndex
import london.aipartner.echo.core.transcribe.SmartTitle
import london.aipartner.echo.core.transcribe.TranscribeOpts
import london.aipartner.echo.core.transcribe.Transcriber
import london.aipartner.echo.core.transcribe.TranscriptWriter

/**
 * Phase 6 Step 4 — transcription-on-record-stop, run **in the foreground service after
 * stop** (the service keeps the FGS alive across this and shows "Transcribing…").
 *
 * Ordering is the honesty contract: the recording (audio) is persisted by
 * `RecorderController.stop()` **before** this runs, so a failed or interrupted
 * transcription can never lose the audio — the recording survives with a `FAILED`
 * status (retryable) and the UI shows an honest state, never a broken card.
 *
 * One bounded native lifetime (low-RAM / 2 GB A03 OOM mitigation): decrypt → route to
 * the [Transcriber] (on-device whisper by default; cloud only if the double gate is
 * open) → write rev 0 → **free the native whisper context** in `finally`. The decrypted
 * plaintext temp is shredded (overwrite + delete) so no plaintext audio outlives the pass.
 *
 * Honest degradation: the VAD hard-rule inside [Transcriber] yields a no-speech
 * transcript (rev 0, zero segments) for silence — never fabricated text; any error →
 * `FAILED`. Semantic-search indexing is best-effort and never blocks DONE.
 */
class TranscribingPostProcessor @Inject constructor(
    private val recordingDao: RecordingDao,
    private val transcriber: Transcriber,
    private val transcriptWriter: TranscriptWriter,
    private val semanticIndex: SemanticIndex,
    private val aiArtifactDao: AiArtifactDao,
    private val encryptor: AudioEncryptor,
    private val progressBus: TranscriptionProgressBus,
    private val transcriptionPreferences: TranscriptionPreferences,
) : RecordingPostProcessor {

    override suspend fun process(recordingId: String, audioRef: String) {
        // QUARANTINE GUARD (Phase 10, 2026-07-18). A decode that crashes the PROCESS (e.g. a
        // native SIGABRT) is auto-rescheduled by WorkManager, re-running forever → the app
        // appears to crash on every launch. Persist the attempt count BEFORE the crash-prone
        // native call so a process death still advances it; once it exceeds the cap, mark the
        // recording terminally FAILED and STOP — degrade to "one failed recording", never a
        // crash loop. (An explicit user retry clears the count via resetTranscriptionAttempts.)
        val attempt = recordingDao.incrementAndGetTranscriptionAttempts(recordingId)
        if (attempt > MAX_TRANSCRIPTION_ATTEMPTS) {
            Log.e(
                TAG,
                "Quarantining $recordingId: transcription failed $MAX_TRANSCRIPTION_ATTEMPTS× " +
                    "(attempt $attempt) — marking FAILED, will not re-attempt. Audio is intact.",
            )
            recordingDao.updateTranscriptionStatus(recordingId, TranscriptionStatus.FAILED.name)
            progressBus.clear(recordingId)
            return
        }
        recordingDao.updateTranscriptionStatus(recordingId, TranscriptionStatus.RUNNING.name)
        progressBus.update(recordingId, 0)

        // Transiently decrypt to a plaintext temp the decoder can read; shred it after.
        val temp = runCatching { encryptor.decryptToTemp(File(audioRef)) }.getOrNull()
        if (temp == null) {
            Log.e(TAG, "Could not decrypt audio for transcription: $recordingId")
            recordingDao.updateTranscriptionStatus(recordingId, TranscriptionStatus.FAILED.name)
            return
        }

        try {
            // ── NO pre-decode language detection (Direction A, step 3 — 2026-07-19). ──────────
            // The pre-decode whisper-base language-detect pass was REMOVED. Its language ID is
            // unreliable on real phone-mic audio (genuine English at P(en) 0.12–0.31 vs ~0.95 on
            // clean fixtures), so it (a) blocked the transcript when used as a gate [launch
            // blocker #2] and (b) as an advisory hint produced false "might not be English" under
            // correct transcripts — all for ~11 s of extra encoder work per recording. The honest
            // mis-decode hint now comes ONLY from MisdecodeGuard (post-decode text signals —
            // repetition / foreign markers — evidence-based and free; see RecordingDetailViewModel
            // .warningFor). A doomed wrong-language decode stays bounded by the hang-cap
            // (temperature_inc=0 + wall-clock abort_cb). `engine.detectLanguage` remains as a
            // capability for the test suite + a future RELIABLE detector, just not called here.
            val forcedTag = transcriptionPreferences.effectiveLanguageTag()

            // ALWAYS transcribe in the forced language. Poll whisper's
            // 0..100 progress onto the bus for the live "Transcribing… NN%" bar; cancel on return.
            val transcript = coroutineScope {
                val poller = launch {
                    while (isActive) {
                        val pct = transcriber.currentProgressPercent()
                        if (pct >= 0) progressBus.update(recordingId, pct)
                        delay(400)
                    }
                }
                try {
                    transcriber.transcribe(
                        AudioRef(temp.absolutePath),
                        TranscribeOpts(languageTag = transcriptionPreferences.effectiveLanguageTag()),
                    )
                } finally {
                    poller.cancel()
                }
            }
            // DELETE-RACE GUARD (Donald 2026-07-31). Deletion does NOT cancel an in-flight
            // transcription, so a recording can be deleted WHILE this pass runs. The DB foreign
            // keys already make resurrection impossible — every derived write below targets a table
            // with an onDelete=CASCADE FK to `recordings`, so an insert for a now-missing parent
            // fails, and a deleted recording can never come back or stay searchable. But that path
            // burns the full decode and then trips the LOUD "SEMANTIC INDEXING FAILED … embedder is
            // broken" error (a real-defect alarm) for what is a benign race. Re-read existence once
            // here (state may have changed across a multi-minute decode) and exit cleanly instead.
            // `return` still runs the finally: temp shredded, whisper context freed.
            if (recordingDao.getById(recordingId) == null) {
                Log.i(TAG, "Recording $recordingId was deleted during transcription — discarding derived output.")
                return
            }
            // rev 0 — immutable machine output. A no-speech transcript still writes rev 0
            // (zero segments), so the recording reads "transcribed, nothing said".
            transcriptWriter.writeMachineRevision(recordingId, transcript)
            // Smart title (item 4): a free, on-device, deterministic name from the transcript —
            // NOT the cloud generative TITLE (which is Pro/off). Regenerable, stored as the TITLE
            // AiArtifact so display precedence (userTitle → smart → date+time) and a future cloud
            // title both work. Skipped when the user has set a MANUAL title (their name always wins
            // and must survive re-transcription) or when there's nothing faithful to name (→ null,
            // falls back to date+time). Best-effort: never blocks DONE.
            runCatching { maybeWriteSmartTitle(recordingId, transcript) }
                .onFailure { Log.w(TAG, "Smart-title generation failed for $recordingId", it) }
            // Best-effort: make it findable via on-device semantic search. Non-fatal to the
            // transcript, but a FAILURE HERE MEANS SEARCH SILENTLY INDEXES NOTHING — log LOUDLY
            // (Log.e) so a broken embedder (e.g. R8 stripping MediaPipe's protobuf reflection) is
            // observable, never swallowed. A silently-empty search is worse than an honest gap.
            if (transcript.segments.isNotEmpty()) {
                runCatching { semanticIndex.index(recordingId, transcript) }
                    .onFailure {
                        Log.e(
                            TAG,
                            "SEMANTIC INDEXING FAILED for $recordingId — this recording will NOT be " +
                                "findable by search. If this recurs, the on-device embedder is broken " +
                                "(check MediaPipe/protobuf keep rules on release).",
                            it,
                        )
                    }
            }
            recordingDao.updateTranscriptionStatus(recordingId, TranscriptionStatus.DONE.name)
            Log.i(
                TAG,
                "Transcription DONE for $recordingId: locus=${transcript.locus} " +
                    "segments=${transcript.segments.size} noSpeech=${transcript.noSpeechDetected}",
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Transcription failed for $recordingId (audio is intact)", t)
            recordingDao.updateTranscriptionStatus(recordingId, TranscriptionStatus.FAILED.name)
        } finally {
            // Clear live progress so the screens re-read the DB and show the final
            // transcript/status (this is what makes "Transcribing…" resolve to done).
            progressBus.clear(recordingId)
            shred(temp)
            // FREE the native whisper context so the ~150 MB model does not stay resident
            // between recordings (the low-RAM OOM mitigation).
            runCatching { transcriber.releaseResources() }
        }
    }

    /**
     * Write the on-device smart TITLE artifact, honoring provenance: if the user has already set a
     * MANUAL title we generate NOTHING (their name wins and survives re-transcription); if the
     * transcript yields no faithful phrase we write NOTHING (the UI falls back to date+time). Uses
     * the same stable id scheme as the cloud [london.aipartner.echo.core.transcribe.AiArtifactWriter]
     * (`recordingId:TITLE`) so a regenerate REPLACEs rather than duplicates.
     */
    private suspend fun maybeWriteSmartTitle(
        recordingId: String,
        transcript: london.aipartner.echo.core.transcribe.Transcript,
    ) {
        val userTitle = recordingDao.getById(recordingId)?.userTitle
        if (!DisplayTitle.shouldGenerateSmartTitle(userTitle)) return
        val text = transcript.segments.joinToString(" ") { it.text }
        val title = SmartTitle.fromTranscript(text) ?: return
        aiArtifactDao.upsert(
            AiArtifactEntity(
                id = "$recordingId:TITLE",
                recordingId = recordingId,
                kind = "TITLE",
                model = SmartTitle.MODEL,
                content = title,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Overwrite-then-delete so plaintext audio doesn't linger on disk. */
    private fun shred(file: File) {
        runCatching {
            if (file.exists()) {
                val len = file.length()
                if (len > 0) file.outputStream().use { out ->
                    val zeros = ByteArray(8 * 1024)
                    var written = 0L
                    while (written < len) {
                        val n = minOf(zeros.size.toLong(), len - written).toInt()
                        out.write(zeros, 0, n)
                        written += n
                    }
                    out.flush()
                }
            }
        }
        runCatching { file.delete() }
    }

    private companion object {
        const val TAG = "EchoTranscribe"

        /**
         * Quarantine cap: after this many STARTED passes without a DONE, the recording is marked
         * terminally FAILED and never re-attempted. Bounds a deterministically-crashing decode to
         * a few crashes, not an infinite WorkManager reschedule loop. An explicit user retry
         * clears the count (resetTranscriptionAttempts) so a transient failure is still retryable.
         */
        const val MAX_TRANSCRIPTION_ATTEMPTS = 3
    }
}
