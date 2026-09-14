package london.aipartner.echo.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton
import london.aipartner.echo.BuildConfig
import london.aipartner.echo.core.billing.DevEntitlements
import london.aipartner.echo.core.billing.Entitlements
import london.aipartner.echo.core.consent.ConsentGate
import london.aipartner.echo.core.consent.ConsentPreferences
import london.aipartner.echo.core.consent.RecordAudioConsentGate
import london.aipartner.echo.core.sync.CloudSink
import london.aipartner.echo.core.sync.LocalOnlyCloudSink
import london.aipartner.echo.core.data.AiArtifactDao
import london.aipartner.echo.core.transcribe.AiArtifactWriter
import london.aipartner.echo.core.transcribe.AiEngine
import london.aipartner.echo.core.transcribe.CloudAiEngine
import london.aipartner.echo.core.transcribe.CloudTranscriber
import london.aipartner.echo.core.transcribe.EgressConsent
import london.aipartner.echo.core.transcribe.EnergyVad
import london.aipartner.echo.core.transcribe.MediaCodecPcmDecoder
import london.aipartner.echo.core.transcribe.ModelProvisioner
import london.aipartner.echo.core.transcribe.NativeWhisperEngine
import london.aipartner.echo.core.transcribe.NotConfiguredAsrProvider
import london.aipartner.echo.core.data.EmbeddingDao
import london.aipartner.echo.core.transcribe.MediaPipeSemanticIndex
import london.aipartner.echo.core.transcribe.MediaPipeTextEmbedder
import london.aipartner.echo.core.transcribe.NotConfiguredLlmProvider
import london.aipartner.echo.core.transcribe.OnDeviceTranscriber
import london.aipartner.echo.core.transcribe.SemanticIndex
import london.aipartner.echo.core.transcribe.Transcriber
import london.aipartner.echo.core.transcribe.TranscriberRouter

/**
 * Binds each of the five seams to its Phase 1 permissive stub. Every later phase
 * replaces a binding here (or moves it into its seam's module) without touching
 * the call sites that depend on the interface. Nothing reaches around a seam.
 *
 * NOTE: [Entitlements] is bound to [DevEntitlements] with Pro **off** — gating is
 * honest from day one. Phase 9 swaps this for real Play Billing state.
 *
 * The `CaptureSourceResolver` is provided by `:core:capture`'s real
 * `CaptureModule` (Phase 2).
 */
@Module
@InstallIn(SingletonComponent::class)
object SeamsModule {

    @Provides
    @Singleton
    fun consentGate(): ConsentGate = RecordAudioConsentGate()

    @Provides
    @Singleton
    fun consentPreferences(@ApplicationContext context: Context): ConsentPreferences =
        ConsentPreferences(context)

    /**
     * Phase 1 stub, still Pro-**off** in every shipping path. The only way `proUnlocked`
     * becomes true is a **debug build** whose `directDebug` verify harness set the flag —
     * `BuildConfig.DEBUG` short-circuits the read entirely in release, so release Pro can
     * never be flipped on here (Phase 9 replaces this with real Play Billing state).
     */
    @Provides
    @Singleton
    fun entitlements(@ApplicationContext context: Context): Entitlements =
        DevEntitlements(proUnlocked = BuildConfig.DEBUG && DebugFlags.proUnlocked(context))

    /**
     * Phase 4. The bound [Transcriber] is the [TranscriberRouter]: on-device by
     * default (offline, no keys, no network), cloud ONLY when the double gate
     * (Pro entitlement + egress consent) is open. The on-device engine's native
     * `libwhisper.so` (arm64) and the PAD `whisper_base` asset pack are assembled
     * in the release build — see PROGRESS.md "remaining native/Play work".
     */
    @Provides
    @Singleton
    fun modelProvisioner(@ApplicationContext context: Context): ModelProvisioner {
        // SHA-256 of ggml-base.bin (the on-device whisper `base` weights), verified
        // by Gate B B1 on 2026-06-25. The install-time asset pack must ship THIS exact file.
        val sha = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
        val modelFile = java.io.File(context.filesDir, "models/ggml-base.bin")
        // Local models dir first (fast path once copied out; also where a debug build can
        // be side-loaded via adb). Fallback copies the model out of the INSTALL-TIME asset
        // pack (:whisper_base_assets) to that path on first run — no download, no INTERNET.
        // (On-demand PlayAssetDeliveryModelProvisioner is reserved for the larger-model
        // ladder — see references/better-models-pro.md Decision 5-PRE.)
        return london.aipartner.echo.core.transcribe.FileSystemModelProvisioner(
            modelFile = modelFile,
            expectedSha256 = sha,
            fallback = london.aipartner.echo.core.transcribe.InstallTimeAssetModelProvisioner(
                openAsset = { context.assets.open("ggml-base.bin") },
                target = modelFile,
                expectedSha256 = sha,
            ),
        )
    }

    @Provides
    @Singleton
    fun transcriber(
        @ApplicationContext context: Context,
        entitlements: Entitlements,
        consentPreferences: ConsentPreferences,
        modelProvisioner: ModelProvisioner,
    ): Transcriber {
        val onDevice = OnDeviceTranscriber(
            decoder = MediaCodecPcmDecoder(),
            vad = EnergyVad(),
            engine = NativeWhisperEngine(),
            modelProvisioner = modelProvisioner,
        )
        val cloud = CloudTranscriber(
            entitlements = entitlements,
            egressConsent = EgressConsent { consentPreferences.dataEgressAllowed },
            provider = NotConfiguredAsrProvider(),
        )
        return TranscriberRouter(onDevice, cloud)
    }

    @Provides
    @Singleton
    fun transcriptWriter(
        transcriptDao: london.aipartner.echo.core.data.TranscriptDao,
    ): london.aipartner.echo.core.transcribe.TranscriptWriter =
        london.aipartner.echo.core.transcribe.TranscriptWriter(transcriptDao)

    /**
     * Phase 7 decouple (Donald, 2026-07-03). The record service's post-processor now merely
     * **enqueues** a background [london.aipartner.echo.transcribe.TranscriptionWorker] and returns
     * immediately, so a long transcription can never block a new recording's Stop (the structural
     * "stop doesn't work" cause). The actual whisper pass runs in the worker via
     * [london.aipartner.echo.transcribe.TranscribingPostProcessor] (Hilt-constructed from its
     * `@Inject` ctor), which still FREES the native context after each pass; the jobs are serial so
     * only one whisper context is ever live (memory discipline preserved, Phase-5 OOM not
     * resurrected).
     */
    @Provides
    @Singleton
    fun recordingPostProcessor(
        workManagerPostProcessor: london.aipartner.echo.transcribe.WorkManagerPostProcessor,
    ): london.aipartner.echo.core.capture.RecordingPostProcessor = workManagerPostProcessor

    /**
     * Phase 8. The production sink is a [london.aipartner.echo.core.sync.RoutingCloudSink]
     * that resolves the user's configured WebDAV destination per call. No destination
     * configured ⇒ the sync worker no-ops (it checks first). The payload it forwards is
     * always ciphertext — [london.aipartner.echo.core.sync.SyncEngine] re-encrypts with the
     * Argon2id backup key first — so the host is zero-knowledge. (Drive appfolder is a later
     * step; [LocalOnlyCloudSink] remains the reference no-op sink.)
     */
    @Provides
    @Singleton
    fun cloudSink(
        configStore: london.aipartner.echo.sync.WebDavConfigStore,
    ): CloudSink = london.aipartner.echo.core.sync.RoutingCloudSink { configStore.current() }

    /**
     * Phase 8. Owns the backup passphrase → Argon2id derived key (Option B,
     * zero-knowledge). The raw passphrase is never persisted; only the derived key
     * (Keystore-backed) is cached. No network yet — this is the passphrase/encryption
     * layer that must exist BEFORE any upload path can.
     */
    @Provides
    @Singleton
    fun backupKeyManager(@ApplicationContext context: Context): london.aipartner.echo.core.sync.backup.BackupKeyManager =
        london.aipartner.echo.core.sync.backup.BackupKeyManager(context)

    /**
     * Phase 5. The generative AI layer (title/summary/actions) is **cloud-only,
     * Pro, double-gated, OFF by default** (Donald's locus decision, 2026-06-25 —
     * `references/phase-05-ai-features.md`). Reuses the SAME double gate as the
     * cloud transcriber: `Entitlements.has(AI_SUMMARY)` AND egress consent. The
     * provider stays [NotConfiguredLlmProvider] until Phase 9 — no paid calls.
     * There is no free on-device generative engine in v1.
     */
    @Provides
    @Singleton
    fun aiEngine(
        entitlements: Entitlements,
        consentPreferences: ConsentPreferences,
    ): AiEngine = CloudAiEngine(
        entitlements = entitlements,
        egressConsent = EgressConsent { consentPreferences.dataEgressAllowed },
        provider = NotConfiguredLlmProvider(),
    )

    @Provides
    @Singleton
    fun aiArtifactWriter(aiArtifactDao: AiArtifactDao): AiArtifactWriter =
        AiArtifactWriter(aiArtifactDao)

    /**
     * Phase 5. Semantic search is **on-device, free, offline, zero egress** (no
     * `EgressConsent`/`Entitlements` reach this path — enforced by ctor signature
     * and `EgressDistinctionTest`). MediaPipe Text Embedder runs the bundled
     * Universal Sentence Encoder; the embedding store is a regenerable derivation.
     */
    @Provides
    @Singleton
    fun semanticIndex(
        @ApplicationContext context: Context,
        embeddingDao: EmbeddingDao,
    ): SemanticIndex = MediaPipeSemanticIndex(
        embedder = MediaPipeTextEmbedder(context),
        dao = embeddingDao,
    )

    @Provides
    @Singleton
    fun recordingDeleter(
        recordingDao: london.aipartner.echo.core.data.RecordingDao,
        syncRefDao: london.aipartner.echo.core.data.SyncRefDao,
        cloudSink: CloudSink,
    ) = london.aipartner.echo.recordings.RecordingDeleter(recordingDao, syncRefDao, cloudSink)
}
