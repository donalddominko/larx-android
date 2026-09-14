package london.aipartner.echo

/**
 * Compile-time feature gates for surfaces whose backend exists but must NOT be exposed in v1.
 *
 * These are STRUCTURAL gates, not runtime config: a `false` here removes the whole surface from
 * the shipped UI (and R8 can prune the dead branches), so the app can never advertise a feature it
 * can't deliver. Flip to `true` in the fast-follow that actually ships the feature.
 */
object FeatureFlags {
    /**
     * Cloud backup / sync Settings surface (egress toggle + passphrase setup).
     *
     * **OFF for v1 (Phase 10):** the app ships with **no `INTERNET` permission** (OS-enforced
     * no-egress — the privacy USP + the store claim "recordings cannot leave your device"). Cloud
     * backup therefore PHYSICALLY CANNOT WORK; exposing it would advertise a guaranteed-to-fail
     * feature and contradict the store listing. The `:core:sync` engine + the passphrase-setup
     * screen stay in the codebase, just unreachable. Turn this `true` in the sync fast-follow that
     * re-adds `INTERNET` — and, per the release-blocking coupling gate, updates the Data Safety
     * form + privacy policy in that SAME release (see references/phase-08-cloud-sync.md).
     */
    const val CLOUD_BACKUP = false

    /**
     * Generative AI **summary / action-items** sections on the recording detail screen.
     *
     * **OFF for v1:** the generative layer ([london.aipartner.echo.core.transcribe.AiEngine]) is
     * cloud-only, Pro-gated, off by default, and the shipping build has **no `INTERNET`**, so v1
     * NEVER produces a SUMMARY/ACTIONS artifact. The detail screen already renders those sections
     * only when the artifact exists (so they never appear in practice), but this makes it
     * **structurally impossible** — a store reviewer or user can never see a summary/actions surface
     * for a feature v1 doesn't ship (same discipline as [CLOUD_BACKUP]). The rendering code +
     * `AiKind.SUMMARY/ACTIONS` stay in the codebase; flip this `true` in the AI-summaries Pro
     * fast-follow (which re-adds `INTERNET` and updates the Data Safety form + privacy policy in the
     * same release, per the coupling gate).
     */
    const val SUMMARIES = false
}
