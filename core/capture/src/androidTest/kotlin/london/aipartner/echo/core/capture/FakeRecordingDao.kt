package london.aipartner.echo.core.capture

import london.aipartner.echo.core.data.ConsentRecordDao
import london.aipartner.echo.core.data.ConsentRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import london.aipartner.echo.core.data.RecordingDao
import london.aipartner.echo.core.data.RecordingEntity
import london.aipartner.echo.core.data.TranscriptionStatus

/** In-memory daos so capture tests don't need Room/SQLCipher wired up. */
class FakeRecordingDao : RecordingDao {
    val inserted = mutableListOf<RecordingEntity>()

    override suspend fun insert(recording: RecordingEntity) { inserted += recording }
    override suspend fun getById(id: String): RecordingEntity? = inserted.find { it.id == id }
    override fun observeById(id: String): Flow<RecordingEntity?> =
        flowOf(inserted.find { it.id == id })
    override suspend fun getAll(): List<RecordingEntity> = inserted.toList()
    override suspend fun deleteById(id: String) { inserted.removeAll { it.id == id } }
    override suspend fun updateTranscriptionStatus(id: String, status: String) {
        val i = inserted.indexOfFirst { it.id == id }
        if (i >= 0) inserted[i] = inserted[i].copy(transcriptionStatus = status)
    }
    override suspend fun updateDetectedLanguage(id: String, tag: String?) {
        val i = inserted.indexOfFirst { it.id == id }
        if (i >= 0) inserted[i] = inserted[i].copy(detectedLanguageTag = tag)
    }
    override suspend fun failOrphanedRunningTranscriptions(): Int {
        var n = 0
        inserted.indices.forEach { i ->
            if (inserted[i].transcriptionStatus == TranscriptionStatus.RUNNING.name) {
                inserted[i] = inserted[i].copy(transcriptionStatus = TranscriptionStatus.FAILED.name)
                n++
            }
        }
        return n
    }
}

class FakeConsentRecordDao : ConsentRecordDao {
    val inserted = mutableListOf<ConsentRecordEntity>()

    override suspend fun insert(record: ConsentRecordEntity) { inserted += record }
    override suspend fun forRecording(recordingId: String): ConsentRecordEntity? =
        inserted.find { it.recordingId == recordingId }
}
