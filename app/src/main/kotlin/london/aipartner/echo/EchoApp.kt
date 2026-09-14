package london.aipartner.echo

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import london.aipartner.echo.transcribe.SemanticBackfillWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import london.aipartner.echo.core.data.RecordingDao

@HiltAndroidApp
class EchoApp : Application(), Configuration.Provider {

    /** Hilt-aware factory so [london.aipartner.echo.transcribe.TranscriptionWorker] can have its
     *  dependencies injected. Paired with the default WorkManager initializer being removed from
     *  the manifest (on-demand init). */
    @Inject lateinit var workerFactory: HiltWorkerFactory

    /** Phase 8 — kicks a backup pass on start. Fails closed (no-op) unless egress is on,
     *  Pro is held, a destination is configured, and a passphrase is set. */
    @Inject lateinit var syncScheduler: london.aipartner.echo.sync.SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RecoveryEntryPoint {
        fun recordingDao(): RecordingDao
    }

    override fun onCreate() {
        super.onCreate()
        // Startup recovery: a transcription worker killed mid-pass (process death) can leave a
        // recording stuck RUNNING. Reset any still-RUNNING transcription at process start to FAILED
        // so the UI is honest and retryable instead of a permanent "Transcribing…".
        val dao = EntryPointAccessors
            .fromApplication(this, RecoveryEntryPoint::class.java)
            .recordingDao()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { dao.failOrphanedRunningTranscriptions() }
                .onSuccess { if (it > 0) Log.i("EchoApp", "Recovered $it orphaned RUNNING transcription(s) → FAILED") }
                .onFailure { Log.w("EchoApp", "Orphan-transcription recovery failed", it) }
        }

        // Phase 8: enqueue an encrypted backup pass. The SyncGate + destination + passphrase
        // checks fail closed, so this is a no-op until the user has turned everything on.
        runCatching { syncScheduler.enqueueBackup() }
            .onFailure { Log.w("EchoApp", "Could not enqueue backup pass", it) }

        // Self-healing semantic search: re-index any DONE recording that lacks a current-model
        // embedding (transcribed on a build where the embedder was broken, or before search
        // existed) so it becomes searchable. Idempotent + best-effort; KEEP so it never stacks.
        runCatching {
            WorkManager.getInstance(this).enqueueUniqueWork(
                SemanticBackfillWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SemanticBackfillWorker>().build(),
            )
        }.onFailure { Log.w("EchoApp", "Could not enqueue semantic backfill", it) }
    }
}
