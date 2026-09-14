package london.aipartner.echo.core.sync

import london.aipartner.echo.core.sync.backup.BackupCipher

/**
 * Reads the **device-decrypted plaintext bytes** for an artifact (the real impl
 * decrypts via the capture module's `AudioEncryptor`). The [SyncGate] guarantees this
 * is **never even called** when a gate is closed — the "zero bytes read" half of the
 * forced-DENY analog. Its output is immediately re-encrypted by the [BackupCipher]
 * before it reaches the sink, so plaintext never leaves this process.
 */
fun interface ArtifactReader {
    suspend fun read(artifact: SyncArtifact): ByteArray
}

/** The result of attempting to sync one artifact. */
sealed interface SyncOutcome {
    data class Synced(val ref: RemoteRef) : SyncOutcome
    data class Blocked(val reason: SyncBlockReason) : SyncOutcome
    data class Failed(val error: Throwable) : SyncOutcome
}

/**
 * Sink-agnostic sync driver. For each artifact it enforces the [SyncGate] **before**
 * opening/reading the file (no optimistic-read-then-check), then hands the bytes to the
 * [CloudSink]. Step 2 proves the #1 gate item against a fake sink: egress-off ⇒ the
 * reader and `sink.put` are never called (zero bytes, zero puts); gate-open ⇒ `put`
 * exactly once per artifact. (Re-encryption is inserted in Step 3; a real sink +
 * WorkManager orchestration in Step 4 — this engine's shape doesn't change.)
 */
class SyncEngine(
    private val gate: SyncGate,
    private val reader: ArtifactReader,
    private val cipher: BackupCipher,
    private val sink: CloudSink,
) {
    suspend fun sync(artifact: SyncArtifact): SyncOutcome {
        // The #1 gate — at the very top, before opening the file.
        gate.blockReason()?.let { return SyncOutcome.Blocked(it) }
        return try {
            // Only reached when BOTH gates are open. Reading here is the first byte
            // access; nothing above this line touches the recording's bytes.
            val plaintext = reader.read(artifact)
            // Zero-knowledge boundary: re-encrypt with the user's backup key BEFORE the
            // sink ever sees the bytes. The sink receives only the encrypted payload.
            val payload = cipher.encrypt(plaintext)
            SyncOutcome.Synced(sink.put(artifact, payload))
        } catch (t: Throwable) {
            SyncOutcome.Failed(t)
        }
    }

    /** Walk a set of pending artifacts; each is independently gated. */
    suspend fun syncAll(artifacts: List<SyncArtifact>): List<SyncOutcome> =
        artifacts.map { sync(it) }
}
