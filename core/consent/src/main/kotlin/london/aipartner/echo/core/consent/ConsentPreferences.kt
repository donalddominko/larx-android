package london.aipartner.echo.core.consent

import android.content.Context

/**
 * Persisted consent-adjacent state for a self-recording memo app.
 *
 * - [disclosureAcknowledged] — whether the user has seen the one-time first-run
 *   disclosure. This is a UI affordance flag ONLY: it controls whether the
 *   disclosure screen is shown, and is NEVER read by [ConsentGate]. Acknowledging
 *   the disclosure does not unlock recording — RECORD_AUDIO + the gate do.
 * - [dataEgressAllowed] — the consent that actually matters for a privacy-first
 *   recorder: "audio/transcripts may leave this device". Defaults OFF; cloud
 *   transcription (Phase 4) and sync (Phase 8) must fail closed without it.
 */
class ConsentPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("echo_consent", Context.MODE_PRIVATE)

    var disclosureAcknowledged: Boolean
        get() = prefs.getBoolean(KEY_DISCLOSURE, false)
        set(value) = prefs.edit().putBoolean(KEY_DISCLOSURE, value).apply()

    /** Off by default — no audio leaves the device until the user turns this on. */
    var dataEgressAllowed: Boolean
        get() = prefs.getBoolean(KEY_EGRESS, false)
        set(value) = prefs.edit().putBoolean(KEY_EGRESS, value).apply()

    private companion object {
        const val KEY_DISCLOSURE = "disclosure_acknowledged"
        const val KEY_EGRESS = "data_egress_allowed"
    }
}
