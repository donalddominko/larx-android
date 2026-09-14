package london.aipartner.echo.core.transcribe

import kotlinx.coroutines.test.runTest
import london.aipartner.echo.core.data.AiArtifactDao
import london.aipartner.echo.core.data.AiArtifactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The writer is the persistence chokepoint for the honest-degradation rule: only a
 * successful [AiArtifactResult.Generated] is ever written. Failed / nothing-to-
 * generate persist NOTHING — there is no code path that stores fabricated or
 * placeholder content. A regenerate REPLACEs the same-kind artifact (stable id).
 */
class AiArtifactWriterTest {

    private class FakeDao : AiArtifactDao {
        val rows = mutableMapOf<String, AiArtifactEntity>()
        override suspend fun upsert(artifact: AiArtifactEntity) { rows[artifact.id] = artifact }
        override suspend fun forRecording(recordingId: String) =
            rows.values.filter { it.recordingId == recordingId }
    }

    private fun writer(dao: AiArtifactDao) = AiArtifactWriter(dao, clock = { 42L })

    @Test fun generated_isPersistedWithProvenance() = runTest {
        val dao = FakeDao()
        val id = writer(dao).persist(
            "rec1",
            AiArtifactResult.Generated(AiKind.SUMMARY, "they shipped", "model-x"),
        )
        assertEquals("rec1:SUMMARY", id)
        val row = dao.rows.getValue("rec1:SUMMARY")
        assertEquals("SUMMARY", row.kind)
        assertEquals("they shipped", row.content)
        assertEquals("model-x", row.model)
    }

    @Test fun failed_persistsNothing() = runTest {
        val dao = FakeDao()
        val id = writer(dao).persist("rec1", AiArtifactResult.Failed("network down"))
        assertNull(id)
        assertTrue("a failed generation must write no artifact", dao.rows.isEmpty())
    }

    @Test fun nothingToGenerate_persistsNothing() = runTest {
        val dao = FakeDao()
        val id = writer(dao).persist("rec1", AiArtifactResult.NothingToGenerate)
        assertNull(id)
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun regenerate_replacesSameKind_doesNotDuplicate() = runTest {
        val dao = FakeDao()
        val w = writer(dao)
        w.persist("rec1", AiArtifactResult.Generated(AiKind.SUMMARY, "v1", "model-x"))
        w.persist("rec1", AiArtifactResult.Generated(AiKind.SUMMARY, "v2", "model-y"))
        assertEquals(1, dao.forRecording("rec1").size)
        assertEquals("v2", dao.rows.getValue("rec1:SUMMARY").content)
    }
}
