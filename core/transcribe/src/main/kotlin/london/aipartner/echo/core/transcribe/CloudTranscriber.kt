package london.aipartner.echo.core.transcribe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import london.aipartner.echo.core.billing.Entitlements
import london.aipartner.echo.core.billing.Feature

/**
 * Reads `ConsentPreferences.dataEgressAllowed` without coupling `:core:transcribe`
 * to the consent module's Android-backed class. Bound in `:app` to
 * `{ consentPreferences.dataEgressAllowed }`. The consent that decides whether
 * user **audio may leave the device**.
 */
fun interface EgressConsent {
    fun isAllowed(): Boolean
}

/**
 * Provider-agnostic cloud ASR (the runtime-core LLM/ASR-abstraction philosophy):
 * one interface, a swappable concrete provider. The high-accuracy + diarization
 * path. Implementations upload audio — so they are ONLY ever reached after the
 * double gate in [CloudTranscriber] passes.
 */
interface AsrProvider {
    suspend fun transcribe(audio: AudioRef, opts: TranscribeOpts): List<TranscriptSegment>
}

/** Typed refusal when the cloud path is requested without BOTH gates open. */
class CloudTranscriptionRefused(message: String) : IllegalStateException(message)

/**
 * The Pro, **double-gated** cloud path.
 *
 * Requires BOTH `Entitlements.has(CLOUD_TRANSCRIPTION)` (Pro) AND
 * [EgressConsent] ("audio may leave this device", off by default). Fails closed
 * without **either** — and the check happens at the very top, **before any byte
 * of audio is read** for upload. No optimistic-upload-then-check.
 */
class CloudTranscriber(
    private val entitlements: Entitlements,
    private val egressConsent: EgressConsent,
    private val provider: AsrProvider,
) : Transcriber {

    override val locus = Locus.CLOUD

    /** True only when BOTH gates are open. Deterministic — no model decides this. */
    fun gatesOpen(): Boolean =
        entitlements.has(Feature.CLOUD_TRANSCRIPTION) && egressConsent.isAllowed()

    override suspend fun transcribe(audio: AudioRef, opts: TranscribeOpts): Transcript {
        if (!entitlements.has(Feature.CLOUD_TRANSCRIPTION)) {
            throw CloudTranscriptionRefused("Pro entitlement CLOUD_TRANSCRIPTION not held")
        }
        if (!egressConsent.isAllowed()) {
            throw CloudTranscriptionRefused("data-egress consent is off (audio may not leave device)")
        }
        // Only now — both gates open — is audio handed to the provider for upload.
        val segments = provider.transcribe(audio, opts)
        return Transcript(
            locus = Locus.CLOUD,
            languageTag = opts.languageTag,
            segments = segments,
            noSpeechDetected = segments.isEmpty(),
        )
    }

    override fun live(): Flow<PartialTranscript> = emptyFlow()
}
