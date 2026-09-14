package london.aipartner.echo.recordings

import java.io.File
import london.aipartner.echo.core.data.RecordingDao
import london.aipartner.echo.core.data.SyncRefDao
import london.aipartner.echo.core.sync.CloudSink
import london.aipartner.echo.core.sync.RemoteRef
import london.aipartner.echo.core.sync.SinkId

/**
 * The delete-everywhere contract (Phase 3 defines it; Phase 6 adds the UI to
 * invoke it, Phase 8 makes the sink propagation real). Deletion:
 *   1. propagates to every `CloudSink` the recording was synced to,
 *   2. removes the local encrypted audio file,
 *   3. removes the DB row (FK CASCADE clears transcript/AI/consent/sync rows).
 *
 * v1 has no sync, so step 1 is a no-op over an empty SyncRef set — but the
 * contract is wired so a recording can never be "deleted" locally while lingering
 * in a sink.
 */
class RecordingDeleter(
    private val recordingDao: RecordingDao,
    private val syncRefDao: SyncRefDao,
    private val cloudSink: CloudSink,
) {
    suspend fun delete(recordingId: String) {
        // 1. delete-everywhere: propagate to each sink first.
        syncRefDao.forRecording(recordingId).forEach { ref ->
            val remote = ref.remoteRef ?: return@forEach
            cloudSink.delete(RemoteRef(runCatching { SinkId.valueOf(ref.sinkId) }
                .getOrDefault(SinkId.LOCAL_ONLY), remote))
        }
        // 2. local encrypted audio.
        recordingDao.getById(recordingId)?.localAudioRef?.let { File(it).delete() }
        // 3. DB row (+ cascaded children).
        recordingDao.deleteById(recordingId)
    }
}
