package london.aipartner.echo.sync

import javax.inject.Inject
import london.aipartner.echo.core.data.SyncRefDao
import london.aipartner.echo.core.data.SyncRefEntity
import london.aipartner.echo.core.sync.SinkId
import london.aipartner.echo.core.sync.SyncRecordingState
import london.aipartner.echo.core.sync.SyncStateStore

/**
 * Persists a recording's sync lifecycle into [SyncRefEntity] so a resumed/failed backup is
 * an honest, retryable state rather than a silent loss (the WorkManager worker re-reads this
 * on retry to skip already-SYNCED recordings — the airplane-mode dedup guarantee).
 *
 * One row per (recording, sink): the PK is deterministic (`{recordingId}:{sink}`) so a retry
 * UPSERTs the SAME row rather than accumulating duplicates. [remoteRef] is set to the
 * recording id once SYNCED — a marker the recording's blobs exist remotely, from which
 * delete-everywhere derives the per-artifact remote names (`{recordingId}-{kind}`) it must
 * remove. Only WEBDAV in this step.
 */
class RoomSyncStateStore @Inject constructor(
    private val syncRefDao: SyncRefDao,
) : SyncStateStore {

    override suspend fun update(
        recordingId: String,
        state: SyncRecordingState,
        error: String?,
    ) {
        val sink = SinkId.WEBDAV
        syncRefDao.upsert(
            SyncRefEntity(
                id = "$recordingId:${sink.name}",
                recordingId = recordingId,
                sinkId = sink.name,
                remoteRef = if (state == SyncRecordingState.SYNCED) recordingId else null,
                state = state.name,
                lastError = error,
            ),
        )
    }
}
