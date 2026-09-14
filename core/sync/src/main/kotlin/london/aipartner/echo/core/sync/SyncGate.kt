package london.aipartner.echo.core.sync

import london.aipartner.echo.core.billing.Entitlements
import london.aipartner.echo.core.billing.Feature

/**
 * Reads `ConsentPreferences.dataEgressAllowed` without coupling `:core:sync` to the
 * consent module's Android-backed class. Bound in `:app` to
 * `{ consentPreferences.dataEgressAllowed }` (mirrors `:core:transcribe`'s
 * `EgressConsent`). The consent that decides whether user **recordings may leave the
 * device** — the sync analog of the capture forced-DENY guarantee.
 */
fun interface SyncEgressConsent {
    fun isAllowed(): Boolean
}

/** Typed refusal when a sync is attempted without BOTH gates open. */
class SyncRefused(val reason: SyncBlockReason) :
    IllegalStateException("sync refused: ${reason.detail}")

enum class SyncBlockReason(val detail: String) {
    EGRESS_OFF("data-egress consent is off (recordings may not leave the device)"),
    NOT_ENTITLED("Pro entitlement CLOUD_SYNC not held"),
}

/**
 * The deterministic Phase-8 gate. Sync requires BOTH **egress consent** (the binding,
 * testable gate in Phase 8) AND the **Pro entitlement** (fail-closed; its paywall is
 * Phase 9). No model decides this. Checked at the very top of a sync, **before any
 * byte of a recording is read** — the mirror of `CloudTranscriber`.
 *
 * Egress is checked first so a non-Pro user who hasn't turned egress on is reported as
 * EGRESS_OFF (the honest primary reason in Phase 8).
 */
class SyncGate(
    private val egress: SyncEgressConsent,
    private val entitlements: Entitlements,
) {
    /** The block reason, or null when both gates are open. Deterministic. */
    fun blockReason(): SyncBlockReason? = when {
        !egress.isAllowed() -> SyncBlockReason.EGRESS_OFF
        !entitlements.has(Feature.CLOUD_SYNC) -> SyncBlockReason.NOT_ENTITLED
        else -> null
    }

    fun isOpen(): Boolean = blockReason() == null

    /** Throws [SyncRefused] unless both gates are open. */
    fun ensureOpen() {
        blockReason()?.let { throw SyncRefused(it) }
    }
}
