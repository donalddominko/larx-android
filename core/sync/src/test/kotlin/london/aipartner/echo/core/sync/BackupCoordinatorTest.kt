package london.aipartner.echo.core.sync

import kotlinx.coroutines.runBlocking
import london.aipartner.echo.core.billing.Entitlements
import london.aipartner.echo.core.billing.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-recording orchestration: a recording backs up as its THREE artifacts
 * (audio + transcript + metadata) and is SYNCED only when all three uploaded, else
 * FAILED/BLOCKED — never reported as backed up on a partial upload. Also re-proves the
 * egress gate at the recording level (blocked ⇒ no UPLOADING, no puts).
 */
class BackupCoordinatorTest {

    private class SpySink : CloudSink {
        override val id = SinkId.WEBDAV
        val puts = mutableListOf<String>()      // remoteName of each put
        var failOnKind: ArtifactKind? = null
        override suspend fun put(artifact: SyncArtifact, payload: ByteArray): RemoteRef {
            if (artifact.kind == failOnKind) throw WebDavHttpError(500, "PUT")
            puts += artifact.remoteName
            return RemoteRef(id, artifact.remoteName)
        }
        override suspend fun delete(ref: RemoteRef) {}
    }

    private class RecordingStore : SyncStateStore {
        val transitions = mutableListOf<Pair<SyncRecordingState, String?>>()
        override suspend fun update(recordingId: String, state: SyncRecordingState, error: String?) {
            transitions += state to error
        }
        val states get() = transitions.map { it.first }
    }

    private val entitled = object : Entitlements { override fun has(feature: Feature) = true }
    private fun coordinator(sink: CloudSink, store: SyncStateStore, egressOn: Boolean): BackupCoordinator {
        val gate = SyncGate({ egressOn }, entitled)
        return BackupCoordinator(SyncEngine(gate, { ByteArray(64) }, { it }, sink), gate, store)
    }

    @Test fun backsUpAllThreeArtifacts_thenSynced() = runBlocking {
        val sink = SpySink(); val store = RecordingStore()
        val state = coordinator(sink, store, egressOn = true).backUp("rec-1")

        assertEquals(SyncRecordingState.SYNCED, state)
        assertEquals(
            listOf("rec-1-audio", "rec-1-transcript", "rec-1-metadata"),
            sink.puts,
        )
        assertEquals(listOf(SyncRecordingState.UPLOADING, SyncRecordingState.SYNCED), store.states)
    }

    @Test fun egressOff_blocksBeforeUploading_noPuts() = runBlocking {
        val sink = SpySink(); val store = RecordingStore()
        val state = coordinator(sink, store, egressOn = false).backUp("rec-1")

        assertEquals(SyncRecordingState.BLOCKED, state)
        assertTrue(sink.puts.isEmpty())
        assertEquals(listOf(SyncRecordingState.BLOCKED), store.states) // never went UPLOADING
    }

    @Test fun partialFailure_marksRecordingFailed_notSynced() = runBlocking {
        val sink = SpySink().apply { failOnKind = ArtifactKind.TRANSCRIPT }
        val store = RecordingStore()
        val state = coordinator(sink, store, egressOn = true).backUp("rec-1")

        assertEquals(SyncRecordingState.FAILED, state)
        assertEquals(listOf("rec-1-audio"), sink.puts) // stopped after the failed artifact
        assertEquals(listOf(SyncRecordingState.UPLOADING, SyncRecordingState.FAILED), store.states)
    }

    @Test fun backUpAll_reportsPerRecordingState() = runBlocking {
        val sink = SpySink(); val store = RecordingStore()
        val result = coordinator(sink, store, egressOn = true).backUpAll(listOf("a", "b"))

        assertEquals(mapOf("a" to SyncRecordingState.SYNCED, "b" to SyncRecordingState.SYNCED), result)
        assertEquals(6, sink.puts.size) // 3 artifacts × 2 recordings
    }
}
