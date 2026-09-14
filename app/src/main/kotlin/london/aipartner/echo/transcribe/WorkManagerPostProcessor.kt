package london.aipartner.echo.transcribe

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import javax.inject.Inject
import london.aipartner.echo.core.capture.RecordingPostProcessor

/**
 * The [RecordingPostProcessor] the record service now sees (Phase 7 decouple, Donald 2026-07-03).
 *
 * Instead of running the whisper pass inline (which kept the record FGS alive and blocked a new
 * recording's Stop), [process] simply **enqueues** a [TranscriptionWorker] and returns
 * immediately — so the record service tears down the instant audio is saved and capture control
 * is never blocked by a prior transcription.
 *
 * The jobs are enqueued as **unique serial work** ([ExistingWorkPolicy.APPEND_OR_REPLACE] on one
 * shared name): they run one-at-a-time, so at most one whisper context is ever live (the memory
 * discipline the original in-FGS design protected — preserved here, not resurrecting the Phase-5
 * OOM). Each recording's real transcription is done by [TranscribingPostProcessor] inside the
 * worker, which still frees the native context after every pass.
 */
class WorkManagerPostProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
) : RecordingPostProcessor {

    override suspend fun process(recordingId: String, audioRef: String) {
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(
                Data.Builder()
                    .putString(TranscriptionWorker.KEY_RECORDING_ID, recordingId)
                    .putString(TranscriptionWorker.KEY_AUDIO_REF, audioRef)
                    .build(),
            )
            .addTag(TranscriptionWorker.UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TranscriptionWorker.UNIQUE_WORK_NAME,
            // APPEND_OR_REPLACE: serialize behind any in-flight transcription (one whisper context
            // at a time); if the prior chain finished/failed, start fresh rather than stall.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }
}
