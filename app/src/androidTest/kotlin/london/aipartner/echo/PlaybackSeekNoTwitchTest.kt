package london.aipartner.echo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import london.aipartner.echo.playback.PlaybackController
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6 — Issue-2 gate: tapping a transcript line seeks straight to it with **no
 * intermediate flash of the previous segment**.
 *
 * The karaoke highlight is a pure function of `PlaybackController.positionMs`, so the
 * visible twitch == `positionMs` momentarily regressing to the pre-seek value while
 * `MediaPlayer`'s async seek is in flight (the ticker publishing the stale
 * `currentPosition`). This drives the REAL controller with a real audio file on the A03,
 * does a forward seek WHILE PLAYING, and asserts `positionMs` never drops back below the
 * seek target — i.e. the highlight can never flash an earlier line.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackSeekNoTwitchTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var wav: File
    private lateinit var controller: PlaybackController
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun setUp() {
        wav = File.createTempFile("seek-tone", ".wav", context.cacheDir)
        writeSineWav(wav, seconds = 6, sampleRate = 44_100, freqHz = 440.0)
        // MediaPlayer delivers prepared/seek-complete callbacks on the thread that created
        // it, so the controller must be created and driven on the main looper.
        instrumentation.runOnMainSync { controller = PlaybackController() }
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { controller.dispose() }
        wav.delete()
    }

    @Test fun forwardSeekWhilePlaying_neverRegressesBelowTarget() {
        instrumentation.runOnMainSync { controller.load(wav) }
        await("player ready") { controller.ready.value }

        instrumentation.runOnMainSync { controller.playPause() }
        await("playback advanced") { controller.positionMs.value > 500 }

        val targetMs = 4_000L
        instrumentation.runOnMainSync { controller.seekTo(targetMs) }

        // Sample across the whole seek-settle window. Without the guard the ticker would
        // publish the stale ~pre-seek position here, dropping far below the target.
        val floor = targetMs - 250 // allow seek snap tolerance; the bug regresses by seconds
        var minSeen = Long.MAX_VALUE
        repeat(24) {
            val pos = controller.positionMs.value
            minSeen = minOf(minSeen, pos)
            assertTrue(
                "position regressed to $pos after seeking to $targetMs (highlight would flash an earlier line)",
                pos >= floor,
            )
            Thread.sleep(25)
        }
        // Sanity: we actually observed positions in the window (not a no-op).
        assertTrue("expected to observe post-seek positions", minSeen >= floor && minSeen != Long.MAX_VALUE)
    }

    private fun await(what: String, timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(20)
        }
    }

    /** Minimal mono 16-bit PCM WAV so a real MediaPlayer can prepare/seek/play it. */
    private fun writeSineWav(file: File, seconds: Int, sampleRate: Int, freqHz: Double) {
        val numSamples = seconds * sampleRate
        val dataBytes = numSamples * 2
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            fun writeLeInt(v: Int) = raf.write(byteArrayOf(
                (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
                ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte(),
            ))
            fun writeLeShort(v: Int) = raf.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()))
            raf.writeBytes("RIFF"); writeLeInt(36 + dataBytes); raf.writeBytes("WAVE")
            raf.writeBytes("fmt "); writeLeInt(16); writeLeShort(1); writeLeShort(1)
            writeLeInt(sampleRate); writeLeInt(sampleRate * 2); writeLeShort(2); writeLeShort(16)
            raf.writeBytes("data"); writeLeInt(dataBytes)
            for (i in 0 until numSamples) {
                val sample = (Math.sin(2.0 * Math.PI * freqHz * i / sampleRate) * 0.4 * Short.MAX_VALUE).toInt()
                writeLeShort(sample)
            }
        }
    }
}
