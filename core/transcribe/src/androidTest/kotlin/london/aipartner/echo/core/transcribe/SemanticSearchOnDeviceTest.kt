package london.aipartner.echo.core.transcribe

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.test.runTest
import london.aipartner.echo.core.data.EmbeddingDao
import london.aipartner.echo.core.data.EmbeddingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that semantic search actually works with the **real bundled
 * MediaPipe model**, fully offline, and ranks by *relevance* — not just returns
 * non-empty. Run with the network off (the embedder makes no network calls by
 * construction; this asserts the retrieval quality the JVM test can only fake).
 *
 * Uses an in-memory [EmbeddingDao] to isolate the embedder+ranking from Room; the
 * encrypted-DB lifecycle is covered separately by `EmbeddingMigrationTest` and the
 * `:core:data` suite.
 */
@RunWith(AndroidJUnit4::class)
class SemanticSearchOnDeviceTest {

    private class FakeEmbeddingDao : EmbeddingDao {
        val rows = linkedMapOf<String, EmbeddingEntity>()
        override suspend fun upsert(embedding: EmbeddingEntity) { rows[embedding.recordingId] = embedding }
        override suspend fun all() = rows.values.toList()
        override suspend fun forRecording(recordingId: String) = rows[recordingId]
        override suspend fun deleteForRecording(recordingId: String) { rows.remove(recordingId) }
        override suspend fun count() = rows.size
    }

    private fun transcript(text: String) =
        Transcript(Locus.ON_DEVICE, "en", listOf(TranscriptSegment(text, 0, 1000)))

    @Test fun realModel_ranksSemanticallyRelevantRecordingFirst() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val index = MediaPipeSemanticIndex(MediaPipeTextEmbedder(context), FakeEmbeddingDao())

        index.index("dentist", transcript(
            "I called the surgery to reschedule my dental check-up after the toothache.",
        ))
        index.index("cooking", transcript(
            "We simmered the tomato sauce with garlic and basil for the pasta.",
        ))
        index.index("finance", transcript(
            "The client still has not paid last quarter's invoice; chase the account.",
        ))

        // A paraphrase that shares almost no literal words with the dental transcript —
        // semantic embeddings (not keyword match) should still rank it first.
        val hits = index.query("when is my appointment to see the tooth doctor", k = 3)

        assertEquals(3, hits.size)
        assertEquals("semantically-relevant recording must rank first", "dentist", hits.first().recordingId)
        assertTrue("top hit must out-score the next", hits[0].score > hits[1].score)
    }
}
