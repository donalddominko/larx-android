package london.aipartner.echo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.test.runTest
import london.aipartner.echo.core.data.AiArtifactEntity
import london.aipartner.echo.core.data.DbPassphraseProvider
import london.aipartner.echo.core.data.EchoDatabase
import london.aipartner.echo.core.data.EmbeddingEntity
import london.aipartner.echo.core.data.RecordingEntity
import london.aipartner.echo.core.data.SyncRefEntity
import london.aipartner.echo.core.data.TranscriptRevisionEntity
import london.aipartner.echo.core.data.TranscriptSegmentEntity
import london.aipartner.echo.core.sync.CloudSink
import london.aipartner.echo.core.sync.RemoteRef
import london.aipartner.echo.core.sync.SinkId
import london.aipartner.echo.core.sync.SyncArtifact
import london.aipartner.echo.recordings.RecordingDeleter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6 Step 3 gate — Delete-everywhere from the UI (instrumented).
 *
 * Asserts the [RecordingDeleter] contract the delete button invokes:
 *  - the delete-everywhere propagation runs **even over an empty sink set** (the v1
 *    case) and still removes the local encrypted file + DB row;
 *  - the cascade clears transcript revisions/segments, AI artifacts, and the
 *    semantic-search embedding (FK CASCADE) — derivations never linger;
 *  - when a sync ref exists, the sink's `delete` IS called (delete-everywhere holds
 *    once Phase 8 makes sinks real).
 */
@RunWith(AndroidJUnit4::class)
class DeleteEverywhereTest {

    private lateinit var db: EchoDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Records every RemoteRef it was asked to delete, so we can assert propagation. */
    private class SpyCloudSink : CloudSink {
        val deleted = mutableListOf<RemoteRef>()
        override val id = SinkId.LOCAL_ONLY
        override suspend fun put(artifact: SyncArtifact, payload: ByteArray): RemoteRef =
            RemoteRef(SinkId.LOCAL_ONLY, "noop")
        override suspend fun delete(ref: RemoteRef) { deleted.add(ref) }
    }

    @Before
    fun setUp() {
        context.deleteDatabase(EchoDatabase.DB_NAME)
        val passphrase = DbPassphraseProvider(context).getOrCreate()
        db = EchoDatabase.build(context, passphrase)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(EchoDatabase.DB_NAME)
    }

    @Test
    fun delete_overEmptySinkSet_removesFileAndCascadesDerivations() = runTest {
        val audioFile = File.createTempFile("echo-del", ".ogg", context.cacheDir)
        audioFile.writeBytes(byteArrayOf(1, 2, 3))
        seed("rec-del", audioFile)
        assertTrue(audioFile.exists())

        val sink = SpyCloudSink()
        val deleter = RecordingDeleter(db.recordingDao(), db.syncRefDao(), sink)
        deleter.delete("rec-del")

        // No sink rows → nothing to propagate to, but the contract still completed.
        assertTrue("no sink delete when sink set empty", sink.deleted.isEmpty())
        // Local encrypted audio gone.
        assertFalse("local audio file removed", audioFile.exists())
        // DB row + cascaded children gone.
        assertNull(db.recordingDao().getById("rec-del"))
        assertTrue(db.transcriptDao().revisionsFor("rec-del").isEmpty())
        assertTrue(db.aiArtifactDao().forRecording("rec-del").isEmpty())
        assertNull("embedding cascade-deleted", db.embeddingDao().forRecording("rec-del"))
    }

    @Test
    fun delete_withSyncRef_propagatesToSink() = runTest {
        val audioFile = File.createTempFile("echo-del2", ".ogg", context.cacheDir)
        audioFile.writeBytes(byteArrayOf(1, 2, 3))
        seed("rec-sync", audioFile)
        db.syncRefDao().upsert(
            SyncRefEntity(
                id = "ref-1",
                recordingId = "rec-sync",
                sinkId = SinkId.LOCAL_ONLY.name,
                remoteRef = "remote-handle",
                state = "SYNCED",
                lastError = null,
            ),
        )

        val sink = SpyCloudSink()
        RecordingDeleter(db.recordingDao(), db.syncRefDao(), sink).delete("rec-sync")

        assertEquals(1, sink.deleted.size)
        assertEquals("remote-handle", sink.deleted.first().remoteId)
        assertNull(db.recordingDao().getById("rec-sync"))
    }

    private suspend fun seed(id: String, audioFile: File) {
        db.recordingDao().insert(
            RecordingEntity(
                id = id,
                createdAt = 1_000L,
                durationMs = 5_000L,
                captureSource = "MIC",
                fidelity = "LOSSY",
                captureMode = "ON_DEMAND",
                contactHash = null,
                localAudioRef = audioFile.absolutePath,
                encryptionMeta = null,
                syncState = "LOCAL_ONLY",
            ),
        )
        db.transcriptDao().insertRevision(
            TranscriptRevisionEntity("rev-$id", id, rev = 0, locus = "ON_DEVICE", languageTag = "en", createdAt = 1_000L),
        )
        db.transcriptDao().insertSegments(
            listOf(TranscriptSegmentEntity("seg-$id", "rev-$id", 0, "hello", 0L, 1_000L, null)),
        )
        db.aiArtifactDao().upsert(
            AiArtifactEntity("art-$id", id, kind = "TITLE", model = "test", content = "A title", createdAt = 1_000L),
        )
        db.embeddingDao().upsert(
            EmbeddingEntity(recordingId = id, model = "test", dim = 2, vector = byteArrayOf(0, 0, 0, 0), createdAt = 1_000L),
        )
    }
}
