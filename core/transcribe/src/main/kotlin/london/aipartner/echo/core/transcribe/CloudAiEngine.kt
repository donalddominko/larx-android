package london.aipartner.echo.core.transcribe

import london.aipartner.echo.core.billing.Entitlements
import london.aipartner.echo.core.billing.Feature

/**
 * The Pro, **double-gated** cloud generative engine — the v1 locus for
 * title/summary/actions (`references/phase-05-ai-features.md`).
 *
 * Requires BOTH `Entitlements.has(AI_SUMMARY)` (Pro) AND [EgressConsent]
 * ("content may leave this device", off by default — the SAME consent that gates
 * [CloudTranscriber], because the transcript leaving the device is content egress
 * exactly as audio is). Fails closed without **either**, and the check happens at
 * the very top — **before any transcript text is read or handed to the provider**.
 * No optimistic-send-then-check.
 *
 * Honest degradation (the whisper-on-silence rule, one layer out): empty input
 * yields [AiArtifactResult.NothingToGenerate]; a provider error/timeout or a
 * null/blank result yields [AiArtifactResult.Failed] — never a fabricated artifact.
 */
class CloudAiEngine(
    private val entitlements: Entitlements,
    private val egressConsent: EgressConsent,
    private val provider: LlmProvider,
) : AiEngine {

    override val locus = Locus.CLOUD

    /** True only when BOTH gates are open. Deterministic — no model decides this. */
    fun gatesOpen(): Boolean =
        entitlements.has(Feature.AI_SUMMARY) && egressConsent.isAllowed()

    override suspend fun generate(input: AiInput, kind: AiKind): AiArtifactResult {
        // Gate FIRST — before reading transcript content for upload.
        if (!entitlements.has(Feature.AI_SUMMARY)) {
            throw AiGenerationRefused("Pro entitlement AI_SUMMARY not held")
        }
        if (!egressConsent.isAllowed()) {
            throw AiGenerationRefused("data-egress consent is off (content may not leave device)")
        }
        // Honest empty-input rule: nothing was said ⇒ invent nothing.
        if (!input.hasSpeech) return AiArtifactResult.NothingToGenerate

        // Only now — both gates open AND there is content — is text sent to the provider.
        val content = try {
            provider.generate(kind, input.transcriptText, input.languageTag)
        } catch (e: Exception) {
            return AiArtifactResult.Failed("provider error: ${e.message}")
        }
        // Honest degradation: a blank/null result is NOT an artifact.
        if (content.isNullOrBlank()) {
            return AiArtifactResult.Failed("provider returned no usable $kind")
        }
        return AiArtifactResult.Generated(kind, content.trim(), provider.model)
    }
}
