package london.aipartner.echo.transcribe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import london.aipartner.echo.core.data.EmbeddingDao
import london.aipartner.echo.core.data.EmbeddingEntity
import london.aipartner.echo.core.data.RecordingDao
import london.aipartner.echo.core.data.RecordingEntity
import london.aipartner.echo.core.data.TranscriptDao
import london.aipartner.echo.core.data.TranscriptRevisionEntity
import london.aipartner.echo.core.data.TranscriptSegmentEntity
import london.aipartner.echo.core.data.TranscriptionStatus
import london.aipartner.echo.core.transcribe.SearchHit
import london.aipartner.echo.core.transcribe.SemanticIndex
import london.aipartner.echo.core.transcribe.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit spec for the self-healing [SemanticBackfill] pass. Device-independent — pure logic over
 * fake DAOs + a recording [SemanticIndex]. Covers: a DONE recording with segments but no
 * embedding is indexed; an already-current-model one is skipped; a zero-segment (no-speech) one
 * is skipped WITHOUT touching the embedder (and so can't thrash); a non-DONE one is ignored; a
 * different-model embedding is re-indexed (migration); and the pass no-ops once caught up.
 */
class SemanticBackfillTest {

    private val model = "mediapipe-use-1"

    // ── Fakes ────────────────────────────────────────────────────────────────────────────────
    private class FakeIndex(override val model: String) : SemanticIndex {
        val indexed = mutableListOf<String>()
        override suspend fun index(recordingId: String, transcript: Transcript) {
            indexed += recordingId
        }
        override suspend fun query(query: String, k: Int): List<SearchHit> = emptyList()
    }

    private class FakeEmbeddingDao(rows: List<EmbeddingEntity>) : EmbeddingDao {
        private val store = rows.associateBy { it.recordingId }.toMutableMap()
        override suspend fun upsert(embedding: EmbeddingEntity) { store[embedding.recordingId] = embedding }
        override suspend fun all(): List<EmbeddingEntity> = store.values.toList()
        override suspend fun forRecording(recordingId: String): EmbeddingEntity? = store[recordingId]
        override suspend fun deleteForRecording(recordingId: String) { store.remove(recordingId) }
        override suspend fun count(): Int = store.size
    }

    private class FakeTranscriptDao(
        private val revisions: Map<String, List<TranscriptRevisionEntity>>,
        private val segments: Map<String, List<TranscriptSegmentEntity>>,
    ) : TranscriptDao {
        override suspend fun revisionsFor(recordingId: String) = revisions[recordingId].orEmpty()
        override suspend fun segmentsFor(revisionId: String) = segments[revisionId].orEmpty()
        override suspend fun insertRevision(revision: TranscriptRevisionEntity) = Unit
        override suspend fun insertSegments(segments: List<TranscriptSegmentEntity>) = Unit
        override suspend fun deleteRevisionsAtRev(recordingId: String, rev: Int) = Unit
        override fun observeRevisions(recordingId: String): Flow<List<TranscriptRevisionEntity>> = TODO()
        override suspend fun latestTranscriptTexts() = TODO()
    }

    private class FakeRecordingDao(private val rows: List<RecordingEntity>) : RecordingDao {
        override suspend fun getAll(): List<RecordingEntity> = rows
        override suspend fun insert(recording: RecordingEntity) = TODO()
        override suspend fun getById(id: String): RecordingEntity? = rows.firstOrNull { it.id == id }
        override fun observeById(id: String): Flow<RecordingEntity?> = TODO()
        override suspend fun deleteById(id: String) = TODO()
        override suspend fun updateTranscriptionStatus(id: String, status: String) = TODO()
        override suspend fun updateUserTitle(id: String, title: String?) = TODO()
        override suspend fun updateDetectedLanguage(id: String, tag: String?) = TODO()
        override suspend fun incrementTranscriptionAttempts(id: String) = TODO()
        override suspend fun getTranscriptionAttempts(id: String): Int = TODO()
        override suspend fun resetTranscriptionAttempts(id: String) = TODO()
        override suspend fun failOrphanedRunningTranscriptions(): Int = TODO()
    }

    // ── Builders ─────────────────────────────────────────────────────────────────────────────
    private fun recording(id: String, status: TranscriptionStatus) = RecordingEntity(
        id = id, createdAt = 0, durationMs = 1000, captureSource = "MIC", fidelity = "ONE",
        captureMode = "MEMO", contactHash = null, localAudioRef = "/x", encryptionMeta = null,
        syncState = "LOCAL_ONLY", transcriptionStatus = status.name,
    )

    private fun rev(id: String, recordingId: String, r: Int = 0) =
        TranscriptRevisionEntity(id = id, recordingId = recordingId, rev = r, locus = "ON_DEVICE", languageTag = "en", createdAt = 0)

    private fun seg(revId: String, text: String) =
        TranscriptSegmentEntity(id = "$revId-s", revisionId = revId, orderIdx = 0, text = text, tStartMs = 0, tEndMs = 500, speaker = null)

    private fun embedding(recordingId: String, model: String) =
        EmbeddingEntity(recordingId = recordingId, model = model, dim = 1, vector = ByteArray(4), createdAt = 0)

    private fun backfill(
        recordings: List<RecordingEntity>,
        revisions: Map<String, List<TranscriptRevisionEntity>>,
        segments: Map<String, List<TranscriptSegmentEntity>>,
        embeddings: List<EmbeddingEntity>,
        index: FakeIndex,
    ) = SemanticBackfill(
        recordingDao = FakeRecordingDao(recordings),
        transcriptDao = FakeTranscriptDao(revisions, segments),
        embeddingDao = FakeEmbeddingDao(embeddings),
        semanticIndex = index,
    )

    // ── Tests ────────────────────────────────────────────────────────────────────────────────
    @Test fun doneWithSegmentsButNoEmbedding_isIndexed() = runBlocking {
        val idx = FakeIndex(model)
        val n = backfill(
            recordings = listOf(recording("r1", TranscriptionStatus.DONE)),
            revisions = mapOf("r1" to listOf(rev("v1", "r1"))),
            segments = mapOf("v1" to listOf(seg("v1", "tomato"))),
            embeddings = emptyList(),
            index = idx,
        ).run()

        assertEquals(1, n)
        assertEquals(listOf("r1"), idx.indexed)
    }

    @Test fun alreadyIndexedWithCurrentModel_isSkipped() = runBlocking {
        val idx = FakeIndex(model)
        val n = backfill(
            recordings = listOf(recording("r1", TranscriptionStatus.DONE)),
            revisions = mapOf("r1" to listOf(rev("v1", "r1"))),
            segments = mapOf("v1" to listOf(seg("v1", "tomato"))),
            embeddings = listOf(embedding("r1", model)),
            index = idx,
        ).run()

        assertEquals(0, n)
        assertTrue(idx.indexed.isEmpty())
    }

    @Test fun zeroSegmentNoSpeech_isSkipped_withoutTouchingEmbedder() = runBlocking {
        val idx = FakeIndex(model)
        val n = backfill(
            recordings = listOf(recording("r1", TranscriptionStatus.DONE)),
            revisions = mapOf("r1" to listOf(rev("v1", "r1"))),
            segments = mapOf("v1" to emptyList()), // no-speech: rev 0 exists, zero segments
            embeddings = emptyList(),
            index = idx,
        ).run()

        assertEquals(0, n)
        assertTrue("embedder must not be invoked for no-speech", idx.indexed.isEmpty())
    }

    @Test fun nonDoneRecording_isIgnored() = runBlocking {
        val idx = FakeIndex(model)
        val n = backfill(
            recordings = listOf(
                recording("r1", TranscriptionStatus.FAILED),
                recording("r2", TranscriptionStatus.RUNNING),
                recording("r3", TranscriptionStatus.PENDING),
            ),
            revisions = mapOf("r1" to listOf(rev("v1", "r1"))),
            segments = mapOf("v1" to listOf(seg("v1", "tomato"))),
            embeddings = emptyList(),
            index = idx,
        ).run()

        assertEquals(0, n)
        assertTrue(idx.indexed.isEmpty())
    }

    @Test fun differentModelEmbedding_isReindexed() = runBlocking {
        val idx = FakeIndex(model)
        val n = backfill(
            recordings = listOf(recording("r1", TranscriptionStatus.DONE)),
            revisions = mapOf("r1" to listOf(rev("v1", "r1"))),
            segments = mapOf("v1" to listOf(seg("v1", "tomato"))),
            embeddings = listOf(embedding("r1", "old-model-v0")),
            index = idx,
        ).run()

        assertEquals(1, n)
        assertEquals(listOf("r1"), idx.indexed)
    }

    @Test fun usesLatestRevisionSegments() = runBlocking {
        val idx = FakeIndex(model)
        // Two revisions; the latest (rev 1) is the one that must be embedded.
        val n = backfill(
            recordings = listOf(recording("r1", TranscriptionStatus.DONE)),
            revisions = mapOf("r1" to listOf(rev("v0", "r1", 0), rev("v1", "r1", 1))),
            segments = mapOf("v0" to emptyList(), "v1" to listOf(seg("v1", "edited")),),
            embeddings = emptyList(),
            index = idx,
        ).run()

        assertEquals(1, n)
        assertEquals(listOf("r1"), idx.indexed)
    }

    @Test fun caughtUpLibrary_noOps() = runBlocking {
        val idx = FakeIndex(model)
        val n = backfill(
            recordings = listOf(
                recording("r1", TranscriptionStatus.DONE),
                recording("r2", TranscriptionStatus.DONE),
            ),
            revisions = mapOf("r1" to listOf(rev("v1", "r1")), "r2" to listOf(rev("v2", "r2"))),
            segments = mapOf("v1" to listOf(seg("v1", "a")), "v2" to listOf(seg("v2", "b"))),
            embeddings = listOf(embedding("r1", model), embedding("r2", model)),
            index = idx,
        ).run()

        assertEquals(0, n)
        assertTrue(idx.indexed.isEmpty())
    }
}
