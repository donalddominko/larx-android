package london.aipartner.echo.core.transcribe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The offline, free, **zero-key, zero-network** default. whisper.cpp `base` via
 * JNI ([WhisperEngine]), batch over a finished recording.
 *
 * Pipeline: decode → **VAD hard-rule** → engine.
 * - Decode the recording to 16 kHz mono PCM-16 ([PcmDecoder]).
 * - Run the [Vad]. **No speech → the engine is NOT invoked**; return an empty
 *   transcript with `noSpeechDetected = true`. Never fabricate words on silence.
 * - Speech present → ensure the model is provisioned ([ModelProvisioner]) and
 *   hand the PCM to the engine; map its segments into a [Transcript].
 *
 * By construction this class has NO reference to networking, keys, `Entitlements`,
 * or the egress consent — only [ModelProvisioner] touches the network, and only
 * for the model file. Guarded by the egress-distinction test.
 */
class OnDeviceTranscriber(
    private val decoder: PcmDecoder,
    private val vad: Vad,
    private val engine: WhisperEngine,
    private val modelProvisioner: ModelProvisioner,
) : Transcriber {

    override val locus = Locus.ON_DEVICE

    override suspend fun transcribe(audio: AudioRef, opts: TranscribeOpts): Transcript {
        val pcm = decoder.decodeToPcm16Mono16k(audio)

        // HARD RULE: never hand silence to the model (whisper hallucinates on it).
        if (!vad.hasSpeech(pcm)) {
            return Transcript(
                locus = Locus.ON_DEVICE,
                languageTag = opts.languageTag,
                segments = emptyList(),
                noSpeechDetected = true,
            )
        }

        if (!engine.isAvailable()) {
            throw TranscriptionEngineUnavailable("on-device engine not ready")
        }
        val modelPath = modelProvisioner.ensureModel()
        val segments = engine.transcribe(pcm, modelPath, opts.languageTag)

        return Transcript(
            locus = Locus.ON_DEVICE,
            languageTag = opts.languageTag,
            segments = segments,
            noSpeechDetected = segments.isEmpty(),
        )
    }

    /** Live partials are a later enhancement; on-device v1 is batch-only. */
    override fun live(): Flow<PartialTranscript> = emptyFlow()

    /** Free the whisper native context after a pass (low-RAM OOM mitigation). */
    override fun releaseResources() = engine.release()

    override fun currentProgressPercent(): Int = engine.progressPercent()

    /**
     * Phase 7 Layer 2 — dedicated auto-detect for a WARN. Decodes the audio, then hands the
     * FIRST 30 s of PCM to the engine's auto-detect (whisper auto-detects on the first 30 s
     * window; truncating bounds the memory/time on the rare failure path). The caller has
     * already released the transcription context, so the detect context is the only one resident.
     */
    override suspend fun detectLanguage(audio: AudioRef, forcedTag: String): DetectedLanguage? {
        if (!engine.isAvailable()) return null
        val pcm = decoder.decodeToPcm16Mono16k(audio)
        if (pcm.isEmpty()) return null
        // HARD RULE (same as transcribe): never hand silence to the model — detecting a
        // "language" in silence would produce a spurious mismatch warn on a quiet recording.
        if (!vad.hasSpeech(pcm)) return null
        val window = if (pcm.size > DETECT_WINDOW_SAMPLES) pcm.copyOf(DETECT_WINDOW_SAMPLES) else pcm
        // ensureModel (not modelPathOrNull): detect-first runs BEFORE any transcribe, so the
        // provisioner hasn't verified/cached the path yet — modelPathOrNull would be null. This
        // provisions+verifies once (cached); the following transcribe reuses it. Null if unavailable.
        val modelPath = runCatching { modelProvisioner.ensureModel() }.getOrNull() ?: return null
        // whisper wants the primary subtag ("en", not "en-GB") for the forced-probability lookup.
        val forcedPrimary = forcedTag.substringBefore('-')
        return engine.detectLanguage(window, modelPath, forcedPrimary)
    }

    private companion object {
        /**
         * 10 s at 16 kHz mono — the language-detection window. whisper auto-detects on up to the
         * first 30 s, but the A03 verification showed even ~2 s of English detects "en" at 0.97
         * confidence, so 10 s is ample signal while capping the encoder cost on long recordings
         * (detect ≈ encoder over this window, ~11 s worst case rather than ~34 s at 30 s).
         */
        const val DETECT_WINDOW_SAMPLES = 10 * 16_000
    }
}
