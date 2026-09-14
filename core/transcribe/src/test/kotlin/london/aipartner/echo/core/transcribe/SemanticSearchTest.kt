package london.aipartner.echo.core.transcribe

import kotlinx.coroutines.test.runTest
import london.aipartner.echo.core.data.EmbeddingDao
import london.aipartner.echo.core.data.EmbeddingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relevance, not just non-emptiness: with a deterministic bag-of-words embedder the
 * index must rank the genuinely-relevant recording **first**, and the cosine math /
 * BLOB round-trip must be correct. Pure JVM (fake embedder + fake DAO); the real
 * MediaPipe model + offline guarantee are exercised by the on-device suite.
 */
class SemanticSearchTest {

    /** Deterministic, order-insensitive bag-of-words embedding over a fixed vocab. */
    private class BagOfWordsEmbedder(private val vocab: List<String>) : Embedder {
        override val model = "bow-test-1"
        override suspend fun embed(text: String): FloatArray {
            val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }
            return FloatArray(vocab.size) { i -> words.count { it == vocab[i] }.toFloat() }
        }
    }

    private class FakeEmbeddingDao : EmbeddingDao {
        val rows = linkedMapOf<String, EmbeddingEntity>()
        override suspend fun upsert(embedding: EmbeddingEntity) { rows[embedding.recordingId] = embedding }
        override suspend fun all() = rows.values.toList()
        override suspend fun forRecording(recordingId: String) = rows[recordingId]
        override suspend fun deleteForRecording(recordingId: String) { rows.remove(recordingId) }
        override suspend fun count() = rows.size
    }

    private val vocab = listOf("dentist", "tooth", "appointment", "pasta", "garlic", "recipe", "budget", "invoice")

    private fun transcript(text: String) =
        Transcript(Locus.ON_DEVICE, "en", listOf(TranscriptSegment(text, 0, 1000)))

    private fun index(dao: EmbeddingDao) =
        MediaPipeSemanticIndex(BagOfWordsEmbedder(vocab), dao, clock = { 0L })

    @Test fun ranksTheRelevantRecordingFirst() = runTest {
        val dao = FakeEmbeddingDao()
        val idx = index(dao)
        idx.index("dental", transcript("booked a dentist appointment for a sore tooth"))
        idx.index("cooking", transcript("a garlic pasta recipe for dinner"))
        idx.index("finance", transcript("the quarterly budget and an unpaid invoice"))

        val hits = idx.query("when is my tooth dentist appointment", k = 3)

        assertEquals("all recordings scored", 3, hits.size)
        assertEquals("the dental recording must rank first", "dental", hits.first().recordingId)
        assertTrue("top hit must out-score the rest", hits[0].score > hits[1].score)
    }

    @Test fun indexingNoSpeechStoresNothing_andRemovesStale() = runTest {
        val dao = FakeEmbeddingDao()
        val idx = index(dao)
        idx.index("rec1", transcript("garlic pasta recipe"))
        assertEquals(1, dao.count())
        // Re-indexing the same recording with an empty transcript drops the stale vector.
        idx.index("rec1", Transcript(Locus.ON_DEVICE, "en", emptyList(), noSpeechDetected = true))
        assertEquals(0, dao.count())
    }

    @Test fun emptyIndexOrBlankQuery_returnsNoHits() = runTest {
        val dao = FakeEmbeddingDao()
        val idx = index(dao)
        assertTrue(idx.query("anything", 5).isEmpty())          // nothing indexed
        idx.index("rec1", transcript("garlic pasta recipe"))
        assertTrue(idx.query("   ", 5).isEmpty())               // blank query
    }

    @Test fun blobRoundTrip_preservesVector() {
        val v = floatArrayOf(0.5f, -1.25f, 3.0f, 0f)
        assertTrue(v.toBytes().toFloats().contentEquals(v))
    }
}
