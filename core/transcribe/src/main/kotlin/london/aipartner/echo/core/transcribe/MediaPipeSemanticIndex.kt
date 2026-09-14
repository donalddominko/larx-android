package london.aipartner.echo.core.transcribe

import london.aipartner.echo.core.data.EmbeddingDao
import london.aipartner.echo.core.data.EmbeddingEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * On-device semantic search (Phase 5). Free, offline, **zero egress by
 * construction** — its constructor takes only an [Embedder] and an [EmbeddingDao],
 * never `EgressConsent`/`Entitlements` (asserted by `EgressDistinctionTest`).
 * Content never leaves the device.
 *
 * The embedding store is a **regenerable derivation, never source-of-truth**: the
 * whole `embeddings` table can be dropped and rebuilt from transcripts at any time.
 * Retrieval is **brute-force cosine** over all stored vectors — correct and simple
 * at v1 library scale (no vector DB). The ranking logic lives here (pure Kotlin),
 * so it is JVM-unit-testable with a fake embedder + fake DAO; only [MediaPipeText
 * Embedder] touches the native model.
 */
class MediaPipeSemanticIndex(
    private val embedder: Embedder,
    private val dao: EmbeddingDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SemanticIndex {

    /** The one native [TextEmbedder] handle is a single, reused, non-thread-safe resource. ALL
     *  embedder access — live indexing (transcription worker), the startup backfill worker, and
     *  query (UI) — is serialized through this so concurrent passes can't race the native context
     *  or contend for it. The critical section is only the `embed()` call; DB work is outside it. */
    private val embedderLock = Mutex()

    override val model: String get() = embedder.model

    override suspend fun index(recordingId: String, transcript: Transcript) {
        // Nothing was said ⇒ nothing to index. Honest: don't store a junk vector.
        val text = transcript.segments.joinToString(" ") { it.text }.trim()
        if (text.isEmpty()) {
            dao.deleteForRecording(recordingId)
            return
        }
        val vector = embedderLock.withLock { embedder.embed(text) }
        dao.upsert(
            EmbeddingEntity(
                recordingId = recordingId,
                model = embedder.model,
                dim = vector.size,
                vector = vector.toBytes(),
                createdAt = clock(),
            ),
        )
    }

    override suspend fun query(query: String, k: Int): List<SearchHit> {
        if (query.isBlank() || k <= 0) return emptyList()
        val stored = dao.all()
        if (stored.isEmpty()) return emptyList()
        val q = embedderLock.withLock { embedder.embed(query) }
        return stored
            .mapNotNull { row ->
                val v = row.vector.toFloats()
                // Skip dimension-mismatched (stale-model) rows rather than crash;
                // they will be re-indexed with the current model.
                if (v.size != q.size) null
                else SearchHit(row.recordingId, cosine(q, v))
            }
            .sortedByDescending { it.score }
            .take(k)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return dot / (sqrt(na) * sqrt(nb))
    }
}

/** Embedding ⇄ BLOB as little-endian float32 (matches `EmbeddingEntity.vector`). */
internal fun FloatArray.toBytes(): ByteArray {
    val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    forEach { buf.putFloat(it) }
    return buf.array()
}

internal fun ByteArray.toFloats(): FloatArray {
    val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { buf.float }
}
