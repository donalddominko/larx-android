package london.aipartner.echo.core.sync

import kotlinx.coroutines.runBlocking
import london.aipartner.echo.core.billing.Entitlements
import london.aipartner.echo.core.billing.Feature
import london.aipartner.echo.core.sync.backup.BackupCipher
import london.aipartner.echo.core.sync.backup.BackupCrypto
import london.aipartner.echo.core.sync.backup.BackupKeyDeriver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zero-knowledge gate (Option B, all sinks): what the sink actually receives contains
 * **no known plaintext** and decrypts back **only** with the Argon2id passphrase key —
 * a wrong passphrase fails. Proven end-to-end through the real [SyncEngine] path
 * (reader → [BackupCipher] → sink), so no plaintext-upload path exists.
 */
class SyncZeroKnowledgeTest {

    /** Fake sink that captures the uploaded payload for inspection. */
    private class CapturingSink : CloudSink {
        override val id = SinkId.WEBDAV
        var lastPayload: ByteArray? = null
        override suspend fun put(artifact: SyncArtifact, payload: ByteArray): RemoteRef {
            lastPayload = payload
            return RemoteRef(id, artifact.recordingId)
        }
        override suspend fun delete(ref: RemoteRef) {}
    }

    private val entitled = object : Entitlements { override fun has(feature: Feature) = true }
    private val salt = ByteArray(BackupKeyDeriver.SALT_LENGTH_BYTES) { (it * 7).toByte() }
    private val key = BackupKeyDeriver.deriveKey("correct horse battery staple".toCharArray(), salt)

    private val transcriptPlaintext =
        "Board meeting: revenue up 12%, hire two engineers, ship Phase 8.".toByteArray()

    @Test fun uploadedPayload_hasNoPlaintext_andDecryptsOnlyWithTheBackupKey() = runBlocking {
        val sink = CapturingSink()
        val engine = SyncEngine(
            gate = SyncGate({ true }, entitled),
            reader = { transcriptPlaintext },                 // device-decrypted bytes
            cipher = BackupCipher { BackupCrypto.encrypt(key, it) },
            sink = sink,
        )

        engine.sync(SyncArtifact("rec-1", ArtifactKind.TRANSCRIPT, "/enc/rec-1.ogg"))

        val payload = requireNotNull(sink.lastPayload) { "sink received no payload" }
        // 1) No plaintext survives in what was uploaded.
        val haystack = String(payload, Charsets.ISO_8859_1)
        assertFalse(haystack.contains("Board meeting"))
        assertFalse(haystack.contains("revenue"))
        assertFalse(
            payload.asSequence().windowed(transcriptPlaintext.size)
                .any { it.toByteArray().contentEquals(transcriptPlaintext) },
        )
        // 2) It decrypts back ONLY with the right key.
        assertTrue(BackupCrypto.decrypt(key, payload).contentEquals(transcriptPlaintext))
        // 3) A wrong passphrase fails.
        val wrongKey = BackupKeyDeriver.deriveKey("wrong passphrase entirely".toCharArray(), salt)
        assertThrows(BackupCrypto.WrongKeyOrCorruptData::class.java) {
            BackupCrypto.decrypt(wrongKey, payload)
        }
        Unit
    }

    private fun List<Byte>.toByteArray() = ByteArray(size) { this[it] }
}
