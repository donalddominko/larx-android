package london.aipartner.echo.core.capture

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import london.aipartner.echo.core.consent.ConsentGate
import london.aipartner.echo.core.data.ConsentRecordDao
import london.aipartner.echo.core.data.RecordingDao

/**
 * Provides the real Phase 2 capture wiring. [ConsentGate] is bound by the app
 * seams module, [RecordingDao] by `:core:data` — this module assembles them into
 * the resolver + controller.
 */
@Module
@InstallIn(SingletonComponent::class)
object CaptureModule {

    @Provides @Singleton
    fun micRecorder(
        @ApplicationContext context: Context,
        capturePreferences: CapturePreferences,
    ) = MicRecorder(context, gainProvider = { capturePreferences.micGain })

    @Provides @Singleton
    fun resolver(mic: MicRecorder): CaptureSourceResolver = RealCaptureSourceResolver(mic)

    @Provides @Singleton
    fun audioEncryptor(@ApplicationContext context: Context) = AudioEncryptor(context)

    @Provides @Singleton
    fun recoveryStore(@ApplicationContext context: Context) = RecordingRecoveryStore(context)

    @Provides @Singleton
    fun capturePreferences(@ApplicationContext context: Context) = CapturePreferences(context)

    @Provides @Singleton
    fun recorderController(
        @ApplicationContext context: Context,
        resolver: CaptureSourceResolver,
        consentGate: ConsentGate,
        encryptor: AudioEncryptor,
        recordingDao: RecordingDao,
        consentRecordDao: ConsentRecordDao,
        recoveryStore: RecordingRecoveryStore,
    ) = RecorderController(
        context, resolver, consentGate, encryptor, recordingDao, consentRecordDao,
        recoveryStore,
    )
}
