package london.aipartner.echo.core.billing

/**
 * Seam 3 — WHAT the user may use. The single source of truth for free-vs-Pro.
 * Every gated feature asks [has]; no screen talks to Billing directly. Backed by
 * Play Billing state + a cached entitlement in Phase 9 (fail-closed on the paid
 * side, fail-open within a grace window so a paying user isn't punished offline).
 */
interface Entitlements {
    fun has(feature: Feature): Boolean
}

/** The set of gated capabilities. Grows as paid features land. */
enum class Feature {
    CLOUD_TRANSCRIPTION,
    CLOUD_SYNC,
    AI_SUMMARY,
    SEMANTIC_SEARCH,

    /**
     * Better on-device transcription models (Pro v1 launch lever). Read by the
     * model-selection policy at the moment a NEW recording is about to be
     * transcribed, to cap the maximum tier it may use: held ⇒ up to the device's
     * largest gate-qualified model; not held ⇒ capped at the best FREE-eligible
     * model that phone's two gates admit. NEVER re-transcribes existing artifacts
     * (immutable — prime directive 2); only future selection changes on lapse.
     * Zero marginal cost — models are a one-time on-device download.
     */
    PREMIUM_MODELS,
}
