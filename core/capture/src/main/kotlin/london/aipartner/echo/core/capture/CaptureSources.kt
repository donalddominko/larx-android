package london.aipartner.echo.core.capture

import android.content.Context
import android.media.MediaRecorder

/**
 * Voice memos / meetings / interviews — the single v1 capture source. Always
 * available with a mic + permission, always Play-safe. Honest capability: mic, HD.
 *
 * Captures via [AudioRecordCaptureHandle] (raw PCM → live gain → AAC). The raw `MIC`
 * source is the honest unprocessed input; the device-dependent quietness is handled by
 * the **live** user mic-boost ([gainProvider], the persisted slider) applied to the
 * stream as it's captured — so the meter reflects it and there's no post-capture
 * re-encode. (`VOICE_RECOGNITION` was tried and rejected — it gated a close voice down
 * on the A03; post-capture auto-normalize was also rejected. See PROGRESS.md.)
 */
class MicRecorder(
    private val context: Context,
    private val gainProvider: () -> Float = { 1.0f },
    private val audioSource: Int = MediaRecorder.AudioSource.MIC,
) : CaptureSource {
    override val id = CaptureSourceId.MIC

    override val capability = CaptureCapability(
        source = CaptureSourceId.MIC,
        fidelity = Fidelity.HD,
    )

    override fun isAvailable(ctx: DeviceContext): Boolean = ctx.hasMicrophone

    override suspend fun start(spec: RecordingSpec): CaptureHandle =
        AudioRecordCaptureHandle(
            outputFile = spec.outputTempFile,
            sampleRateHz = spec.sampleRateHz,
            audioSource = audioSource,
            gainProvider = gainProvider,
        ).also { it.begin() }
}

// NOTE: call recording was removed for v1 (2026-06-24). Testing proved the
// Samsung A03 zeroes the mic for a 3rd-party app during a telephony call (the
// common OEM/OS lockdown — see capture-strategy.md). Echo is a focused voice
// recorder. The CaptureSource seam is preserved: a future source (VoIP, a future
// API) can be added as a new CaptureSource + CaptureJob without touching the rest
// of the app.
