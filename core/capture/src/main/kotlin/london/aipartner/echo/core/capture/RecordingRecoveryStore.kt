package london.aipartner.echo.core.capture

import android.content.Context

/**
 * Durable record of the single in-progress capture, written at start and cleared
 * on successful finalize. If the service process is killed mid-recording, this is
 * what lets [RecorderController.recoverOrphan] find the partial and surface it
 * rather than lose it silently.
 */
class RecordingRecoveryStore(context: Context) {

    private val prefs = context.getSharedPreferences("echo_capture_recovery", Context.MODE_PRIVATE)

    data class Orphan(
        val recordingId: String,
        val tempPath: String,
        val startedAt: Long,
        val sourceId: CaptureSourceId,
        val codec: Codec,
        val captureMode: String,
    )

    fun markStarted(orphan: Orphan) {
        prefs.edit()
            .putString(KEY_ID, orphan.recordingId)
            .putString(KEY_TEMP, orphan.tempPath)
            .putLong(KEY_STARTED, orphan.startedAt)
            .putString(KEY_SOURCE, orphan.sourceId.name)
            .putString(KEY_CODEC, orphan.codec.name)
            .putString(KEY_MODE, orphan.captureMode)
            .apply()
    }

    fun read(): Orphan? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        return Orphan(
            recordingId = id,
            tempPath = prefs.getString(KEY_TEMP, null) ?: return null,
            startedAt = prefs.getLong(KEY_STARTED, 0L),
            sourceId = runCatching {
                CaptureSourceId.valueOf(prefs.getString(KEY_SOURCE, "") ?: "")
            }.getOrDefault(CaptureSourceId.MIC),
            codec = runCatching {
                Codec.valueOf(prefs.getString(KEY_CODEC, "") ?: "")
            }.getOrDefault(Codec.AAC),
            captureMode = prefs.getString(KEY_MODE, "ON_DEMAND") ?: "ON_DEMAND",
        )
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_ID = "id"
        const val KEY_TEMP = "temp"
        const val KEY_STARTED = "started"
        const val KEY_SOURCE = "source"
        const val KEY_CODEC = "codec"
        const val KEY_MODE = "mode"
    }
}
