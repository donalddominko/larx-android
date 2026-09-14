package london.aipartner.echo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import london.aipartner.echo.core.transcribe.MediaPipeTextEmbedder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ★ RELEASE-VARIANT R8 SMOKE (2026-07-19). Runs the REAL MediaPipe text embedder end-to-end.
 *
 * Build with `-PreleaseTest` so the app + this test are the MINIFIED release variant, then this
 * exercises the actual R8 output. Semantic search silently indexed NOTHING once because R8
 * obfuscated MediaPipe's protobuf classes and the embedder threw at init ("Field platform_ for
 * <Class> not found") — swallowed by the best-effort index() catch, so no test or gate saw it
 * (the whole suite runs unminified). This is the guard: if the embedder is broken under R8, this
 * FAILS loudly. It's the semantic-search analog of verify-release-jni-classes.
 *
 * Runs on a physical arm64 device (real TFLite native + the bundled Universal Sentence Encoder
 * asset). Offline — the embedder makes no network calls.
 */
@RunWith(AndroidJUnit4::class)
class SemanticEmbedReleaseSmokeTest {

    @Test
    fun realEmbedder_producesUsableVector_underMinification() = runBlocking {
        // targetContext = the app under test (release, minified) — its assets carry the USE model.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val embedder = MediaPipeTextEmbedder(ctx)

        // If R8 broke MediaPipe's protobuf reflection, createFromOptions/embed throws HERE.
        val a = embedder.embed("the quarterly budget review meeting notes")
        val b = embedder.embed("financial planning discussion for Q3")
        val far = embedder.embed("a recipe for chocolate chip cookies")

        assertTrue("embedder returned an empty vector — model/init failed under R8", a.isNotEmpty())
        assertTrue("second embed empty", b.isNotEmpty())
        assertTrue("dims must match across embeds", a.size == b.size && a.size == far.size)

        // Sanity: semantically-related text should be closer than unrelated text. This proves the
        // vectors are real embeddings, not zero/garbage that happens to be non-empty.
        fun cos(x: FloatArray, y: FloatArray): Double {
            var dot = 0.0; var nx = 0.0; var ny = 0.0
            for (i in x.indices) { dot += x[i] * y[i]; nx += x[i] * x[i]; ny += y[i] * y[i] }
            return if (nx == 0.0 || ny == 0.0) 0.0 else dot / (Math.sqrt(nx) * Math.sqrt(ny))
        }
        val near = cos(a, b)
        val farSim = cos(a, far)
        android.util.Log.i("EchoEmbedSmoke", "cos(related)=$near cos(unrelated)=$farSim (dim=${a.size})")
        assertTrue("related=$near should exceed unrelated=$farSim — embeddings look degenerate", near > farSim)
    }
}
