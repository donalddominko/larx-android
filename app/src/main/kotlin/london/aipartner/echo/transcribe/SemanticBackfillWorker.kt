package london.aipartner.echo.transcribe

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background WorkManager job that runs the [SemanticBackfill] pass on startup (2026-07-20).
 *
 * A plain (non-foreground) [CoroutineWorker]: this is low-priority catch-up, not user-visible
 * work, and runs off the main thread on WorkManager's executor. It is enqueued as UNIQUE work
 * (see [EchoApp]) with `KEEP`, so a pending/running backfill is never stacked. The pass itself is
 * idempotent and best-effort, so a swallowed failure here just means "try again next launch".
 */
@HiltWorker
class SemanticBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backfill: SemanticBackfill,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        runCatching { backfill.run() }
            .onFailure { Log.e(TAG, "Semantic backfill pass failed (will retry next launch)", it) }
        return Result.success()
    }

    companion object {
        /** Single unique chain so a backfill never stacks on itself across quick restarts. */
        const val UNIQUE_WORK_NAME = "echo-semantic-backfill"
        private const val TAG = "EchoSemanticBackfill"
    }
}
