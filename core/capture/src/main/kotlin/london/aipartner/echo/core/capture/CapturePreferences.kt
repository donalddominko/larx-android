package london.aipartner.echo.core.capture

import android.content.Context

/**
 * User-tunable capture settings. The mic gain is **device-dependent** — the raw MIC
 * source records loud on some phones and very quiet on others (the A03 is quiet even
 * speaking close), and no single automatic target works everywhere. So the user
 * calibrates a boost for *their* device; it's applied to new recordings post-capture
 * (decode → gain → re-encode). 1.0× = off (original capture, no re-encode).
 */
class CapturePreferences(context: Context) {

    private val prefs = context.getSharedPreferences("echo_capture", Context.MODE_PRIVATE)

    /** Linear gain multiplier applied to new recordings, clamped to a sane range. */
    var micGain: Float
        get() = prefs.getFloat(KEY_MIC_GAIN, 1.0f).coerceIn(MIN_GAIN, MAX_GAIN)
        set(value) = prefs.edit().putFloat(KEY_MIC_GAIN, value.coerceIn(MIN_GAIN, MAX_GAIN)).apply()

    companion object {
        const val MIN_GAIN = 1.0f
        const val MAX_GAIN = 12.0f
        private const val KEY_MIC_GAIN = "mic_gain"
    }
}
