package london.aipartner.echo.core.sync

import kotlinx.coroutines.runBlocking
import london.aipartner.echo.core.billing.Entitlements
import london.aipartner.echo.core.billing.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **#1 Phase-8 gate item** (the sync analog of "a forced-DENY produces no file"):
 * with `dataEgressAllowed = false`, a full sync attempt calls the sink's `put` **zero
 * times** and reads **zero bytes** of the recording; flip egress on (with Pro) ⇒ `put`
 * is called exactly once per pending recording. Deterministic, no network, before any
 * real sink exists.
 */
class SyncEngineGateTest {

    /** Spy sink — counts puts/deletes; never touches a network. */
    private class SpySink : CloudSink {
        override val id = SinkId.WEBDAV
        var putCount = 0
        val deleted = mutableListOf<RemoteRef>()
        override suspend fun put(artifact: SyncArtifact, payload: ByteArray): RemoteRef {
            putCount++
            return RemoteRef(id, artifact.recordingId)
        }
        override suspend fun delete(ref: RemoteRef) { deleted += ref }
    }

    /** Byte-counting reader — proves whether ANY byte of a recording was read. */
    private class CountingReader : ArtifactReader {
        var readCount = 0
        var bytesRead = 0L
        override suspend fun read(artifact: SyncArtifact): ByteArray {
            readCount++
            val bytes = ByteArray(1024) // stand-in for real audio bytes
            bytesRead += bytes.size
            return bytes
        }
    }

    // The gate tests don't exercise encryption (egress-off never reaches it); a
    // passthrough keeps them focused on the gate. Zero-knowledge is covered separately.
    private val passthrough = london.aipartner.echo.core.sync.backup.BackupCipher { it }

    private fun entitlements(has: Boolean) = object : Entitlements {
        override fun has(feature: Feature) = has
    }
    private val entitled = entitlements(true)
    private val notEntitled = entitlements(false)
    private fun egress(on: Boolean) = SyncEgressConsent { on }

    private val pending = listOf(
        SyncArtifact("rec-1", ArtifactKind.AUDIO, "/enc/rec-1.ogg"),
        SyncArtifact("rec-2", ArtifactKind.AUDIO, "/enc/rec-2.ogg"),
        SyncArtifact("rec-3", ArtifactKind.AUDIO, "/enc/rec-3.ogg"),
    )

    @Test fun egressOff_readsZeroBytes_andNeverPuts() = runBlocking {
        val sink = SpySink()
        val reader = CountingReader()
        val engine = SyncEngine(SyncGate(egress(false), entitled), reader, passthrough, sink)

        val outcomes = engine.syncAll(pending)

        assertEquals(0, sink.putCount)          // the sink was never called
        assertEquals(0, reader.readCount)        // the file was never opened
        assertEquals(0L, reader.bytesRead)       // ZERO bytes left the device
        assertTrue(outcomes.all { it is SyncOutcome.Blocked })
        assertTrue(outcomes.all { (it as SyncOutcome.Blocked).reason == SyncBlockReason.EGRESS_OFF })
    }

    @Test fun egressOn_withPro_putsOncePerRecording() = runBlocking {
        val sink = SpySink()
        val reader = CountingReader()
        val engine = SyncEngine(SyncGate(egress(true), entitled), reader, passthrough, sink)

        val outcomes = engine.syncAll(pending)

        assertEquals(pending.size, sink.putCount)   // exactly once per pending recording
        assertEquals(pending.size, reader.readCount)
        assertTrue(outcomes.all { it is SyncOutcome.Synced })
    }

    @Test fun egressOn_butNotPro_isBlocked_andReadsNothing() = runBlocking {
        val sink = SpySink()
        val reader = CountingReader()
        val engine = SyncEngine(SyncGate(egress(true), notEntitled), reader, passthrough, sink)

        val outcomes = engine.syncAll(pending)

        assertEquals(0, sink.putCount)
        assertEquals(0L, reader.bytesRead)
        assertTrue(outcomes.all { (it as SyncOutcome.Blocked).reason == SyncBlockReason.NOT_ENTITLED })
    }

    @Test fun egressOff_reportsEgress_evenWhenAlsoNotPro() {
        // Egress is the honest primary reason in Phase 8 — checked first.
        val gate = SyncGate(egress(false), notEntitled)
        assertEquals(SyncBlockReason.EGRESS_OFF, gate.blockReason())
    }
}
