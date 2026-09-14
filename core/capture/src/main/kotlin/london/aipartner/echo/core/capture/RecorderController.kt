package london.aipartner.echo.core.capture

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import london.aipartner.echo.core.consent.CaptureMode
import london.aipartner.echo.core.consent.ConsentGate
import london.aipartner.echo.core.consent.ConsentVerdict
import london.aipartner.echo.core.consent.RecordRequest
import london.aipartner.echo.core.data.ConsentRecordDao
import london.aipartner.echo.core.data.ConsentRecordEntity
import london.aipartner.echo.core.data.RecordingDao
import london.aipartner.echo.core.data.RecordingEntity

sealed interface RecorderState {
    data object Idle : RecorderState
    data class Recording(
        val recordingId: String,
        val capability: CaptureCapability,
        val paused: Boolean,
    ) : RecorderState
    /** Capture has stopped (mic released) and the recording is being finalized —
     *  encrypted, and re-encoded if a mic boost is set. Briefly between Recording and
     *  Idle; the UI shows "Saving…" so a slow re-encode doesn't read as a frozen screen. */
    data object Finalizing : RecorderState
}

sealed interface StartResult {
    data class Started(val recordingId: String, val capability: CaptureCapability) : StartResult
    /** The no-bypass guarantee: a DENY produces NO file and surfaces a reason. */
    data class Denied(val reason: String) : StartResult
}

/**
 * The capture core the foreground service delegates to. Owns the single
 * in-progress recording: routes through [ConsentGate] before [CaptureSource.start]
 * (the only path to start), encrypts the result at rest, persists an immutable
 * [RecordingEntity], and recovers an orphaned partial after process death.
 *
 * Kept free of Service/notification concerns so it is testable directly.
 */
class RecorderController(
    private val context: Context,
    private val resolver: CaptureSourceResolver,
    private val consentGate: ConsentGate,
    private val encryptor: AudioEncryptor,
    private val recordingDao: RecordingDao,
    private val consentRecordDao: ConsentRecordDao,
    private val recoveryStore: RecordingRecoveryStore,
) {
    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private var handle: CaptureHandle? = null
    private var activeId: String? = null
    private var activeMode: CaptureMode = CaptureMode.ON_DEMAND
    private var activeCapability: CaptureCapability? = null

    fun amplitude(): Flow<Float> = handle?.amplitude() ?: emptyFlow()

    /**
     * In-progress capture is written here — under `filesDir`, **never `cacheDir`**.
     * The OS storage manager may purge `cacheDir` at any time under storage pressure,
     * and on the A03 (Phase 6 Step 3 device pass) a low-storage purge deleted a live
     * recording's temp mid-capture, silently destroying the audio. `filesDir` is not
     * reclaimable by the OS, so an active recording survives storage pressure. The
     * temp is consumed by `AudioEncryptor.encryptFrom` (which deletes it) on stop, or
     * cleaned by the empty-file guard / orphan recovery — so `.pending/` self-empties.
     */
    private fun pendingDir(): File =
        // BESIDE recordings, never inside it — recordingsDir must hold only finished
        // encrypted audio files (anything listing it treats entries as files; a nested
        // dir caused an EISDIR crash). filesDir/pending is the in-progress scratch area.
        File(context.filesDir, "pending").apply { mkdirs() }

    /**
     * Evaluates consent, then (only on a non-DENY verdict) resolves a source and
     * starts capture. [preferredCodec] forces a codec (tests/forced AAC); null = auto.
     */
    suspend fun start(
        job: CaptureJob,
        mode: CaptureMode,
        hasRecordAudioPermission: Boolean,
        preferredCodec: Codec? = null,
    ): StartResult {
        if (_state.value is RecorderState.Recording) {
            return StartResult.Denied("already recording")
        }
        val req = RecordRequest(
            captureMode = mode,
            hasRecordAudioPermission = hasRecordAudioPermission,
        )
        when (consentGate.evaluate(req)) {
            ConsentVerdict.DENY ->
                return StartResult.Denied("consent gate denied (RECORD_AUDIO not granted)")
            ConsentVerdict.ALLOW -> Unit
        }

        val source = resolver.resolve(job)
        val id = UUID.randomUUID().toString()
        val temp = File.createTempFile("rec_", ".tmp", pendingDir())
        val spec = RecordingSpec(outputTempFile = temp, preferredCodec = preferredCodec)

        val started = source.start(spec)
        handle = started
        activeId = id
        activeMode = mode
        activeCapability = source.capability

        recoveryStore.markStarted(
            RecordingRecoveryStore.Orphan(
                recordingId = id,
                tempPath = temp.absolutePath,
                startedAt = System.currentTimeMillis(),
                sourceId = source.id,
                codec = preferredCodec ?: Codec.OPUS,
                captureMode = mode.name,
            ),
        )
        _state.value = RecorderState.Recording(id, source.capability, paused = false)
        return StartResult.Started(id, source.capability)
    }

    suspend fun pause() {
        val s = _state.value as? RecorderState.Recording ?: return
        handle?.pause()
        _state.value = s.copy(paused = true)
    }

    suspend fun resume() {
        val s = _state.value as? RecorderState.Recording ?: return
        handle?.resume()
        _state.value = s.copy(paused = false)
    }

    /** Stops, encrypts at rest, persists an immutable RecordingEntity, returns it.
     *
     * **Always returns to [RecorderState.Idle], even if the underlying recorder
     * stop fails** (e.g. a too-short tap where `MediaRecorder.stop()` throws
     * "stop failed"). Without the `finally` the controller would stay wedged on
     * `Recording` and the UI Stop button would appear dead — the recorder must
     * never get stuck. A failed capture persists nothing and surfaces the error to
     * the caller (the service swallows it); the recoverer cleans the orphan temp.
     *
     * Returns the persisted recording, or **null when there was nothing worth
     * keeping** — a sub-second tap whose `stop()` succeeds but produces a missing or
     * empty file is **cleanly discarded**, never persisted as a broken card. (A
     * `stop()` that *throws* discards by propagating; this guards the it-succeeded-
     * but-the-file-is-empty case.) Combined with the no-fabrication transcript rule,
     * this keeps a zero-length/unplayable artifact from ever reaching the player. */
    suspend fun stop(): RecordingEntity? {
        // Claim the active recording atomically and null the fields BEFORE the (now
        // potentially slow) gain re-encode. A repeat ACTION_STOP delivered while the
        // encode runs then sees no active recording and is a clean no-op — without this
        // each redelivery re-encoded and re-inserted the same id (UNIQUE constraint
        // crash). The reads + nulling are synchronous (no suspension between), so a
        // second stop can't capture the same handle.
        val h = handle ?: return null
        val id = activeId ?: return null
        val capability = activeCapability ?: return null
        handle = null
        activeId = null
        activeCapability = null
        // Mic is about to be released; show "Saving…" while we encrypt + (maybe) re-encode.
        _state.value = RecorderState.Finalizing
        try {
            val result = h.stop()
            if (!result.outputTempFile.exists() || result.outputTempFile.length() == 0L) {
                runCatching { result.outputTempFile.delete() }
                recoveryStore.clear()
                return null
            }
            // Gain is applied LIVE during capture (the mic-boost slider feeds the
            // AudioRecord engine), so finalize is just encrypt + persist — no re-encode.
            val encrypted = encryptor.encryptFrom(result.outputTempFile, fileName(id, result.codec))
            val entity = recordingEntity(
                id = id,
                capability = capability,
                mode = activeMode,
                codec = result.codec,
                durationMs = result.durationMs,
                localAudioRef = encrypted.absolutePath,
                syncState = "LOCAL_ONLY",
            )
            recordingDao.insert(entity)
            writeConsentRecord(id, basis = "on-demand; RECORD_AUDIO granted")
            recoveryStore.clear()
            return entity
        } finally {
            reset()
        }
    }

    /**
     * Process-death recovery. If a partial temp from a killed recording survives,
     * encrypt + persist it (flagged RECOVERED) so it's surfaced, not lost.
     * Returns the recovered entity, or null if there was nothing to recover.
     */
    suspend fun recoverOrphan(): RecordingEntity? {
        val orphan = recoveryStore.read() ?: return null
        val temp = File(orphan.tempPath)
        if (!temp.exists() || temp.length() == 0L) {
            temp.delete()
            recoveryStore.clear()
            return null
        }
        // Valid-or-discard, extended to recovery. A process kill never reaches
        // MediaMuxer.stop(), so a real new-engine (AudioRecord→MediaCodec→MediaMuxer)
        // partial has mdat frames but NO moov atom → it is undecodable, and the captured
        // audio is genuinely unrecoverable. Surfacing it as a "RECOVERED" card would be a
        // phantom recording with no playable audio — dishonest. So if the partial is not a
        // decodable media file, discard it (and log a breadcrumb) rather than persist a
        // broken card. (Same honesty contract as stop()'s empty-file guard.)
        if (!isDecodableMediaFile(temp)) {
            Log.w(
                TAG,
                "Recovered partial ${orphan.recordingId} is undecodable (killed muxer left " +
                    "no moov atom); discarding rather than surfacing a broken card.",
            )
            temp.delete()
            recoveryStore.clear()
            return null
        }
        val encrypted = encryptor.encryptFrom(temp, fileName(orphan.recordingId, orphan.codec))
        val entity = recordingEntity(
            id = orphan.recordingId,
            capability = CaptureCapability(orphan.sourceId, Fidelity.LOSSY),
            mode = runCatching { CaptureMode.valueOf(orphan.captureMode) }
                .getOrDefault(CaptureMode.ON_DEMAND),
            codec = orphan.codec,
            durationMs = 0L,
            localAudioRef = encrypted.absolutePath,
            syncState = "RECOVERED",
        )
        recordingDao.insert(entity)
        writeConsentRecord(orphan.recordingId, basis = "on-demand; recovered after process death")
        recoveryStore.clear()
        return entity
    }

    /** The audit trail: every recording gets a ConsentRecord (verdict it acted on). */
    private suspend fun writeConsentRecord(recordingId: String, basis: String) {
        consentRecordDao.insert(
            ConsentRecordEntity(
                id = UUID.randomUUID().toString(),
                recordingId = recordingId,
                verdict = ConsentVerdict.ALLOW.name,
                basis = basis,
                noticeEmitted = false, // memo app: no audible notice mechanism
                jurisdiction = "user-responsibility",
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** True iff [file] is a decodable media container with an audio track. A killed-muxer
     *  partial (no moov atom) makes `setDataSource` throw → false. */
    private fun isDecodableMediaFile(file: File): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            (0 until ex.trackCount).any {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { ex.release() }
        }
    }

    private fun reset() {
        handle = null
        activeId = null
        activeCapability = null
        _state.value = RecorderState.Idle
    }

    private fun fileName(id: String, codec: Codec) =
        id + if (codec == Codec.OPUS) ".ogg" else ".m4a"

    private fun recordingEntity(
        id: String,
        capability: CaptureCapability,
        mode: CaptureMode,
        codec: Codec,
        durationMs: Long,
        localAudioRef: String,
        syncState: String,
    ) = RecordingEntity(
        id = id,
        createdAt = System.currentTimeMillis(),
        durationMs = durationMs,
        captureSource = capability.source.name,
        fidelity = capability.fidelity.name,
        captureMode = mode.name,
        contactHash = null,
        localAudioRef = localAudioRef,
        encryptionMeta = "EncryptedFile:AES256_GCM_HKDF_4KB;codec=${codec.name}",
        syncState = syncState,
    )

    private companion object {
        const val TAG = "EchoCapture"
    }
}
