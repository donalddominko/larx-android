package london.aipartner.echo.core.sync

/**
 * Seam 5 — destination-agnostic sync. DriveSink (Drive, appfolder scope only),
 * WebDavSink (own VPS / Nextcloud), reserved S3Sink all sit behind this. Sync is
 * a Pro feature (Phase 8), but the seam exists from Phase 1 so local-only v1
 * already speaks its language — and the delete-everywhere contract compiles now.
 */
interface CloudSink {
    val id: SinkId

    /**
     * Store [payload] for [artifact] and return its [RemoteRef]. [payload] is ALWAYS
     * the passphrase-encrypted (Option B, zero-knowledge) blob — the sink never sees
     * plaintext. The re-encryption happens in [SyncEngine] before this is called, so
     * no sink implementation can accidentally upload plaintext.
     */
    suspend fun put(artifact: SyncArtifact, payload: ByteArray): RemoteRef

    /** Deletion MUST propagate to every sink — the delete-everywhere contract. */
    suspend fun delete(ref: RemoteRef)
}

enum class SinkId { LOCAL_ONLY, DRIVE, WEBDAV, S3 }

/**
 * One uploadable unit. A recording backs up as THREE artifacts — audio, the latest
 * transcript, and metadata (Option B: back up what exists, don't re-derive) — each
 * encrypted before upload. [remoteName] is the flat blob name at the sink, unique per
 * (recording, kind) so the three never collide.
 */
data class SyncArtifact(
    val recordingId: String,
    val kind: ArtifactKind,
    val localPath: String,
) {
    val remoteName: String get() = "$recordingId-${kind.suffix}"
}

/** The three things that make up a backed-up recording. */
enum class ArtifactKind(val suffix: String) {
    AUDIO("audio"),
    TRANSCRIPT("transcript"),
    METADATA("metadata"),
}

/** Opaque handle to a synced artifact at a destination. */
data class RemoteRef(
    val sinkId: SinkId,
    val remoteId: String,
)
