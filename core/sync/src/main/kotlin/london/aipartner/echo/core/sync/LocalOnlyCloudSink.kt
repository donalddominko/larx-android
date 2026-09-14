package london.aipartner.echo.core.sync

import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 1 stub. v1 is local-only, but the seam is real: [put] records a local
 * ref and [delete] honors the delete-everywhere contract (so the call site
 * compiles and is exercised now). Real DriveSink / WebDavSink land in Phase 8.
 */
class LocalOnlyCloudSink : CloudSink {
    override val id = SinkId.LOCAL_ONLY

    private val refs = ConcurrentHashMap<String, RemoteRef>()

    override suspend fun put(artifact: SyncArtifact, payload: ByteArray): RemoteRef {
        val ref = RemoteRef(sinkId = SinkId.LOCAL_ONLY, remoteId = artifact.remoteName)
        refs[ref.remoteId] = ref
        return ref
    }

    override suspend fun delete(ref: RemoteRef) {
        refs.remove(ref.remoteId)
    }
}
