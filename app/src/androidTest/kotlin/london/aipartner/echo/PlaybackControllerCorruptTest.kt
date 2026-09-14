package london.aipartner.echo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import london.aipartner.echo.playback.PlaybackController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for the delete-time crash (2026-06-26): opening a recording with a
 * **corrupt/empty audio file** crashed the app via an uncaught `IOException` from
 * `MediaPlayer.setDataSource` in [PlaybackController.load] — and because opening is the
 * only way to reach the delete button, broken recordings were undeletable. The gain
 * re-encode/cancellation churn produced such files (0/56/449-byte). `load()` must now
 * fail **gracefully** (surface `failed`, never throw), so the recording stays openable
 * and deletable. (The delete cascade itself is covered by `DeleteEverywhereTest`.)
 */
@RunWith(AndroidJUnit4::class)
class PlaybackControllerCorruptTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun load_emptyFile_failsGracefully_noCrash() {
        // 0-byte file: setDataSource throws synchronously — must be caught.
        val empty = File.createTempFile("empty_", ".m4a", context.cacheDir).also { it.writeBytes(ByteArray(0)) }
        assertGracefulFailure(empty)
    }

    @Test
    fun load_garbageFile_failsGracefully_noCrash() {
        // Non-audio bytes: fails either synchronously or async via onError — both handled.
        val garbage = File.createTempFile("garbage_", ".m4a", context.cacheDir)
            .also { it.writeBytes(ByteArray(512) { (it % 7).toByte() }) }
        assertGracefulFailure(garbage)
    }

    private fun assertGracefulFailure(file: File) {
        val controller = PlaybackController()
        // load() runs on the main thread (MediaPlayer callbacks need a Looper). If it
        // threw, runOnMainSync would propagate the exception and fail the test.
        instrumentation.runOnMainSync { controller.load(file) }

        val failed = waitForTrue(timeoutMs = 4000) { controller.failed.value }
        assertTrue("corrupt audio must surface as failed, never crash", failed)
        // It must not have reported itself as a ready, playable file.
        assertFalse("must not be marked ready", controller.ready.value)

        instrumentation.runOnMainSync { controller.dispose() }
        file.delete()
    }

    private inline fun waitForTrue(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }
}
