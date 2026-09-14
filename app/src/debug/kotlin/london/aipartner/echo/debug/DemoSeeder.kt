package london.aipartner.echo.debug

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.sync.withLock
import london.aipartner.echo.core.capture.AudioEncryptor
import london.aipartner.echo.core.data.AiArtifactEntity
import london.aipartner.echo.core.data.RecordingDao
import london.aipartner.echo.core.data.RecordingEntity
import london.aipartner.echo.core.data.TranscriptDao
import london.aipartner.echo.core.data.TranscriptRevisionEntity
import london.aipartner.echo.core.data.TranscriptSegmentEntity
import london.aipartner.echo.core.data.AiArtifactDao
import london.aipartner.echo.core.transcribe.Locus
import london.aipartner.echo.core.transcribe.SemanticIndex
import london.aipartner.echo.core.transcribe.Transcript
import london.aipartner.echo.core.transcribe.TranscriptSegment

/**
 * DEBUG-ONLY demo seeder. Lives in `src/debug` — it is in NO release build, same
 * discipline as [DebugRecordActivity] / the DEBUG trigger launcher. It exists for ONE
 * reason: until transcription-on-record-stop is wired (Step 4), real recordings have
 * empty transcripts, so the karaoke highlight can't be *felt* on a real capture. This
 * seeds **one** recording with **real audible audio + matching segment timings** so the
 * highlight / auto-scroll / tap-to-seek can be exercised on the A03.
 *
 * It is honest about what it is: the audio is a synthesised ascending-tone track (each
 * segment a distinct pitch, so you can *hear* the playhead move and confirm tap-to-seek),
 * and the transcript is fixed demo prose — it does NOT claim to be a transcription of the
 * tones. Nothing here touches the capture/consent path; it only writes rows + an encrypted
 * file through the same `AudioEncryptor` every real recording uses.
 */
object DemoSeeder {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DemoEntryPoint {
        fun recordingDao(): RecordingDao
        fun transcriptDao(): TranscriptDao
        fun aiArtifactDao(): AiArtifactDao
        fun audioEncryptor(): AudioEncryptor
        fun semanticIndex(): SemanticIndex
    }

    private const val SAMPLE_RATE = 16_000
    private const val SEGMENT_MS = 1_000L

    // Serialises concurrent seeds (the adb recipe can spawn two activity instances in
    // one process) so the clear-then-insert is atomic and exactly one demo survives.
    private val seedMutex = kotlinx.coroutines.sync.Mutex()

    // ── Demo library for STORE SCREENSHOTS. Consumer-realistic voice memos with a MIX of title
    //    states so the library reads honestly: manual renames (userTitle on the row), on-device
    //    smart titles (TITLE artifact), and one left to the date+time default. Audio is a
    //    synthesised tone track (invisible in a static screenshot); the transcript TEXT is the
    //    on-screen content. Fresh content — deliberately NOT the prior soak/real recordings.
    private data class Demo(
        val slug: String,
        val userTitle: String?,   // a manual rename lives on the recording row (wins, survives)
        val smartTitle: String?,  // on-device smart title (TITLE artifact); only when no userTitle
        val ageMs: Long,          // createdAt = now - ageMs (varied dates down the list)
        val lines: List<String>,
        val summary: String? = null,
        val actions: String? = null,
    )

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    private val DEMOS = listOf(
        Demo(
            slug = "standup",
            userTitle = "Weekly team sync",
            smartTitle = null,
            ageMs = 2 * HOUR,
            lines = listOf(
                "Morning everyone — thanks for hopping on.",
                "Quick round of updates, then we'll talk timelines.",
                "Design finished the new onboarding screens yesterday.",
                "They tested really well — the drop-off is way down.",
                "Backend's almost done with the export endpoint.",
                "We should have it on staging by Thursday.",
                "One blocker: we're still waiting on the API keys.",
                "I'll chase that this afternoon so we're not stuck.",
                "Marketing wants the launch assets by next Friday.",
                "Let's aim to freeze scope at the end of the week.",
                "Anything else before we wrap? No? Great.",
                "Thanks all — I'll send the notes right after this.",
            ),
            // NO summary/actions: v1 does NOT generate them (the AiEngine summary/action path is
            // cloud-only, Pro-gated, off by default, and the shipping build has no INTERNET). The
            // detail screen renders those sections ONLY when the artifact exists, so v1 shows just
            // the transcript + player. Seeding them would produce dishonest store screenshots.
            // (When the Pro/cloud summaries fast-follow ships, re-add summary/actions here.)
        ),
        Demo(
            slug = "song",
            userTitle = null,
            smartTitle = "Song idea for the bridge",
            ageMs = 20 * HOUR,
            lines = listOf(
                "Okay, quick idea before I forget it.",
                "The bridge should drop to just piano and voice.",
                "Then the drums come back in on the last line.",
                "Something like — hold on, let me hum it.",
            ),
        ),
        Demo(
            slug = "interview",
            userTitle = "Interview — Amara (product research)",
            smartTitle = null,
            ageMs = 2 * DAY,
            lines = listOf(
                "So tell me how you take notes day to day.",
                "Honestly, I just record voice memos on my phone.",
                "The problem is I never listen back to any of them.",
                "There are two hundred in there, all untitled.",
                "If I could search what I said, that'd change everything.",
                "And I'd want it private — not uploaded to some server.",
            ),
        ),
        Demo(
            slug = "errands",
            userTitle = null,
            smartTitle = "Pick up dry cleaning and call the plumber",
            ageMs = 3 * DAY,
            lines = listOf(
                "A few things to do before the weekend.",
                "Pick up the dry cleaning — it's been there a week.",
                "Call the plumber about the dripping kitchen tap.",
                "And book the car in for its service.",
            ),
        ),
        Demo(
            slug = "lecture",
            userTitle = null,
            smartTitle = null, // no title at all → the card renders the date+time default
            ageMs = 6 * DAY,
            lines = listOf(
                "Today we're looking at how memory actually works.",
                "There's short-term memory and long-term memory.",
                "The hippocampus is central to forming new memories.",
                "Repetition and sleep both strengthen what we keep.",
                "We'll come back to spaced repetition next week.",
            ),
        ),
    )

    /** Seeds the demo library (idempotent). Returns the number of recordings seeded. */
    suspend fun seed(context: Context): Int = seedMutex.withLock {
        val ep = EntryPointAccessors.fromApplication(
            context.applicationContext, DemoEntryPoint::class.java,
        )

        // Idempotent: clear any prior demo recordings first so re-seeding converges to exactly the
        // DEMOS set, never a pile-up. FK CASCADE drops each demo's transcript/segments/artifacts.
        ep.recordingDao().getAll()
            .filter { it.id.startsWith("demo-") }
            .forEach { old ->
                old.localAudioRef?.let { runCatching { File(it).delete() } }
                ep.recordingDao().deleteById(old.id)
            }

        val encryptor = ep.audioEncryptor()
        val now = System.currentTimeMillis()
        DEMOS.forEach { demo ->
            val id = "demo-${demo.slug}-" + UUID.randomUUID().toString()
            val createdAt = now - demo.ageMs
            val durationMs = demo.lines.size * SEGMENT_MS

            // Real (tone) audio through the same AudioEncryptor a real capture uses, so playback
            // controls render and the file is encrypted at rest like everything else.
            val temp = File.createTempFile("demo_", ".wav", context.cacheDir)
            writeToneWav(temp, durationMs)
            val encrypted = encryptor.encryptFrom(temp, "$id.wav")
            runCatching { temp.delete() }

            ep.recordingDao().insert(
                RecordingEntity(
                    id = id,
                    createdAt = createdAt,
                    durationMs = durationMs,
                    captureSource = "MIC",
                    fidelity = "HD",
                    captureMode = "ON_DEMAND",
                    contactHash = null,
                    localAudioRef = encrypted.absolutePath,
                    encryptionMeta = "demo",
                    syncState = "LOCAL",
                    transcriptionStatus =
                        london.aipartner.echo.core.data.TranscriptionStatus.DONE.name,
                    userTitle = demo.userTitle,
                ),
            )

            val revisionId = "$id-rev0"
            ep.transcriptDao().insertRevision(
                TranscriptRevisionEntity(
                    id = revisionId,
                    recordingId = id,
                    rev = 0,
                    locus = "ON_DEVICE",
                    languageTag = "en",
                    createdAt = createdAt,
                ),
            )
            val segments = demo.lines.mapIndexed { i, text ->
                TranscriptSegmentEntity(
                    id = "$revisionId-seg$i",
                    revisionId = revisionId,
                    orderIdx = i,
                    text = text,
                    tStartMs = i * SEGMENT_MS,
                    tEndMs = (i + 1) * SEGMENT_MS,
                    speaker = null,
                )
            }
            ep.transcriptDao().insertSegments(segments)

            val ai = ep.aiArtifactDao()
            // Smart title uses the real (recordingId:TITLE) id + on-device provenance so it renders
            // exactly like a production smart title (and is overridden by userTitle when both set).
            demo.smartTitle?.let {
                ai.upsert(AiArtifactEntity("$id:TITLE", id, "TITLE", "on-device-heuristic", it, createdAt))
            }
            demo.summary?.let {
                ai.upsert(AiArtifactEntity("$id:SUMMARY", id, "SUMMARY", "demo", it, createdAt))
            }
            demo.actions?.let {
                ai.upsert(AiArtifactEntity("$id:ACTIONS", id, "ACTIONS", "demo", it, createdAt))
            }

            // Best-effort on-device semantic index (offline) — never fail the seed on embedder issues.
            runCatching {
                ep.semanticIndex().index(
                    id,
                    Transcript(
                        locus = Locus.ON_DEVICE,
                        languageTag = "en",
                        segments = segments.map { TranscriptSegment(it.text, it.tStartMs, it.tEndMs, it.speaker) },
                    ),
                )
            }
        }
        DEMOS.size
    }

    /** Writes a mono 16-bit PCM WAV: pitch steps up once per [SEGMENT_MS] so the
     *  playhead is audible and tap-to-seek is unmistakable. */
    private fun writeToneWav(out: File, durationMs: Long) {
        val totalSamples = (SAMPLE_RATE * durationMs / 1000).toInt()
        val samplesPerSegment = (SAMPLE_RATE * SEGMENT_MS / 1000).toInt()
        val pcm = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (n in 0 until totalSamples) {
            val seg = n / samplesPerSegment
            // Tiny phone speakers roll off hard below ~400Hz, so start mid-range and
            // step up — every segment is clearly audible on the device speaker.
            val freq = 600.0 + seg * 70.0
            val amp = 0.5
            val sample = (sin(2.0 * PI * freq * n / SAMPLE_RATE) * amp * Short.MAX_VALUE).toInt().toShort()
            pcm.putShort(sample)
        }
        val data = pcm.array()
        out.outputStream().use { os ->
            os.write(wavHeader(data.size))
            os.write(data)
        }
    }

    private fun wavHeader(dataLen: Int): ByteArray {
        val channels = 1
        val bits = 16
        val byteRate = SAMPLE_RATE * channels * bits / 8
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + dataLen)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)               // PCM fmt chunk size
        buf.putShort(1)              // PCM
        buf.putShort(channels.toShort())
        buf.putInt(SAMPLE_RATE)
        buf.putInt(byteRate)
        buf.putShort((channels * bits / 8).toShort()) // block align
        buf.putShort(bits.toShort())
        buf.put("data".toByteArray())
        buf.putInt(dataLen)
        return buf.array()
    }
}
