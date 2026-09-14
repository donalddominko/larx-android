package london.aipartner.echo.core.transcribe

import kotlinx.coroutines.flow.Flow

/**
 * The [Transcriber] bound at the seam. Deterministically routes each request:
 * **on-device by default**, cloud ONLY when [CloudTranscriber]'s double gate
 * (Pro entitlement AND egress consent) is open. No model decides the locus.
 *
 * This replaces the `NoopTranscriber` binding in `:app`'s `SeamsModule`.
 */
class TranscriberRouter(
    private val onDevice: OnDeviceTranscriber,
    private val cloud: CloudTranscriber,
) : Transcriber {

    /** The locus that WOULD be used right now, honestly reflecting the gates. */
    override val locus: Locus
        get() = if (cloud.gatesOpen()) Locus.CLOUD else Locus.ON_DEVICE

    override suspend fun transcribe(audio: AudioRef, opts: TranscribeOpts): Transcript =
        if (cloud.gatesOpen()) cloud.transcribe(audio, opts)
        else onDevice.transcribe(audio, opts)

    /** Live partials come from the on-device path. */
    override fun live(): Flow<PartialTranscript> = onDevice.live()

    /** Only the on-device path holds a heavy native context to free. */
    override fun releaseResources() = onDevice.releaseResources()

    /** Progress is reported by the on-device whisper run. */
    override fun currentProgressPercent(): Int = onDevice.currentProgressPercent()

    /** Layer-2 language detection is on-device regardless of tier (the whisper model is local). */
    override suspend fun detectLanguage(audio: AudioRef, forcedTag: String): DetectedLanguage? =
        onDevice.detectLanguage(audio, forcedTag)
}
