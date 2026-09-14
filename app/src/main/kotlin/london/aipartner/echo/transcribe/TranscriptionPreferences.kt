package london.aipartner.echo.transcribe

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Locale

/**
 * User-tunable transcription settings.
 *
 * **Language (Phase 6 Step 4 — Donald decision 2026-06-27):** the default is the **device
 * system locale** (no detection cost, correct for the common case). This override lets a user
 * who records in a language *different from their phone's system language* tell us explicitly,
 * so the memo isn't silently mis-transcribed as confident garbage. `null` = follow the device
 * locale (the default). The value is a BCP-47 language tag (e.g. "sl", "en"); whisper uses the
 * primary subtag.
 *
 * The picker UIs that SET this are deferred to Phase 7 Settings (global default) and a
 * per-recording re-transcribe action (pick-after form — no capture-seam churn). This class is
 * the functional override seam; the engine already honours it via [TranscribingPostProcessor].
 */
@Singleton
class TranscriptionPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("echo_transcription", Context.MODE_PRIVATE)

    /** Overridden transcription language tag, or null to follow the device locale. */
    var languageTagOverride: String?
        get() = prefs.getString(KEY_LANGUAGE, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_LANGUAGE) else putString(KEY_LANGUAGE, value)
        }.apply()

    /** The effective language tag for a new transcription: the override, else device locale. */
    fun effectiveLanguageTag(): String =
        languageTagOverride ?: Locale.getDefault().toLanguageTag()

    private companion object {
        const val KEY_LANGUAGE = "language_tag"
    }
}
