package london.aipartner.echo.transcribe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Phase 7 — transcription decoupled from the record foreground service into a **background
 * WorkManager job** (Donald, 2026-07-03).
 *
 * WHY: previously the record FGS ran the whisper pass inline after stop and stayed alive
 * ("Transcribing…") the whole time, holding its `finalizing` guard — so a LEGIT long
 * transcription blocked a *new* recording's Stop until it finished (the "stop doesn't work"
 * structural cause, guaranteed to bite on the slow A03: record long → record again → stop
 * wedges). Moving transcription here frees the record service the instant audio is saved;
 * capture control is never blocked by a prior transcription again.
 *
 * MEMORY DISCIPLINE (the constraint that drove the original in-FGS choice — must survive the
 * move, Donald's binding condition):
 *  - [TranscribingPostProcessor] still FREES the native whisper context after every pass
 *    (its `finally { releaseResources() }`), so whisper never stays resident between jobs.
 *  - These jobs are enqueued as **unique serial work** (see [WorkManagerPostProcessor]), so at
 *    most ONE transcription — hence at most one whisper context — is ever active. Two whisper
 *    contexts never co-reside; the Phase-5 OOM is not resurrected.
 *  - The only new co-residence is capture (light MediaRecorder native stack) + one
 *    background transcription, which is the case Donald asked to verify survivable on the A03.
 */
@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val postProcessor: TranscribingPostProcessor,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val recordingId = inputData.getString(KEY_RECORDING_ID)
        val audioRef = inputData.getString(KEY_AUDIO_REF)
        if (recordingId == null || audioRef == null) {
            Log.e(TAG, "Missing input data (recordingId/audioRef); nothing to transcribe")
            return Result.failure()
        }

        // Run as a long-running foreground worker: the whisper pass can take minutes on a long
        // memo on the A03, and we don't want the OS to kill it. The post-processor owns the
        // status transitions (RUNNING → DONE/FAILED) and the progress bus, exactly as before.
        runCatching { setForeground(foregroundInfo()) }
            .onFailure { Log.w(TAG, "Could not enter foreground for transcription (continuing)", it) }

        // process() is already fully defensive: it sets FAILED on any throw and never loses the
        // audio, and frees the whisper context in its finally. A thrown error here would only
        // trigger a WorkManager retry of an already-FAILED-marked recording, so swallow to success.
        runCatching { postProcessor.process(recordingId, audioRef) }
            .onFailure { Log.e(TAG, "Transcription worker failed for $recordingId (audio is safe)", it) }
        return Result.success()
    }

    private fun foregroundInfo(): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("Larx")
            .setContentText("Transcribing…")
            .setSmallIcon(london.aipartner.echo.R.drawable.ic_stat_larx)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Transcription", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "On-device transcription in progress" }
            appContext.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val KEY_RECORDING_ID = "recordingId"
        const val KEY_AUDIO_REF = "audioRef"

        /** All transcriptions share ONE unique serial chain so only one whisper context is ever
         *  live (memory discipline). Named per-app, not per-recording. */
        const val UNIQUE_WORK_NAME = "echo-transcription"

        private const val TAG = "EchoTranscribeWork"
        private const val CHANNEL_ID = "echo_transcription"
        private const val NOTIF_ID = 2001
    }
}
