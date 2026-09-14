package london.aipartner.echo.core.sync

/** The sync lifecycle of one recording, persisted as `SyncRefEntity.state`. */
enum class SyncRecordingState { PENDING, UPLOADING, SYNCED, FAILED, BLOCKED }

/**
 * Records a recording's sync state. Bound in `:app` to persist via `SyncRefDao`
 * (`SyncRef.state` + `lastError`), so a resumed/failed backup is an honest, retryable
 * state rather than a silent loss.
 */
fun interface SyncStateStore {
    suspend fun update(recordingId: String, state: SyncRecordingState, error: String?)
}

/**
 * Backs up ONE recording as its three artifacts (audio + latest transcript + metadata),
 * driving each through the [SyncEngine] (gate → read → re-encrypt → put). The unit of
 * state is the whole recording: it becomes SYNCED only when all three uploaded, else
 * FAILED/BLOCKED with the reason — so a partially-uploaded recording is never reported
 * as backed up. The WorkManager worker calls this per pending recording (serial).
 */
class BackupCoordinator(
    private val engine: SyncEngine,
    private val gate: SyncGate,
    private val store: SyncStateStore,
) {
    suspend fun backUp(recordingId: String): SyncRecordingState {
        // The egress gate, at the very top — before UPLOADING or any byte is read.
        gate.blockReason()?.let {
            store.update(recordingId, SyncRecordingState.BLOCKED, it.detail)
            return SyncRecordingState.BLOCKED
        }
        store.update(recordingId, SyncRecordingState.UPLOADING, null)
        for (kind in ArtifactKind.values()) {
            when (val outcome = engine.sync(SyncArtifact(recordingId, kind, localPath = ""))) {
                is SyncOutcome.Synced -> Unit
                is SyncOutcome.Blocked -> {
                    store.update(recordingId, SyncRecordingState.BLOCKED, outcome.reason.detail)
                    return SyncRecordingState.BLOCKED
                }
                is SyncOutcome.Failed -> {
                    store.update(recordingId, SyncRecordingState.FAILED, outcome.error.message)
                    return SyncRecordingState.FAILED
                }
            }
        }
        store.update(recordingId, SyncRecordingState.SYNCED, null)
        return SyncRecordingState.SYNCED
    }

    /** Back up a set of pending recordings, serially (never blocks capture/transcription). */
    suspend fun backUpAll(recordingIds: List<String>): Map<String, SyncRecordingState> =
        recordingIds.associateWith { backUp(it) }
}
