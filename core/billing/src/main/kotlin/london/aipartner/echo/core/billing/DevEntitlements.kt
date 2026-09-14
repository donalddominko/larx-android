package london.aipartner.echo.core.billing

/**
 * Phase 1 stub. Defaults **Pro-off** so gating is honest from day one — the free
 * experience is what's exercised by default, and the Phase 1 gate (Hilt graph +
 * DB round-trip) does NOT depend on entitlement state.
 *
 * [proUnlocked] is the single documented dev flag to flip on (e.g. from a debug
 * menu or a test) to exercise the Pro path before Billing exists.
 *
 * Phase 9 replaces this with real Play Billing state and the fail-closed /
 * fail-open-within-grace policy; this stub is removed then.
 */
class DevEntitlements(
    private val proUnlocked: Boolean = false,
) : Entitlements {
    override fun has(feature: Feature): Boolean = proUnlocked
}
