package london.aipartner.echo.core.transcribe

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.test.runTest
import london.aipartner.echo.core.data.EchoDatabase
import london.aipartner.echo.core.data.RecordingEntity
import london.aipartner.echo.core.data.TranscriptRevisionEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 5 gate (on device, real **encrypted** DB): the AI-artifact lifecycle.
 * generate → persist → regenerate (replace, not duplicate) → delete artifact
 * leaves the recording + transcript intact; deleting the recording CASCADE-drops
 * the artifact. Proves the regenerable-derivation / never-source-of-truth contract
 * holds against SQLCipher + Room FKs, not just in unit logic.
 */
@RunWith(AndroidJUnit4::class)
class AiArtifactLifecycleTest {

    private lateinit var db: EchoDatabase
    private lateinit var writer: AiArtifactWriter
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        context.deleteDatabase(EchoDatabase.DB_NAME)
        db = EchoDatabase.build(context, "lifecycle-test-passphrase".toByteArray())
        writer = AiArtifactWriter(db.aiArtifactDao(), clock = { 1L })
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(EchoDatabase.DB_NAME)
    }

    private suspend fun seedRecordingWithTranscript(id: String) {
        db.recordingDao().insert(
            RecordingEntity(
                id = id, createdAt = 1, durationMs = 1000, captureSource = "MIC",
                fidelity = "HD", captureMode = "ON_DEMAND", contactHash = null,
                localAudioRef = "/enc/$id.ogg", encryptionMeta = "m", syncState = "LOCAL",
            ),
        )
        db.transcriptDao().insertRevision(
            TranscriptRevisionEntity(
                id = "$id-trv0", recordingId = id, rev = 0, locus = "ON_DEVICE",
                languageTag = "en", createdAt = 1,
            ),
        )
    }

    @Test fun generate_persist_regenerate_delete_neverTouchesSourceOfTruth() = runTest {
        seedRecordingWithTranscript("rec1")

        // generate → persist
        writer.persist("rec1", AiArtifactResult.Generated(AiKind.SUMMARY, "v1 summary", "model-x"))
        assertEquals(1, db.aiArtifactDao().forRecording("rec1").size)

        // regenerate → REPLACE (not duplicate)
        writer.persist("rec1", AiArtifactResult.Generated(AiKind.SUMMARY, "v2 summary", "model-y"))
        val artifacts = db.aiArtifactDao().forRecording("rec1")
        assertEquals("regenerate must replace, not duplicate", 1, artifacts.size)
        assertEquals("v2 summary", artifacts.first().content)

        // delete artifact → recording + transcript intact
        deleteArtifact("rec1")
        assertTrue(db.aiArtifactDao().forRecording("rec1").isEmpty())
        assertNotNull("recording survives artifact deletion", db.recordingDao().getById("rec1"))
        assertEquals("transcript survives artifact deletion",
            1, db.transcriptDao().revisionsFor("rec1").size)

        // delete recording → artifact cascade-drops
        writer.persist("rec1", AiArtifactResult.Generated(AiKind.TITLE, "A Title", "model-z"))
        assertEquals(1, db.aiArtifactDao().forRecording("rec1").size)
        db.recordingDao().deleteById("rec1")
        assertNull(db.recordingDao().getById("rec1"))
        assertTrue("artifacts cascade-drop with the recording",
            db.aiArtifactDao().forRecording("rec1").isEmpty())
    }

    // AiArtifactDao has no delete-by-id; remove via a fresh upsert-free path using SQL.
    private suspend fun deleteArtifact(recordingId: String) {
        // The writer uses a stable id "<recordingId>:<kind>"; clearing the recording's
        // artifacts here exercises "delete artifact leaves source-of-truth intact".
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM ai_artifacts WHERE recordingId = ?", arrayOf(recordingId),
        )
    }
}
