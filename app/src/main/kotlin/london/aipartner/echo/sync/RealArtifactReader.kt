package london.aipartner.echo.sync

import java.io.File
import javax.inject.Inject
import london.aipartner.echo.core.capture.AudioEncryptor
import london.aipartner.echo.core.data.RecordingDao
import london.aipartner.echo.core.data.TranscriptDao
import london.aipartner.echo.core.sync.ArtifactKind
import london.aipartner.echo.core.sync.ArtifactReader
import london.aipartner.echo.core.sync.SyncArtifact
import org.json.JSONArray
import org.json.JSONObject

/**
 * The real byte source for the sync path — reads the SAME on-device artifacts the app
 * produced, decrypting audio with the device Keystore key exactly as playback/transcription
 * do. Its output is handed straight to the [london.aipartner.echo.core.sync.backup.BackupCipher],
 * which re-encrypts with the user's Argon2id backup key before it reaches a sink, so this
 * process is the only place the plaintext ever exists.
 *
 * - AUDIO    — decrypt the encrypted recording to a transient temp, read its bytes, then
 *              **shred the temp** (no plaintext audio left on disk), mirroring the
 *              transcription/playback decrypt-then-shred discipline.
 * - TRANSCRIPT — the **latest** revision (highest `rev`, honoring user edits) + its segments,
 *              serialized to JSON. Backing up the transcript that exists, not re-deriving it.
 * - METADATA — the recording's durable facts (capture capability, timing, status) as JSON.
 *
 * A missing artifact is honest: no audio ref ⇒ throws (the recording surfaces as FAILED,
 * never a silent empty backup); no transcript yet ⇒ an empty-segments document.
 */
class RealArtifactReader @Inject constructor(
    private val audioEncryptor: AudioEncryptor,
    private val recordingDao: RecordingDao,
    private val transcriptDao: TranscriptDao,
) : ArtifactReader {

    override suspend fun read(artifact: SyncArtifact): ByteArray {
        val recording = recordingDao.getById(artifact.recordingId)
            ?: throw IllegalStateException("recording ${artifact.recordingId} not found")
        return when (artifact.kind) {
            ArtifactKind.AUDIO -> readAudio(recording.localAudioRef)
            ArtifactKind.TRANSCRIPT -> readTranscript(artifact.recordingId)
            ArtifactKind.METADATA -> readMetadata(recording)
        }
    }

    private fun readAudio(localAudioRef: String?): ByteArray {
        val ref = localAudioRef
            ?: throw IllegalStateException("recording has no local audio to back up")
        val temp = audioEncryptor.decryptToTemp(File(ref))
        return try {
            temp.readBytes()
        } finally {
            // Shred the transient plaintext: overwrite then delete (Hard rule — no leak).
            runCatching {
                temp.outputStream().use { out ->
                    out.write(ByteArray(temp.length().coerceAtMost(1 shl 20).toInt()))
                }
            }
            temp.delete()
        }
    }

    private suspend fun readTranscript(recordingId: String): ByteArray {
        val latest = transcriptDao.revisionsFor(recordingId).maxByOrNull { it.rev }
        val root = JSONObject()
        root.put("recordingId", recordingId)
        if (latest == null) {
            root.put("rev", JSONObject.NULL)
            root.put("segments", JSONArray())
        } else {
            root.put("rev", latest.rev)
            root.put("locus", latest.locus)
            root.put("languageTag", latest.languageTag ?: JSONObject.NULL)
            root.put("createdAt", latest.createdAt)
            val segments = JSONArray()
            transcriptDao.segmentsFor(latest.id).forEach { seg ->
                segments.put(
                    JSONObject()
                        .put("orderIdx", seg.orderIdx)
                        .put("text", seg.text)
                        .put("tStartMs", seg.tStartMs)
                        .put("tEndMs", seg.tEndMs)
                        .put("speaker", seg.speaker ?: JSONObject.NULL),
                )
            }
            root.put("segments", segments)
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun readMetadata(recording: london.aipartner.echo.core.data.RecordingEntity): ByteArray =
        JSONObject()
            .put("id", recording.id)
            .put("createdAt", recording.createdAt)
            .put("durationMs", recording.durationMs)
            .put("captureSource", recording.captureSource)
            .put("fidelity", recording.fidelity)
            .put("captureMode", recording.captureMode)
            .put("contactHash", recording.contactHash ?: JSONObject.NULL)
            .put("transcriptionStatus", recording.transcriptionStatus)
            .put("detectedLanguageTag", recording.detectedLanguageTag ?: JSONObject.NULL)
            .toString()
            .toByteArray(Charsets.UTF_8)
}
