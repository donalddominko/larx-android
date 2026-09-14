package london.aipartner.echo.core.transcribe

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder

/**
 * The on-device [Embedder] — Google's MediaPipe Text Embedder running the bundled
 * Universal Sentence Encoder model from assets. Fully on-device: **no network, no
 * keys, zero egress.** Takes only a [Context] (to load the asset) — never
 * `EgressConsent`/`Entitlements`.
 *
 * The native embedder is created lazily and reused (creation loads the TFLite
 * model, which is not free). Loading from `setModelAssetPath` reads the APK-bundled
 * asset directly; no file extraction needed.
 */
class MediaPipeTextEmbedder(
    context: Context,
    private val assetName: String = MODEL_ASSET,
) : Embedder {

    private val appContext = context.applicationContext
    override val model = "mediapipe-use-1"

    private val delegate: TextEmbedder by lazy {
        val base = BaseOptions.builder().setModelAssetPath(assetName).build()
        val options = TextEmbedder.TextEmbedderOptions.builder()
            .setBaseOptions(base)
            .setQuantize(false)
            .build()
        TextEmbedder.createFromOptions(appContext, options)
    }

    override suspend fun embed(text: String): FloatArray {
        val result = delegate.embed(text)
        val embedding = result.embeddingResult().embeddings().firstOrNull()
            ?: return FloatArray(0)
        return embedding.floatEmbedding()
    }

    companion object {
        const val MODEL_ASSET = "universal_sentence_encoder.tflite"
    }
}
