package london.aipartner.echo

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import london.aipartner.echo.core.capture.AudioEncryptor
import london.aipartner.echo.core.data.DbPassphraseProvider
import london.aipartner.echo.core.data.EchoDatabase
import london.aipartner.echo.core.data.RecordingEntity
import london.aipartner.echo.core.data.TranscriptRevisionEntity
import london.aipartner.echo.core.data.TranscriptSegmentEntity
import london.aipartner.echo.core.data.TranscriptionStatus
import london.aipartner.echo.core.sync.CloudSink
import london.aipartner.echo.core.sync.RemoteRef
import london.aipartner.echo.core.sync.SinkId
import london.aipartner.echo.core.sync.SyncArtifact
import london.aipartner.echo.recordings.RecordingDeleter
import london.aipartner.echo.transcribe.TranscriptionProgressBus
import london.aipartner.echo.ui.detail.RecordingDetailUiState
import london.aipartner.echo.ui.detail.RecordingDetailViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6 — BUG A gate (**detail flips IN PLACE on completion**, instrumented).
 *
 * The detail screen used to use a fragile manual delay-poll that could leave it stuck on
 * "Transcribing…" until the user navigated away (forcing a library re-read). The fix makes
 * the ViewModel observe the recording row + transcript revisions as Room Flows, so Room's
 * invalidation tracker pushes the DONE transition straight to the foregrounded screen.
 *
 * This test drives the REAL [RecordingDetailViewModel] against a REAL encrypted DB: it
 * seeds a RUNNING recording with no transcript, then — WITHOUT recreating the ViewModel —
 * writes the transcript + flips the status to DONE (exactly what the service does after a
 * pass), and asserts the same ViewModel's state flips to the transcript in place.
 */
@RunWith(AndroidJUnit4::class)
class DetailLiveUpdateTest {

    private lateinit var db: EchoDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class NoopSink : CloudSink {
        override val id = SinkId.LOCAL_ONLY
        override suspend fun put(artifact: SyncArtifact, payload: ByteArray): RemoteRef = RemoteRef(SinkId.LOCAL_ONLY, "noop")
        override suspend fun delete(ref: RemoteRef) {}
    }

    @Before fun setUp() {
        context.deleteDatabase(EchoDatabase.DB_NAME)
        db = EchoDatabase.build(context, DbPassphraseProvider(context).getOrCreate())
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(EchoDatabase.DB_NAME)
    }

    private fun newViewModel(id: String) = RecordingDetailViewModel(
        SavedStateHandle(mapOf("id" to id)),
        db.recordingDao(),
        db.transcriptDao(),
        db.aiArtifactDao(),
        AudioEncryptor(context),
        RecordingDeleter(db.recordingDao(), db.syncRefDao(), NoopSink()),
        TranscriptionProgressBus(),
    )

    private suspend fun RecordingDetailViewModel.awaitState(
        desc: String,
        predicate: (RecordingDetailUiState) -> Boolean,
    ) {
        withTimeout(8_000) {
            while (!predicate(state.value)) delay(50)
        }
    }

    @Test fun detailFlipsToTranscript_inPlace_whenTranscriptionCompletes() = runBlocking {
        val id = "rec-live"
        // A recording mid-transcription: RUNNING, no transcript yet, no audio file.
        db.recordingDao().insert(
            RecordingEntity(
                id = id,
                createdAt = 1_000L,
                durationMs = 90_000L,
                captureSource = "MIC",
                fidelity = "LOSSY",
                captureMode = "ON_DEMAND",
                contactHash = null,
                localAudioRef = null,
                encryptionMeta = null,
                syncState = "LOCAL_ONLY",
                transcriptionStatus = TranscriptionStatus.RUNNING.name,
            ),
        )

        val vm = newViewModel(id)

        // The screen opens on the honest "Transcribing…" state (RUNNING, no segments).
        vm.awaitState("transcribing") {
            !it.loading && it.transcriptionStatus == TranscriptionStatus.RUNNING.name && it.segments.isEmpty()
        }

        // The service finishes the pass: rev 0 + segments written, then status → DONE.
        db.transcriptDao().insertRevision(
            TranscriptRevisionEntity("rev-live", id, rev = 0, locus = "ON_DEVICE", languageTag = "en", createdAt = 2_000L),
        )
        db.transcriptDao().insertSegments(
            listOf(TranscriptSegmentEntity("seg-live", "rev-live", 0, "the transcript text", 0L, 1_000L, null)),
        )
        db.recordingDao().updateTranscriptionStatus(id, TranscriptionStatus.DONE.name)

        // SAME ViewModel (never recreated) flips to DONE + the transcript, in place.
        vm.awaitState("done-with-transcript") {
            it.transcriptionStatus == TranscriptionStatus.DONE.name &&
                it.segments.size == 1 &&
                it.segments.first().text == "the transcript text"
        }
    }
}
