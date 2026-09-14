package london.aipartner.echo.core.transcribe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import london.aipartner.echo.core.data.RecordingTranscriptText
import london.aipartner.echo.core.data.TranscriptDao
import london.aipartner.echo.core.data.TranscriptRevisionEntity
import london.aipartner.echo.core.data.TranscriptSegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TranscriptWriter must leave a recording with **exactly one** rev-0 machine revision no matter how
 * many times transcription runs — the fix for the force-close recovery data bug (a re-run appended a
 * SECOND full rev-0, doubling search text + double semantic indexing). Idempotency is by
 * (recordingId, rev), enforced in `replaceMachineRevision` (delete-then-insert in a transaction),
 * because the revision PK is a random UUID and can never dedup on its own.
 */
class TranscriptWriterTest {

    /** In-memory TranscriptDao that honors the segment→revision FK CASCADE on revision delete, so
     *  the inherited [TranscriptDao.replaceMachineRevision] default runs exactly as on-device. */
    private class FakeDao : TranscriptDao {
        val revisions = mutableListOf<TranscriptRevisionEntity>()
        val segments = mutableListOf<TranscriptSegmentEntity>()

        override suspend fun insertRevision(revision: TranscriptRevisionEntity) { revisions += revision }
        override suspend fun insertSegments(segments: List<TranscriptSegmentEntity>) { this.segments += segments }
        override suspend fun deleteRevisionsAtRev(recordingId: String, rev: Int) {
            val gone = revisions.filter { it.recordingId == recordingId && it.rev == rev }
            revisions.removeAll(gone)
            val goneIds = gone.map { it.id }.toSet()
            segments.removeAll { it.revisionId in goneIds } // FK CASCADE
        }
        override suspend fun revisionsFor(recordingId: String) =
            revisions.filter { it.recordingId == recordingId }.sortedBy { it.rev }
        override fun observeRevisions(recordingId: String): Flow<List<TranscriptRevisionEntity>> =
            flowOf(revisions.filter { it.recordingId == recordingId })
        override suspend fun segmentsFor(revisionId: String) =
            segments.filter { it.revisionId == revisionId }.sortedBy { it.orderIdx }
        override suspend fun latestTranscriptTexts(): List<RecordingTranscriptText> = emptyList()
    }

    private fun transcript(vararg texts: String) = Transcript(
        locus = Locus.ON_DEVICE,
        languageTag = "en",
        segments = texts.mapIndexed { i, t ->
            TranscriptSegment(text = t, tStartMs = i * 1000L, tEndMs = i * 1000L + 900L)
        },
    )

    @Test fun rerunLeavesExactlyOneRev0_notTwo() = runTest {
        val dao = FakeDao()
        val writer = TranscriptWriter(dao)

        writer.writeMachineRevision("rec1", transcript("hello", "world"))
        // Recovery re-run (force-close after write, before DONE) transcribes the same audio again.
        writer.writeMachineRevision("rec1", transcript("hello", "world"))

        val rev0s = dao.revisions.filter { it.recordingId == "rec1" && it.rev == 0 }
        assertEquals("exactly one rev-0 after a recovery re-run", 1, rev0s.size)
        // And exactly one revision's worth of segments — not doubled.
        assertEquals("segments belong to the single surviving rev-0, not doubled", 2, dao.segments.size)
        assertEquals(rev0s.single().id, dao.segments.first().revisionId)
    }

    @Test fun rerunReplacesContent_withLatestTranscript() = runTest {
        val dao = FakeDao()
        val writer = TranscriptWriter(dao)
        writer.writeMachineRevision("rec1", transcript("first pass"))
        writer.writeMachineRevision("rec1", transcript("second pass", "extra"))

        assertEquals(1, dao.revisions.count { it.recordingId == "rec1" && it.rev == 0 })
        assertEquals(listOf("second pass", "extra"), dao.segments.map { it.text })
    }

    @Test fun doesNotTouchOtherRecordings() = runTest {
        val dao = FakeDao()
        val writer = TranscriptWriter(dao)
        writer.writeMachineRevision("recA", transcript("a"))
        writer.writeMachineRevision("recB", transcript("b"))
        writer.writeMachineRevision("recA", transcript("a2")) // re-run A only

        assertEquals(1, dao.revisions.count { it.recordingId == "recA" && it.rev == 0 })
        assertEquals(1, dao.revisions.count { it.recordingId == "recB" && it.rev == 0 })
        assertEquals(setOf("a2", "b"), dao.segments.map { it.text }.toSet())
    }
}
