package london.aipartner.echo.core.transcribe

/**
 * Seam (Phase 5) — the **generative** AI layer that turns a finished transcript
 * into regenerable derivations: a TITLE, a SUMMARY, and extracted ACTIONS.
 *
 * Locus is **LOCKED for v1: CLOUD ONLY** (Donald's call, 2026-06-25 — see
 * `references/phase-05-ai-features.md`). On-device *generative* LLMs are a far
 * weaker story than on-device ASR, so there is no free on-device generative
 * engine in v1; the only concrete implementation is [CloudAiEngine], which sits
 * behind the **same double gate** as [CloudTranscriber] (Pro entitlement AND the
 * "content may leave the device" egress consent). The seam itself is
 * locus-agnostic so an on-device generative path can later be added as a *pure
 * addition* without churn.
 *
 * Every output is an `AiArtifact`-class derivation: disposable, regenerable, and
 * **never the source of truth** (the encrypted audio is; the transcript rev0 is
 * one layer in; these sit one layer further out still).
 */
interface AiEngine {
    val locus: Locus

    /**
     * Produce [kind] for [input]. Implementations MUST be honest:
     * - empty / no-speech input → an explicit [AiArtifactResult.NothingToGenerate],
     *   never fabricated content (the Phase-4 whisper-on-silence rule, one layer out);
     * - a failed / empty / timed-out generation → [AiArtifactResult.Failed],
     *   never a hallucinated or placeholder artifact.
     */
    suspend fun generate(input: AiInput, kind: AiKind): AiArtifactResult
}

/** The generative derivations Echo produces. Mirrors `AiArtifactEntity.kind`. */
enum class AiKind { TITLE, SUMMARY, ACTIONS }

/**
 * What the generative layer reads. Deliberately the **transcript text**, not the
 * audio — generation derives from the transcript. [hasSpeech] is the honest
 * empty-input signal carried from the transcript's `noSpeechDetected`.
 */
data class AiInput(
    val recordingId: String,
    val transcriptText: String,
    val languageTag: String? = null,
) {
    val hasSpeech: Boolean get() = transcriptText.isNotBlank()
}

/**
 * The outcome of a generation. A success carries the content AND its provenance
 * ([model]) so a stale artifact is detectable and regeneration is meaningful.
 * The non-success cases are first-class so callers can NEVER mistake "no result"
 * for "empty content" and persist a fabricated artifact.
 */
sealed interface AiArtifactResult {
    data class Generated(val kind: AiKind, val content: String, val model: String) : AiArtifactResult

    /** Input had no speech/text — honestly produced nothing, did not invent. */
    data object NothingToGenerate : AiArtifactResult

    /** The generation attempt failed (error/timeout/empty) — show no artifact. */
    data class Failed(val reason: String) : AiArtifactResult
}

/** Typed refusal when the cloud generative path is requested without BOTH gates open. */
class AiGenerationRefused(message: String) : IllegalStateException(message)

/**
 * Provider-agnostic generative backend (the runtime-core LLM-abstraction
 * philosophy): one interface, a swappable concrete provider. Implementations send
 * **transcript content off the device** — so they are ONLY ever reached after the
 * double gate in [CloudAiEngine] passes. Returns null/blank to signal "nothing
 * usable produced" (which the engine turns into an honest [AiArtifactResult.Failed],
 * never a fabricated artifact).
 */
interface LlmProvider {
    /** A stable identifier for provenance (the `model` recorded on the artifact). */
    val model: String

    /** Generate [kind] from [transcriptText]. May throw; null/blank ⇒ no usable output. */
    suspend fun generate(kind: AiKind, transcriptText: String, languageTag: String?): String?
}
