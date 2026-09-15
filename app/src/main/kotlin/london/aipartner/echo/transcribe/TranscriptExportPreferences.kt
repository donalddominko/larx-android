package london.aipartner.echo.transcribe

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import london.aipartner.echo.core.transcribe.ExportStyle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-tunable transcript **export style** — the single persisted choice that both Copy and
 * Save read. Mirrors [TranscriptionPreferences]: a thin, deterministic wrapper over a private
 * `SharedPreferences`. There is intentionally no per-action style modal; the user sets this once
 * in Settings and every copy/save uses it.
 *
 * Default [ExportStyle.TIMESTAMPED] — the timestamps are what most people want when pasting a
 * memo/meeting somewhere, and the on-screen player is timestamp-oriented too.
 */
@Singleton
class TranscriptExportPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("echo_export", Context.MODE_PRIVATE)

    /** The persisted export style; defaults to [ExportStyle.TIMESTAMPED]. */
    var style: ExportStyle
        get() = prefs.getString(KEY_STYLE, null)
            ?.let { runCatching { ExportStyle.valueOf(it) }.getOrNull() }
            ?: ExportStyle.TIMESTAMPED
        set(value) = prefs.edit().putString(KEY_STYLE, value.name).apply()

    private companion object {
        const val KEY_STYLE = "export_style"
    }
}
