package london.aipartner.echo.core.transcribe

import kotlin.math.sqrt

/**
 * Voice-activity / silence detection — the **HARD RULE** of Phase 4.
 *
 * Whisper hallucinates phantom phrases ("Thank you.", subtitle credits, …) on
 * pure silence or noise. Emitting that would violate the immutable-attributable-
 * artifact directive: a derivation must be faithful. So before the model is
 * EVER invoked, audio is checked here. No speech → the model is not called and
 * an empty transcript with `noSpeechDetected = true` is produced instead of
 * fabricated words.
 *
 * This is deterministic DSP, not a model — it is unit-tested directly.
 */
interface Vad {
    /** True iff the PCM-16 mono buffer contains enough voiced energy to transcribe. */
    fun hasSpeech(pcm: ShortArray): Boolean
}

/**
 * Frame-based RMS energy VAD over 16 kHz mono PCM-16.
 *
 * Splits the signal into short frames, marks a frame "voiced" when its RMS rises
 * above [voicedRmsThreshold], and reports speech only when the total voiced
 * duration clears [minVoicedMs]. A single click or a tiny burst of noise does not
 * count — the cumulative-duration floor is what rejects "almost silence".
 *
 * Thresholds are conservative (favours *not* fabricating): borderline-quiet audio
 * is treated as silence rather than risking a hallucinated transcript.
 */
class EnergyVad(
    private val sampleRateHz: Int = 16_000,
    private val frameMs: Int = 30,
    /** RMS (in PCM-16 LSBs, 0..32767) above which a frame is "voiced". ~ -36 dBFS. */
    private val voicedRmsThreshold: Double = 500.0,
    /** Minimum cumulative voiced audio before we call it speech. */
    private val minVoicedMs: Int = 300,
) : Vad {

    override fun hasSpeech(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return false
        val frameLen = (sampleRateHz * frameMs / 1000).coerceAtLeast(1)
        var voicedFrames = 0
        var frame = 0
        while (frame + frameLen <= pcm.size) {
            if (frameRms(pcm, frame, frameLen) >= voicedRmsThreshold) voicedFrames++
            frame += frameLen
        }
        val voicedMs = voicedFrames * frameMs
        return voicedMs >= minVoicedMs
    }

    private fun frameRms(pcm: ShortArray, start: Int, len: Int): Double {
        var sumSquares = 0.0
        for (i in start until start + len) {
            val s = pcm[i].toDouble()
            sumSquares += s * s
        }
        return sqrt(sumSquares / len)
    }
}
