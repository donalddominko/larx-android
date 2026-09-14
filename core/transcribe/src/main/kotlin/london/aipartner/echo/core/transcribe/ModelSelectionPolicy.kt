package london.aipartner.echo.core.transcribe

import london.aipartner.echo.core.billing.Entitlements
import london.aipartner.echo.core.billing.Feature

/**
 * Decides which model a **NEW** recording is transcribed with — a pure, deterministic function of
 * the device's gated [offered] set, the user's `PREMIUM_MODELS` entitlement, which models are
 * downloaded, and the user's preference. Read at transcribe time. No model/LLM decides
 * eligibility; `Entitlements` is the single source of truth for the paid line.
 *
 * ## Free vs Pro (Option C, `references/better-models-pro.md` Decision 3) — per device
 * Applied on top of the offered set, sorted ascending by tier:
 * - **BASE** is the universal free floor.
 * - The **largest offered model is Pro** ONLY when the device surfaces ≥ 3 tiers (base + at least
 *   one free better model + a larger Pro model). A phone whose ceiling is exactly one tier above
 *   base gets that better model **free** and has no model-Pro lever (Pro falls back to sync/AI).
 * - Everything below the Pro model is free.
 *
 * ## No-bypass (safety) — the load-bearing guarantee this policy enforces
 * A user preference can NEVER pull in a model outside [offered] (Gate 1/2 already withheld it) or
 * above the entitlement cap. Preference is *clamped*, never authoritative: preferring `small` on an
 * A03 (offered = {base}) resolves to `base`, structurally. This is what the A03 refusal proof
 * asserts.
 *
 * ## Immutability (prime directive 2)
 * This selects for FUTURE recordings only. Existing transcripts are immutable and are NEVER
 * re-transcribed or degraded on entitlement lapse — only what a new recording picks changes.
 */
class ModelSelectionPolicy(
    private val entitlements: Entitlements,
) {
    /**
     * @param offered the device's gated set (both gates passed), from [ModelCapabilityResolver].
     *   MUST always contain BASE (the floor); if empty, BASE is still the honest fallback.
     * @param downloaded which non-floor models are present on disk (BASE is always available).
     * @param preferred the user's chosen model, or null for "best I'm allowed".
     * @return the model a new recording will use — always a real, allowed, available model.
     */
    fun selectFor(
        offered: List<ModelSpec>,
        downloaded: Set<ModelId>,
        preferred: ModelId?,
    ): ModelId {
        val hasPro = entitlements.has(Feature.PREMIUM_MODELS)
        val sorted = offered.sortedBy { it.id.tier }.ifEmpty { listOf(EchoModelCatalog.base) }

        // The Pro model exists only with ≥3 tiers; below it (and the whole set otherwise) is free.
        val proModel: ModelSpec? = if (sorted.size >= 3) sorted.last() else null
        val freeCeiling: ModelSpec = (if (proModel != null) sorted.dropLast(1) else sorted).last()
        val cap: ModelSpec = if (hasPro) (proModel ?: freeCeiling) else freeCeiling

        // Candidates: offered, at or below the entitlement cap, and actually on disk. BASE always.
        val candidates = sorted.filter { spec ->
            spec.id.tier <= cap.id.tier && (spec.isFloor || spec.id in downloaded)
        }

        // Honor the preference only if it survives every gate/cap/availability check; else the best
        // allowed+downloaded model; else the floor. Preference is clamped, never a bypass.
        return preferred?.takeIf { p -> candidates.any { it.id == p } }
            ?: candidates.maxByOrNull { it.id.tier }?.id
            ?: ModelId.BASE
    }
}
