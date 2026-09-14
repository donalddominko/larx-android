package london.aipartner.echo.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Where + how to reach a user's WebDAV server. Provider-agnostic (works for a plain
 *  WebDAV mount, Nextcloud, etc.). [baseUrl] must point at an **existing writable
 *  collection** — Echo PUTs flat blob names into it (no MKCOL, which HttpURLConnection
 *  can't issue anyway). The password is an app-specific credential the user enters; it
 *  is never committed and lives only in secure on-device storage. */
data class WebDavConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
)

/** A WebDAV upload/delete returned a non-success HTTP status. */
class WebDavHttpError(val code: Int, val method: String) :
    IOException("WebDAV $method failed with HTTP $code")

/**
 * Generic WebDAV [CloudSink] (Sync & Infrastructure Engineer's seam). PUT a
 * passphrase-encrypted blob, DELETE it — Basic auth over HTTPS, plain
 * `HttpURLConnection` (no production HTTP dependency). Provider-agnostic on purpose:
 * NOT Nextcloud-tailored — the seam is generic; Nextcloud-specific paths would be a
 * later, confirmed-target addition.
 *
 * The payload is ALWAYS ciphertext ([SyncEngine] re-encrypts before calling `put`), so
 * this sink — like every sink — is zero-knowledge to the host.
 */
class WebDavSink(
    private val config: WebDavConfig,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : CloudSink {

    override val id = SinkId.WEBDAV

    private val authHeader: String =
        "Basic " + Base64.getEncoder().encodeToString(
            "${config.username}:${config.password}".toByteArray(Charsets.UTF_8),
        )

    override suspend fun put(artifact: SyncArtifact, payload: ByteArray): RemoteRef =
        withContext(Dispatchers.IO) {
            val remoteName = artifact.remoteName
            val conn = open(remoteName, "PUT")
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(payload.size)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            try {
                conn.outputStream.use { it.write(payload) }
                val code = conn.responseCode
                if (code !in 200..299) throw WebDavHttpError(code, "PUT")
            } finally {
                conn.disconnect()
            }
            RemoteRef(id, remoteName)
        }

    override suspend fun delete(ref: RemoteRef) = withContext(Dispatchers.IO) {
        val conn = open(ref.remoteId, "DELETE")
        try {
            val code = conn.responseCode
            // 404 = already gone; treat as success (idempotent delete-everywhere).
            if (code !in 200..299 && code != HttpURLConnection.HTTP_NOT_FOUND) {
                throw WebDavHttpError(code, "DELETE")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val url = URL(joinUrl(config.baseUrl, path))
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Authorization", authHeader)
            instanceFollowRedirects = false
        }
    }

    private fun joinUrl(base: String, path: String) =
        base.trimEnd('/') + "/" + path.trimStart('/')
}
