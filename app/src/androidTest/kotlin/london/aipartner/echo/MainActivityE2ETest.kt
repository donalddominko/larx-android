package london.aipartner.echo

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import london.aipartner.echo.core.capture.AudioEncryptor
import london.aipartner.echo.core.consent.ConsentPreferences
import london.aipartner.echo.core.data.AiArtifactDao
import london.aipartner.echo.core.data.AiArtifactEntity
import london.aipartner.echo.core.data.RecordingDao
import london.aipartner.echo.core.data.RecordingEntity
import london.aipartner.echo.core.data.TranscriptDao
import london.aipartner.echo.core.data.TranscriptRevisionEntity
import london.aipartner.echo.core.data.TranscriptSegmentEntity
import london.aipartner.echo.core.data.TranscriptionStatus
import london.aipartner.echo.core.transcribe.Locus
import london.aipartner.echo.core.transcribe.SemanticIndex
import london.aipartner.echo.core.transcribe.Transcript
import london.aipartner.echo.core.transcribe.TranscriptSegment
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * Phase 6 gate — **★ End-to-end `MainActivity` test (BINDING — carried from Phase 4).**
 *
 * A single instrumented test launches the REAL [MainActivity] / nav root and drives the
 * core Phase-6 flow against real surfaces — no stateless-composable stand-in:
 *
 *   library visible (seeded card shows) → semantic search returns it (on-device, offline,
 *   zero egress) → open it → the karaoke player renders the transcript → the transport
 *   control works (Play → Pause toggles on the real decrypt→`MediaPlayer` path) → delete
 *   from the player removes it (leaves the library).
 *
 * The data is seeded through the SAME singleton DAOs / [AudioEncryptor] / [SemanticIndex]
 * the app uses (via a Hilt [EntryPoint]) BEFORE the activity launches, so the activity's
 * own ViewModels read it. The disclosure is acknowledged first (acknowledge-only; never
 * gates recording). The per-screen Compose tests ([KaraokeSyncTest], [DeleteEverywhereTest],
 * [AboutReachableTest]) remain the focused backstops; this is the full-launch spine.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MainActivityE2ETest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val recId = "e2e-mainactivity"
    private val title = "Quarterly planning e2e recording"
    private val lines = listOf(
        "Welcome to the quarterly planning session everyone.",
        "First we review the roadmap and the budget numbers.",
        "Then we assign owners for each of the workstreams.",
        "Finally we agree the deadlines before we wrap up.",
    )

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface E2eEntryPoint {
        fun recordingDao(): RecordingDao
        fun transcriptDao(): TranscriptDao
        fun aiArtifactDao(): AiArtifactDao
        fun audioEncryptor(): AudioEncryptor
        fun semanticIndex(): SemanticIndex
    }

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // Runs before the activity launches (order 2): acknowledge the disclosure so the app
    // starts on the library, and seed one recording (real encrypted audio + transcript +
    // title + on-device embedding) through the real singleton graph.
    @get:Rule(order = 1)
    val seed = object : ExternalResource() {
        override fun before() {
            ConsentPreferences(context).disclosureAcknowledged = true
            val ep = EntryPointAccessors.fromApplication(context, E2eEntryPoint::class.java)
            runBlocking {
                // Idempotent: clear any prior run of this id (FK CASCADE drops children).
                ep.recordingDao().getById(recId)?.let { old ->
                    old.localAudioRef?.let { runCatching { File(it).delete() } }
                    ep.recordingDao().deleteById(recId)
                }

                val durationMs = lines.size * 1_000L
                val temp = File.createTempFile("e2e_", ".wav", context.cacheDir)
                writeToneWav(temp, durationMs)
                val encrypted = ep.audioEncryptor().encryptFrom(temp, "$recId.wav")
                temp.delete()

                val now = System.currentTimeMillis()
                ep.recordingDao().insert(
                    RecordingEntity(
                        id = recId,
                        createdAt = now,
                        durationMs = durationMs,
                        captureSource = "MIC",
                        fidelity = "HD",
                        captureMode = "ON_DEMAND",
                        contactHash = null,
                        localAudioRef = encrypted.absolutePath,
                        encryptionMeta = "e2e",
                        syncState = "LOCAL",
                        transcriptionStatus = TranscriptionStatus.DONE.name,
                    ),
                )
                val revId = "$recId-rev0"
                ep.transcriptDao().insertRevision(
                    TranscriptRevisionEntity(revId, recId, 0, "ON_DEVICE", "en", now),
                )
                val segments = lines.mapIndexed { i, text ->
                    TranscriptSegmentEntity(
                        "$revId-seg$i", revId, i, text, i * 1_000L, (i + 1) * 1_000L, null,
                    )
                }
                ep.transcriptDao().insertSegments(segments)
                ep.aiArtifactDao().upsert(
                    AiArtifactEntity("$recId-title", recId, "TITLE", "e2e", title, now),
                )
                ep.semanticIndex().index(
                    recId,
                    Transcript(
                        locus = Locus.ON_DEVICE,
                        languageTag = "en",
                        segments = segments.map {
                            TranscriptSegment(it.text, it.tStartMs, it.tEndMs, it.speaker)
                        },
                    ),
                )
            }
        }
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        val ep = EntryPointAccessors.fromApplication(context, E2eEntryPoint::class.java)
        runBlocking {
            ep.recordingDao().getById(recId)?.let { old ->
                old.localAudioRef?.let { runCatching { File(it).delete() } }
                ep.recordingDao().deleteById(recId)
            }
        }
    }

    @Test
    fun endToEnd_library_search_player_controls_delete() {
        // 1. Library visible: the seeded card shows (loaded off RecordingDao, newest-first).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(title).assertIsDisplayed()

        // 2. Semantic search (on-device, offline, zero egress): a query drawn from the
        //    transcript returns the recording; the card stays visible under the filter.
        composeRule.onNodeWithContentDescription("Search recordings").performTextInput("roadmap and budget")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(title).assertIsDisplayed()

        // 3. Open it → the karaoke player renders the real transcript.
        composeRule.onNodeWithText(title).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription(lines[0]).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Now playing: ${lines[0]}")
                    .fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Core control works: Play (real decrypt → MediaPlayer) toggles to Pause.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Play").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()

        // 5. Delete from the player → confirm → the recording leaves the library.
        composeRule.onNodeWithContentDescription("Delete recording").performClick()
        composeRule.onNodeWithText("Delete recording?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()
        }
    }

    /** Mono 16-bit PCM WAV, one audible pitch per second — a real file the encrypt →
     *  decrypt → `MediaPlayer` path can actually play, so the transport toggle is real. */
    private fun writeToneWav(out: File, durationMs: Long) {
        val sampleRate = 16_000
        val totalSamples = (sampleRate * durationMs / 1000).toInt()
        val samplesPerSegment = sampleRate // 1s
        val pcm = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (n in 0 until totalSamples) {
            val freq = 600.0 + (n / samplesPerSegment) * 70.0
            val sample = (sin(2.0 * PI * freq * n / sampleRate) * 0.5 * Short.MAX_VALUE).toInt().toShort()
            pcm.putShort(sample)
        }
        val data = pcm.array()
        out.outputStream().use { os ->
            os.write(wavHeader(data.size, sampleRate))
            os.write(data)
        }
    }

    private fun wavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * 2
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataSize)
        return header.array()
    }
}
