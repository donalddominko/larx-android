package london.aipartner.echo.core.data

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the encrypted database and its DAOs to the Hilt graph. The database
 * is a singleton; the passphrase is fetched from the Keystore-wrapped store at
 * build time and never held longer than needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): EchoDatabase {
        val passphrase = DbPassphraseProvider(context).getOrCreate()
        return EchoDatabase.build(context, passphrase)
    }

    @Provides fun recordingDao(db: EchoDatabase): RecordingDao = db.recordingDao()
    @Provides fun transcriptDao(db: EchoDatabase): TranscriptDao = db.transcriptDao()
    @Provides fun aiArtifactDao(db: EchoDatabase): AiArtifactDao = db.aiArtifactDao()
    @Provides fun embeddingDao(db: EchoDatabase): EmbeddingDao = db.embeddingDao()
    @Provides fun consentRecordDao(db: EchoDatabase): ConsentRecordDao = db.consentRecordDao()
    @Provides fun syncRefDao(db: EchoDatabase): SyncRefDao = db.syncRefDao()
}
