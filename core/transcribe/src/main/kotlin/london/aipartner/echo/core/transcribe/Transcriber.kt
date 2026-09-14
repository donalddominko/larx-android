package london.aipartner.echo.core.transcribe

import kotlinx.coroutines.flow.Flow

/**
 * Seam 4 — provider-agnostic transcription. OnDeviceTranscriber (default,
 * offline, free, no keys) and CloudTranscriber (Pro, higher accuracy) both sit
 * behind this. Selection routes through Entitlements + ConsentGate (cloud
 * requires the "audio leaves device" consent). A transcript is a derivation,
 * never the source of truth.
 */
interface Transcriber {
    val locus: Locus

    /** Batch transcription of a finished recording. */
    suspend fun transcribe(audio: AudioRef, opts: TranscribeOpts): Transcript

    /** Streaming partials during capture. Optional per impl; stub emits nothing. */
    fun live(): Flow<PartialTranscript>

    /**
     * Releases any heavy resident resources (e.g. the on-device whisper native context)
     * after a transcription pass. Called by the orchestrator once a batch completes so a
     * large model is not held resident between recordings — the low-RAM OOM mitigation.
     * No-op by default; the on-device path overrides it.
     */
    fun releaseResources() {}

    /** Live 0..100 progress of the in-flight [transcribe], or -1 if unknown. Read from
     *  another coroutine while a batch runs, to drive an honest "Transcribing… NN%" UI. */
    fun currentProgressPercent(): Int = -1

    /**
     * Phase 7 Layer 2 — auto-detect the audio's actual language for a WARN (never to drive the
     * decode). Called by the orchestrator ONLY on a suspected mis-decode (repetition guard fired),
     * so its extra inference is bounded to the rare failure case. Returns the ISO code or null.
     *
     * MEMORY DISCIPLINE: call [releaseResources] BEFORE this so no transcription context is still
     * resident when the detect context loads (2 GB A03 OOM guard). No-op default (cloud returns
     * null — cloud-tier detection is a later concern; the on-device path implements it).
     */
    suspend fun detectLanguage(audio: AudioRef, forcedTag: String): DetectedLanguage? = null
}

enum class Locus { ON_DEVICE, CLOUD }

/** Opaque reference to captured audio in app-private storage. */
data class AudioRef(val localPath: String)

data class TranscribeOpts(
    val languageTag: String? = null,
    val diarize: Boolean = false,
)

data class TranscriptSegment(
    val text: String,
    val tStartMs: Long,
    val tEndMs: Long,
    val speaker: String? = null,
)

data class Transcript(
    val locus: Locus,
    val languageTag: String?,
    val segments: List<TranscriptSegment>,
    /**
     * True when the VAD/silence hard-rule found no speech and the model was
     * therefore NOT invoked. The honest signal that an empty transcript means
     * "nothing was said", not "the model failed" — and never fabricated text.
     * See [OnDeviceTranscriber] and `phase-04-transcription.md`.
     */
    val noSpeechDetected: Boolean = false,
)

data class PartialTranscript(val text: String)
