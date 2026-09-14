package london.aipartner.echo.core.sync

import kotlinx.coroutines.runBlocking
import london.aipartner.echo.core.billing.Entitlements
import london.aipartner.echo.core.billing.Feature
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Exercises the REAL WebDAV network code (HttpURLConnection) against a controllable
 * MockWebServer: exact bytes uploaded, Basic auth, and failure injection (401/500/
 * mid-upload disconnect) — plus the #1 egress gate proven against the real sink (zero
 * requests reach the server when egress is off).
 */
class WebDavSinkTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun sink() = WebDavSink(
        WebDavConfig(baseUrl = server.url("/dav/").toString(), username = "echo", password = "s3cret"),
    )

    private val ciphertext = ByteArray(2048) { (it * 31 + 7).toByte() }

    @Test fun put_uploadsExactBytes_withBasicAuth() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        val ref = sink().put(SyncArtifact("rec-1", ArtifactKind.AUDIO, "/enc/rec-1.ogg"), ciphertext)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue(req.path!!.endsWith("/dav/rec-1-audio"))
        // Basic base64("echo:s3cret")
        assertEquals("Basic ZWNobzpzM2NyZXQ=", req.getHeader("Authorization"))
        assertArrayEquals(ciphertext, req.body.readByteArray())
        assertEquals(SinkId.WEBDAV, ref.sinkId)
        assertEquals("rec-1-audio", ref.remoteId)
    }

    @Test fun put_on401_throwsTypedError() {
        server.enqueue(MockResponse().setResponseCode(401))
        val e = assertThrows(WebDavHttpError::class.java) {
            runBlocking { sink().put(SyncArtifact("rec-1", ArtifactKind.AUDIO, "/p"), ciphertext) }
        }
        assertEquals(401, e.code)
    }

    @Test fun put_on500_throwsTypedError() {
        server.enqueue(MockResponse().setResponseCode(500))
        val e = assertThrows(WebDavHttpError::class.java) {
            runBlocking { sink().put(SyncArtifact("rec-1", ArtifactKind.AUDIO, "/p"), ciphertext) }
        }
        assertEquals(500, e.code)
    }

    @Test fun put_onMidUploadDisconnect_throwsIOException() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY))
        assertThrows(IOException::class.java) {
            runBlocking { sink().put(SyncArtifact("rec-1", ArtifactKind.AUDIO, "/p"), ciphertext) }
        }
    }

    @Test fun delete_missingRemote_isIdempotent() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404)) // already gone
        sink().delete(RemoteRef(SinkId.WEBDAV, "rec-1")) // must NOT throw
        assertEquals("DELETE", server.takeRequest().method)
    }

    // ── #1 gate item, proven against the real network sink ──

    @Test fun egressOff_reachesTheServerZeroTimes() = runBlocking {
        val engine = SyncEngine(
            gate = SyncGate({ false }, entitled),
            reader = { ciphertext },
            cipher = { it },
            sink = sink(),
        )
        engine.syncAll(listOf(SyncArtifact("rec-1", ArtifactKind.AUDIO, "/p"), SyncArtifact("rec-2", ArtifactKind.AUDIO, "/p")))
        assertEquals(0, server.requestCount) // NO byte reached the network
    }

    @Test fun egressOn_uploadsEachRecordingExactlyOnce() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        val engine = SyncEngine(
            gate = SyncGate({ true }, entitled),
            reader = { ciphertext },
            cipher = { it },
            sink = sink(),
        )
        val outcomes = engine.syncAll(listOf(SyncArtifact("rec-1", ArtifactKind.AUDIO, "/p"), SyncArtifact("rec-2", ArtifactKind.AUDIO, "/p")))
        assertEquals(2, server.requestCount)
        assertTrue(outcomes.all { it is SyncOutcome.Synced })
    }

    private val entitled = object : Entitlements { override fun has(feature: Feature) = true }
}
