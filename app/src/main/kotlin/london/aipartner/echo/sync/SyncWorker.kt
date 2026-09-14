package london.aipartner.echo.sync

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
import london.aipartner.echo.core.data.RecordingDao
import london.aipartner.echo.core.data.SyncRefDao
import london.aipartner.echo.core.sync.BackupCoordinator
import london.aipartner.echo.core.sync.SinkId
import london.aipartner.echo.core.sync.SyncRecordingState

/**
 * Phase 8 — the background backup worker. Walks recordings that aren't yet backed up and,
 * for each, drives the [BackupCoordinator] (gate → read real audio/transcript/metadata →
 * re-encrypt with the user's backup key → upload all three, SYNCED only if all three land).
 *
 * Runs as a **foreground `dataSync` worker** (uploads can take a while on a slow link) and,
 * like the transcription worker, is enqueued as **unique serial work** so backups never
 * pile up concurrently. It loads NO whisper context — the Phase-7 memory discipline holds
 * (a backup pass and a transcription never contend for the native model).
 *
 * **Dedup on retry (the airplane-mode guarantee):** already-SYNCED recordings are skipped
 * (their persisted [SyncRefEntity] state), and each artifact PUTs a deterministic flat blob
 * name, so a retried/resumed upload overwrites in place — one remote copy, never two. A
 * transient failure (network drop mid-upload) ⇒ [Result.retry]; a gate block (egress off /
 * not Pro / no destination) is terminal-for-now ⇒ [Result.success] (no spin).
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: BackupCoordinator,
    private val recordingDao: RecordingDao,
    private val syncRefDao: SyncRefDao,
    private val configStore: WebDavConfigStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // No destination configured ⇒ nothing to do (the destination-selection UI is a later
        // step; the real-VPS creds are written out-of-band for the A03 end-to-end).
        if (configStore.current() == null) {
            Log.i(TAG, "No backup destination configured; nothing to back up")
            return Result.success()
        }

        runCatching { setForeground(foregroundInfo()) }
            .onFailure { Log.w(TAG, "Could not enter foreground for backup (continuing)", it) }

        val pending = pendingRecordingIds()
        if (pending.isEmpty()) return Result.success()

        Log.i(TAG, "Backing up ${pending.size} recording(s)")
        val results = coordinator.backUpAll(pending)

        // A transient upload failure (airplane mode mid-run) should resume on retry; skipped
        // (already-SYNCED) recordings make the retry idempotent — no duplicate uploads.
        val anyTransientFailure = results.values.any { it == SyncRecordingState.FAILED }
        return if (anyTransientFailure) {
            Log.w(TAG, "Some backups failed; requesting retry (already-synced are skipped)")
            Result.retry()
        } else {
            Result.success()
        }
    }

    /** Recordings without a SYNCED WebDAV ref yet — the ones a backup pass must (re)try. */
    private suspend fun pendingRecordingIds(): List<String> =
        recordingDao.getAll().filter { recording ->
            syncRefDao.forRecording(recording.id).none {
                it.sinkId == SinkId.WEBDAV.name && it.state == SyncRecordingState.SYNCED.name
            }
        }.map { it.id }

    private fun foregroundInfo(): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("Larx")
            .setContentText("Backing up…")
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
                CHANNEL_ID, "Backup", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Encrypted cloud backup in progress" }
            appContext.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "echo-backup"
        private const val TAG = "EchoSyncWork"
        private const val CHANNEL_ID = "echo_backup"
        private const val NOTIF_ID = 2002
    }
}
