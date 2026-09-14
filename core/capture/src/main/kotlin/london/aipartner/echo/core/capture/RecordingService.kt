package london.aipartner.echo.core.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import london.aipartner.echo.core.consent.CaptureMode

/**
 * Recording foreground service (FGS type = microphone). It owns the notification
 * + lifecycle and delegates all capture logic to [RecorderController]. It never
 * starts capture except via the controller's `ConsentGate`-guarded `start`.
 *
 * On (re)start it attempts [RecorderController.recoverOrphan] so a recording
 * interrupted by process death is finalized + surfaced rather than lost.
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var controller: RecorderController

    /** Downstream processing once audio is persisted — on-device transcription (Step 4).
     *  Bound in `:app`; the service keeps the FGS alive across it then tears down. */
    @Inject lateinit var postProcessor: RecordingPostProcessor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Set once the first ACTION_STOP begins finalizing. A repeat STOP (double-tap, or
    // notification + UI) must NOT launch a second stop that tears the service down and
    // cancels the in-progress finalize (the gain re-encode can take seconds) — that lost
    // the recording. While finalizing, further STOP intents are ignored.
    private val finalizing = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        scope.launch { controller.recoverOrphan() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val job = runCatching { CaptureJob.valueOf(intent.getStringExtra(EXTRA_JOB) ?: "") }
                    .getOrDefault(CaptureJob.MEMO)
                val mode = runCatching { CaptureMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "") }
                    .getOrDefault(CaptureMode.ON_DEMAND)
                startForegroundCompat(buildNotification("Starting…"))
                scope.launch {
                    val hasPerm = hasRecordPermission()
                    try {
                        when (val r = controller.start(job, mode, hasPerm)) {
                            is StartResult.Started -> updateNotification(badge(r.capability))
                            is StartResult.Denied -> {
                                // Not an error (e.g. permission missing), but make the
                                // teardown reason visible rather than a silent vanish.
                                Log.w(TAG, "Capture denied: ${r.reason}")
                                stopForegroundAndSelf()
                            }
                        }
                    } catch (t: Throwable) {
                        // A start failure (mic busy, codec config fails on every codec)
                        // must NOT crash the app or leave a phantom "Starting…" FGS
                        // notification. Log the breadcrumb and tear the service down.
                        Log.e(TAG, "Capture start failed; tearing down service", t)
                        stopForegroundAndSelf()
                    }
                }
            }
            ACTION_PAUSE -> scope.launch {
                runCatching { controller.pause() }.onFailure { Log.e(TAG, "Pause failed", it) }
            }
            ACTION_RESUME -> scope.launch {
                runCatching { controller.resume() }.onFailure { Log.e(TAG, "Resume failed", it) }
            }
            ACTION_STOP -> {
                // Only the FIRST stop finalizes + tears down; ignore repeats so we never
                // cancel an in-flight finalize.
                if (finalizing.compareAndSet(false, true)) {
                    scope.launch {
                        var saved: london.aipartner.echo.core.data.RecordingEntity? = null
                        try {
                            saved = controller.stop()
                        } catch (t: Throwable) {
                            // The controller's finally already recovered state to Idle;
                            // this is the logcat breadcrumb so a future stop failure
                            // (different cause) is never invisible like the old wedge.
                            Log.e(TAG, "Recording stop failed (controller recovered to Idle)", t)
                        }
                        // Audio is now persisted. Transcription is DECOUPLED (Phase 7): the
                        // post-processor merely ENQUEUES a background WorkManager job and returns
                        // immediately, so this service tears down at once and a long transcription
                        // can NEVER block a new recording's Stop (the structural "stop doesn't
                        // work" cause). The audio is safe regardless of transcription outcome.
                        val entity = saved
                        val audioRef = entity?.localAudioRef
                        try {
                            if (entity != null && audioRef != null) {
                                postProcessor.process(entity.id, audioRef)
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Enqueuing transcription failed (recording is safe)", t)
                        } finally {
                            stopForegroundAndSelf()
                        }
                    }
                } else {
                    Log.i(TAG, "ACTION_STOP ignored — already finalizing")
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun hasRecordPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun stopForegroundAndSelf() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun badge(c: CaptureCapability): String = "Recording — microphone"

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            // Use the app's real launcher label (app_name = "Larx"), never the internal
            // "Echo" codename — a hardcoded "Echo" leaked the codename into every recording
            // notification (Donald spotted it on-device 2026-07-31). Reading the label keeps
            // this in lockstep with app_name and can't drift on a future rename.
            .setContentTitle(applicationInfo.loadLabel(packageManager).toString())
            .setContentText(text)
            .setSmallIcon(london.aipartner.echo.core.capture.R.drawable.ic_stat_larx)
            .setOngoing(true)
            .setUsesChronometer(true)
            // Show the recording indicator immediately — don't let Android defer
            // the FGS notification ~10s (which reads as "nothing happened").
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Pause", servicePendingIntent(ACTION_PAUSE))
            .addAction(0, "Resume", servicePendingIntent(ACTION_RESUME))
            .addAction(0, "Stop", servicePendingIntent(ACTION_STOP))
            .build()

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Active recording controls" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "london.aipartner.echo.capture.START"
        const val ACTION_PAUSE = "london.aipartner.echo.capture.PAUSE"
        const val ACTION_RESUME = "london.aipartner.echo.capture.RESUME"
        const val ACTION_STOP = "london.aipartner.echo.capture.STOP"
        const val EXTRA_JOB = "job"
        const val EXTRA_MODE = "mode"

        private const val TAG = "EchoCapture"
        private const val CHANNEL_ID = "echo_recording"
        private const val NOTIF_ID = 1001

        fun startIntent(context: Context, job: CaptureJob, mode: CaptureMode): Intent =
            Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_JOB, job.name)
                .putExtra(EXTRA_MODE, mode.name)
    }
}
