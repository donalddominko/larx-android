package london.aipartner.echo.core.sync

/**
 * The production [CloudSink] the sync engine talks to. It holds no config of its own;
 * instead it resolves a [WebDavConfig] from [configProvider] **per call**, so a
 * destination the user configures (or changes) at runtime takes effect without
 * re-wiring the singleton. When no destination is configured it fails loudly
 * ([NoDestinationConfigured]) rather than silently pretending to back up — the worker
 * guards on [isConfigured] first, so this only fires on a genuine race/misuse.
 *
 * Only WebDAV exists in this step (Drive appfolder is Step 5). The payload it forwards
 * is ALWAYS ciphertext — [SyncEngine] re-encrypts before `put` — so zero-knowledge
 * holds regardless of which concrete sink is resolved.
 */
class RoutingCloudSink(
    private val configProvider: () -> WebDavConfig?,
) : CloudSink {

    override val id = SinkId.WEBDAV

    /** True when a destination is configured — the worker checks this before syncing. */
    fun isConfigured(): Boolean = configProvider() != null

    private fun resolve(): CloudSink =
        configProvider()?.let { WebDavSink(it) } ?: throw NoDestinationConfigured()

    override suspend fun put(artifact: SyncArtifact, payload: ByteArray): RemoteRef =
        resolve().put(artifact, payload)

    override suspend fun delete(ref: RemoteRef) = resolve().delete(ref)
}

/** No backup destination (WebDAV server) has been configured, so nothing can be uploaded. */
class NoDestinationConfigured :
    IllegalStateException("no backup destination configured — choose one in Settings first")
