package london.aipartner.echo.core.capture

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import london.aipartner.echo.core.consent.CaptureMode
import london.aipartner.echo.core.consent.ConsentPreferences
import london.aipartner.echo.core.consent.RecordAudioConsentGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2 AUTOMATED gate (single-device, on the A03). Records for real via
 * MediaRecorder, then verifies encryption-at-rest, a decryptable+readable media
 * file, the verified AAC fallback, the waveform stream, the resolver, the
 * no-bypass DENY, and process-death recovery.
 */
@RunWith(AndroidJUnit4::class)
class RecorderControllerTest {

    @get:Rule
    val permission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var encryptor: AudioEncryptor
    private lateinit var recoveryStore: RecordingRecoveryStore
    private lateinit var dao: FakeRecordingDao
    private lateinit var consentDao: FakeConsentRecordDao
    private lateinit var resolver: CaptureSourceResolver

    private fun newController() = RecorderController(
        context, resolver, RecordAudioConsentGate(), encryptor, dao, consentDao, recoveryStore,
    )

    @Before
    fun setUp() {
        encryptor = AudioEncryptor(context)
        recoveryStore = RecordingRecoveryStore(context).also { it.clear() }
        dao = FakeRecordingDao()
        consentDao = FakeConsentRecordDao()
        resolver = RealCaptureSourceResolver(MicRecorder(context))
        encryptor.recordingsDir.listFiles()?.forEach { it.delete() }
        File(context.filesDir, "pending").deleteRecursively()
    }

    @After
    fun tearDown() {
        encryptor.recordingsDir.listFiles()?.forEach { it.delete() }
        File(context.filesDir, "pending").deleteRecursively()
        recoveryStore.clear()
    }

    @Test
    fun memoRecord_producesEncryptedReadableEntity() = runBlocking {
        val controller = newController()
        val start = controller.start(CaptureJob.MEMO, CaptureMode.ON_DEMAND, hasRecordAudioPermission = true)
        assertTrue(start is StartResult.Started)
        delay(1500)
        val entity = controller.stop()!!

        assertEquals("MIC", entity.captureSource)
        val encrypted = File(entity.localAudioRef!!)
        assertTrue("encrypted file exists", encrypted.exists() && encrypted.length() > 0)
        // The on-disk file is encrypted; decrypt to verify it's a real media file.
        val decrypted = encryptor.decryptToTemp(encrypted)
        assertAudioReadable(decrypted)
        assertNotNull(dao.getById(entity.id))
    }

    @Test
    fun aacFallback_isVerifiedPlayableOutcome() = runBlocking {
        val controller = newController()
        controller.start(
            CaptureJob.MEMO, CaptureMode.ON_DEMAND,
            hasRecordAudioPermission = true, preferredCodec = Codec.AAC,
        )
        delay(1500)
        val entity = controller.stop()!!

        assertTrue("codec recorded as AAC", entity.encryptionMeta!!.contains("codec=AAC"))
        val decrypted = encryptor.decryptToTemp(File(entity.localAudioRef!!))
        val mime = audioMime(decrypted)
        assertTrue("AAC mime, got $mime", mime != null && mime.contains("mp4a"))
    }

    @Test
    fun amplitudeStream_emitsWhileRecording() = runBlocking {
        val controller = newController()
        controller.start(CaptureJob.MEMO, CaptureMode.ON_DEMAND, hasRecordAudioPermission = true)
        // Structural check: the waveform stream emits (magnitude depends on ambient
        // sound, so we assert emission + non-blocking, not a level threshold).
        val samples = withTimeoutOrNull(2000) { controller.amplitude().take(4).toList() }
        controller.stop()
        assertNotNull("amplitude flow should emit", samples)
        assertTrue("expected >=4 samples", (samples?.size ?: 0) >= 4)
    }

    @Test
    fun doubleStop_secondIsNoOp() = runBlocking {
        // A repeat ACTION_STOP (double-tap / notification + UI, or a redelivery while the
        // gain re-encode runs) must NOT re-encode and re-insert the same recording id
        // (that crashed with a UNIQUE constraint). The second stop is a clean no-op.
        val controller = newController()
        controller.start(CaptureJob.MEMO, CaptureMode.ON_DEMAND, hasRecordAudioPermission = true)
        delay(800)
        val first = controller.stop()
        assertNotNull("first stop persists the recording", first)
        val second = controller.stop()
        assertNull("a second stop is a no-op, not a duplicate insert", second)
        assertEquals(RecorderState.Idle, controller.state.value)
        assertEquals("exactly one recording persisted", 1, dao.inserted.size)
    }

    @Test
    fun inProgressCapture_survivesCacheDirEviction() = runBlocking {
        // Regression for the A03 Phase 6 Step 3 device pass: under storage pressure the
        // OS purged the in-progress recording's temp out of cacheDir mid-capture, so the
        // audio was silently lost. The active temp must live in filesDir (not reclaimable
        // by the OS), so a cacheDir wipe during recording must NOT affect the capture.
        val controller = newController()
        controller.start(CaptureJob.MEMO, CaptureMode.ON_DEMAND, hasRecordAudioPermission = true)
        delay(1200)

        val pending = File(context.filesDir, "pending")
        assertTrue(
            "in-progress temp must be under filesDir/.pending, never cacheDir",
            pending.listFiles()?.isNotEmpty() ?: false,
        )

        // Simulate the OS low-storage cache purge: wipe cacheDir out from under capture.
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }

        val entity = controller.stop()
        assertNotNull("recording survived the cacheDir eviction and persisted", entity)
        val encrypted = File(entity!!.localAudioRef!!)
        assertTrue("persisted encrypted audio is non-empty", encrypted.exists() && encrypted.length() > 0)
        assertAudioReadable(encryptor.decryptToTemp(encrypted))
        // .pending self-empties after a successful stop (encryptFrom consumes the temp).
        assertTrue(".pending empties after stop", pending.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun resolver_picksMicForMemo() {
        // v1 ships one job; call recording was dropped (see capture-strategy.md).
        assertEquals(CaptureSourceId.MIC, resolver.resolve(CaptureJob.MEMO).id)
    }

    @Test
    fun deny_producesNoFileAndNoEntity() = runBlocking {
        val controller = newController()
        val before = encryptor.recordingsDir.listFiles()?.size ?: 0
        val result = controller.start(
            CaptureJob.MEMO, CaptureMode.ON_DEMAND, hasRecordAudioPermission = false,
        )
        assertTrue(result is StartResult.Denied)
        assertEquals(RecorderState.Idle, controller.state.value)
        assertEquals(before, encryptor.recordingsDir.listFiles()?.size ?: 0)
        assertTrue(dao.inserted.isEmpty())
    }

    @Test
    fun disclosureFlag_doesNotGateConsent() = runBlocking {
        // Phase 3 invariant: acknowledging the disclosure must NOT unlock recording.
        // With the disclosure marked seen but RECORD_AUDIO absent, the gate still
        // DENYs and no file/entity/ConsentRecord is produced.
        ConsentPreferences(context).disclosureAcknowledged = true
        val controller = newController()
        val before = encryptor.recordingsDir.listFiles()?.size ?: 0

        val result = controller.start(
            CaptureJob.MEMO, CaptureMode.ON_DEMAND, hasRecordAudioPermission = false,
        )

        assertTrue("disclosure-seen must not unlock the gate", result is StartResult.Denied)
        assertEquals(before, encryptor.recordingsDir.listFiles()?.size ?: 0)
        assertTrue(dao.inserted.isEmpty())
        assertTrue(consentDao.inserted.isEmpty())
    }

    @Test
    fun everyRecording_writesAConsentRecord() = runBlocking {
        val controller = newController()
        controller.start(CaptureJob.MEMO, CaptureMode.ON_DEMAND, hasRecordAudioPermission = true)
        delay(1200)
        val entity = controller.stop()!!

        val record = consentDao.forRecording(entity.id)
        assertNotNull("recording must have a ConsentRecord", record)
        assertEquals("ALLOW", record!!.verdict)
        assertFalse(record.noticeEmitted)
    }

    @Test
    fun pauseResume_onNewEngine_excludesPausedTime_andProducesContinuousAudio() = runBlocking {
        // Parity check for the AudioRecord/MediaCodec engine rewrite: pause/resume keep
        // the mic open but stop feeding the encoder, so paused wall-clock time must be
        // EXCLUDED from the reported duration, and the resumed audio must still finalize
        // into a single decodable container.
        val out = File(pendingDir(), "pauseresume.m4a")
        val handle = AudioRecordCaptureHandle(
            outputFile = out,
            sampleRateHz = 44_100,
            audioSource = android.media.MediaRecorder.AudioSource.MIC,
            gainProvider = { 1f },
        )
        handle.begin()
        delay(600)        // ~600ms recorded
        handle.pause()
        delay(1500)       // paused — this 1.5s must NOT count toward duration
        handle.resume()
        delay(600)        // ~600ms more recorded
        val result = handle.stop()

        // Encoded duration ≈ 1200ms (the two un-paused spans), NOT ~2700ms wall-clock.
        // Generous bounds absorb encoder latency / scheduling; the point is the 1.5s pause
        // is excluded (it would push a non-excluding engine well above 2000ms).
        assertTrue(
            "duration ${result.durationMs}ms should exclude the 1.5s pause (expected ~1200ms)",
            result.durationMs in 700..1900,
        )
        assertAudioReadable(out)
        out.delete()
        Unit
    }

    @Test
    fun processDeath_validPartial_isRecovered() = runBlocking {
        // Positive recovery path on the NEW engine: a properly finalized capture whose
        // process died AFTER the muxer flushed (a real, decodable .m4a partial) is
        // recovered + surfaced, not lost.
        val temp = File(pendingDir(), "orphan_valid.m4a")
        AudioRecordCaptureHandle(
            outputFile = temp,
            sampleRateHz = 44_100,
            audioSource = android.media.MediaRecorder.AudioSource.MIC,
            gainProvider = { 1f },
        ).also { it.begin() }.let { delay(800); it.stop() }
        assertTrue("precondition: a valid finalized partial", temp.exists() && temp.length() > 0)

        recoveryStore.markStarted(
            RecordingRecoveryStore.Orphan(
                recordingId = "orphan-valid", tempPath = temp.absolutePath,
                startedAt = 1L, sourceId = CaptureSourceId.MIC,
                codec = Codec.AAC, captureMode = "ON_DEMAND",
            ),
        )
        val recovered = newController().recoverOrphan()
        assertNotNull("a decodable partial should be recovered", recovered)
        assertEquals("RECOVERED", recovered!!.syncState)
        assertTrue(File(recovered.localAudioRef!!).exists())
        assertAudioReadable(encryptor.decryptToTemp(File(recovered.localAudioRef!!)))
        assertNotNull(dao.getById("orphan-valid"))
        assertNull("recovery marker cleared", recoveryStore.read())
    }

    @Test
    fun processDeath_killedMuxerPartial_recoverySurfacesHonestly_validOrDiscarded() = runBlocking {
        // The REAL killed-MediaMuxer case (the parity gap), tested against an actual
        // unfinalized partial — not a synthetic temp. A process kill never reaches
        // MediaMuxer.stop(); [abandonForTest] reproduces that exact on-disk state (mdat
        // frames, muxer released without stop()). What that leaves is OEM/OS-dependent
        // (on some devices the partial is still decodable, on others moov-less and not),
        // so the binding invariant is **valid-or-discard**: recovery either surfaces a
        // RECOVERED card whose audio is genuinely decodable, OR discards it and persists
        // nothing — it NEVER surfaces a phantom card with unplayable audio.
        val temp = File(pendingDir(), "orphan_killed.m4a")
        val handle = AudioRecordCaptureHandle(
            outputFile = temp,
            sampleRateHz = 44_100,
            audioSource = android.media.MediaRecorder.AudioSource.MIC,
            gainProvider = { 1f },
        )
        handle.begin()
        delay(1200) // let the muxer start + write real sample data
        handle.abandonForTest() // kill WITHOUT finalizing the muxer
        assertTrue("killed partial has captured bytes", temp.exists() && temp.length() > 0)

        recoveryStore.markStarted(
            RecordingRecoveryStore.Orphan(
                recordingId = "orphan-killed", tempPath = temp.absolutePath,
                startedAt = 1L, sourceId = CaptureSourceId.MIC,
                codec = Codec.AAC, captureMode = "ON_DEMAND",
            ),
        )
        val recovered = newController().recoverOrphan()
        if (recovered != null) {
            // Surfaced ⇒ must be genuinely decodable (never a broken card).
            assertEquals("RECOVERED", recovered.syncState)
            assertAudioReadable(encryptor.decryptToTemp(File(recovered.localAudioRef!!)))
            assertNotNull(dao.getById("orphan-killed"))
        } else {
            // Discarded ⇒ nothing persisted, partial cleaned up.
            assertTrue("no broken card persisted", dao.getAll().isEmpty())
            assertFalse("undecodable partial cleaned up", temp.exists())
        }
        assertNull("recovery marker cleared either way", recoveryStore.read())
    }

    /** Mirror of [RecorderController.pendingDir] for the engine-direct tests above. */
    private fun pendingDir(): File = File(context.filesDir, "pending").apply { mkdirs() }

    /**
     * Phase-6 regression: a capture whose `stop()` throws (e.g. a too-short tap →
     * `MediaRecorder.stop()` "stop failed") must NOT wedge the recorder on
     * `Recording`. Before the `try/finally` in [RecorderController.stop], the state
     * stayed `Recording` and the UI Stop button appeared dead. The recorder must
     * always return to `Idle`.
     */
    @Test
    fun stopThatThrows_stillReturnsToIdle() = runBlocking {
        resolver = object : CaptureSourceResolver {
            override fun resolve(job: CaptureJob): CaptureSource = ThrowingStopSource()
        }
        val controller = newController()
        controller.start(CaptureJob.MEMO, CaptureMode.ON_DEMAND, hasRecordAudioPermission = true)
        assertTrue(controller.state.value is RecorderState.Recording)

        runCatching { controller.stop() } // the service swallows it; we just need no wedge

        assertEquals(RecorderState.Idle, controller.state.value)
    }

    /**
     * Phase-6 guarantee (blast-radius into the Step-2 karaoke player): a real
     * sub-second tap-record-stop is **either kept as a decodable media file OR
     * cleanly discarded** — never a broken, unplayable card. Whatever the outcome on
     * this hardware, the recorder ends Idle. This is the live-mic analog of the
     * empty-file unit guard below.
     */
    @Test
    fun subSecondRecord_isReadableIfKept_elseDiscarded() = runBlocking {
        val controller = newController()
        controller.start(CaptureJob.MEMO, CaptureMode.ON_DEMAND, hasRecordAudioPermission = true)
        delay(350)
        val entity = controller.stop()

        if (entity != null) {
            // Kept ⇒ must be a real, decodable container (what the player will open).
            val decrypted = encryptor.decryptToTemp(File(entity.localAudioRef!!))
            assertAudioReadable(decrypted)
        } // else cleanly discarded — also satisfies the guarantee
        assertEquals(RecorderState.Idle, controller.state.value)
    }

    /**
     * Phase-6 regression (blast-radius guard for the Step-2 player): a sub-second
     * tap-record-stop that yields an **empty/zero-length file** must be cleanly
     * discarded — `stop()` returns null and NO row is persisted — so a broken,
     * unplayable card can never reach the library (and the karaoke player).
     */
    @Test
    fun stopWithEmptyFile_discardsAndPersistsNothing() = runBlocking {
        resolver = object : CaptureSourceResolver {
            override fun resolve(job: CaptureJob): CaptureSource = EmptyFileSource(context)
        }
        val controller = newController()
        controller.start(CaptureJob.MEMO, CaptureMode.ON_DEMAND, hasRecordAudioPermission = true)

        val entity = controller.stop()

        assertNull("empty capture must not persist a card", entity)
        assertTrue("DB has no recordings", dao.getAll().isEmpty())
        assertEquals(RecorderState.Idle, controller.state.value)
    }

    private class EmptyFileSource(private val ctx: Context) : CaptureSource {
        override val id = CaptureSourceId.MIC
        override val capability = CaptureCapability(CaptureSourceId.MIC, Fidelity.LOSSY)
        override fun isAvailable(ctx: DeviceContext) = true
        override suspend fun start(spec: RecordingSpec): CaptureHandle {
            // Create a real but ZERO-LENGTH temp, the degenerate sub-second outcome.
            spec.outputTempFile.also { it.delete(); it.createNewFile() }
            return object : CaptureHandle {
                override fun amplitude() = kotlinx.coroutines.flow.emptyFlow<Float>()
                override suspend fun pause() = Unit
                override suspend fun resume() = Unit
                override suspend fun stop() = CaptureResult(spec.outputTempFile, Codec.AAC, 0L)
            }
        }
    }

    private class ThrowingStopSource : CaptureSource {
        override val id = CaptureSourceId.MIC
        override val capability = CaptureCapability(CaptureSourceId.MIC, Fidelity.LOSSY)
        override fun isAvailable(ctx: DeviceContext) = true
        override suspend fun start(spec: RecordingSpec): CaptureHandle = object : CaptureHandle {
            override fun amplitude() = kotlinx.coroutines.flow.emptyFlow<Float>()
            override suspend fun pause() = Unit
            override suspend fun resume() = Unit
            override suspend fun stop(): CaptureResult = error("stop failed")
        }
    }

    private fun assertAudioReadable(file: File) {
        val mime = audioMime(file)
        assertTrue("expected an audio track, got mime=$mime", mime != null && mime.startsWith("audio/"))
    }

    private fun audioMime(file: File): String? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            (0 until ex.trackCount)
                .map { ex.getTrackFormat(it) }
                .firstNotNullOfOrNull { it.getString(MediaFormat.KEY_MIME) }
        } finally {
            ex.release()
        }
    }
}
