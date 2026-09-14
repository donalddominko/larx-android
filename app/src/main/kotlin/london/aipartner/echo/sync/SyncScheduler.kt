package london.aipartner.echo.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues a [SyncWorker] backup pass. Unique serial work (one backup pass at a time) with a
 * **CONNECTED network constraint** — so a pass enqueued while offline (airplane mode) simply
 * waits for connectivity and then resumes, and exponential backoff covers a mid-run drop.
 * Combined with the worker's skip-already-SYNCED logic, this is what makes a resumed upload
 * dedup to a single remote copy.
 *
 * The trigger surface is intentionally minimal in this step (called on app start): the
 * explicit "Back up now" control + auto-enqueue-on-new-recording belong with the
 * destination-selection UI (Group A, a later step). The gate still fails closed — an
 * enqueued pass with egress off / no destination is a no-op.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueueBackup() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(SyncWorker.UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncWorker.UNIQUE_WORK_NAME,
            // KEEP: if a backup pass is already queued/running, don't stack another.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
