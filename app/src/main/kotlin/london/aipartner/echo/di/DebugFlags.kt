package london.aipartner.echo.di

import android.content.Context

/**
 * Read-only accessor for debug-only override flags. The **reader** lives here because
 * the release-graph `Entitlements` provider references it, but every call site guards
 * it behind `BuildConfig.DEBUG`, so in a release build the value is a compile-time-dead
 * `false` (the prefs are never even read). The **writer** exists ONLY in the
 * `directDebug` source set (the Phase-8 verify harness) — a release build has no code
 * path that can set `pro_unlocked = true`. This is the "flip Pro, directDebug only,
 * never the release graph" contract from `references/phase-08-cloud-sync.md`.
 */
object DebugFlags {
    const val PREFS = "echo_debug_flags"
    const val KEY_PRO_UNLOCKED = "pro_unlocked"

    /** True only when a directDebug harness has set the flag AND this is a debug build. */
    fun proUnlocked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PRO_UNLOCKED, false)
}
