package london.aipartner.echo.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import london.aipartner.echo.core.billing.Entitlements
import london.aipartner.echo.core.consent.ConsentPreferences
import london.aipartner.echo.core.sync.ArtifactReader
import london.aipartner.echo.core.sync.BackupCoordinator
import london.aipartner.echo.core.sync.CloudSink
import london.aipartner.echo.core.sync.SyncEngine
import london.aipartner.echo.core.sync.SyncEgressConsent
import london.aipartner.echo.core.sync.SyncGate
import london.aipartner.echo.core.sync.SyncStateStore
import london.aipartner.echo.core.sync.backup.BackupCipher
import london.aipartner.echo.core.sync.backup.BackupKeyManager
import london.aipartner.echo.core.sync.backup.KeyManagerBackupCipher
import london.aipartner.echo.sync.RealArtifactReader
import london.aipartner.echo.sync.RoomSyncStateStore

/**
 * Phase 8 — assembles the sync engine graph from the seams already bound in [SeamsModule]
 * (the `CloudSink`, the `BackupKeyManager`, `Entitlements`, `ConsentPreferences`). The
 * `SyncWorker` gets the resulting `BackupCoordinator` injected by Hilt.
 *
 * The whole graph fails closed: the [SyncGate] blocks unless egress consent AND Pro are
 * both on; the [BackupCipher] refuses unless a passphrase-derived key exists. Nothing here
 * touches a network until the worker actually runs against a configured destination.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    /** Egress consent, read from the Phase-3 chokepoint (mirrors `:core:transcribe`'s binding). */
    @Provides
    @Singleton
    fun syncEgressConsent(consentPreferences: ConsentPreferences): SyncEgressConsent =
        SyncEgressConsent { consentPreferences.dataEgressAllowed }

    @Provides
    @Singleton
    fun syncGate(egress: SyncEgressConsent, entitlements: Entitlements): SyncGate =
        SyncGate(egress, entitlements)

    /** Re-encrypts with the cached Argon2id-derived key; fails closed if no passphrase set. */
    @Provides
    @Singleton
    fun backupCipher(keyManager: BackupKeyManager): BackupCipher =
        KeyManagerBackupCipher(keyManager)

    @Provides
    @Singleton
    fun artifactReader(reader: RealArtifactReader): ArtifactReader = reader

    @Provides
    @Singleton
    fun syncStateStore(store: RoomSyncStateStore): SyncStateStore = store

    @Provides
    @Singleton
    fun syncEngine(
        gate: SyncGate,
        reader: ArtifactReader,
        cipher: BackupCipher,
        sink: CloudSink,
    ): SyncEngine = SyncEngine(gate, reader, cipher, sink)

    @Provides
    @Singleton
    fun backupCoordinator(
        engine: SyncEngine,
        gate: SyncGate,
        store: SyncStateStore,
    ): BackupCoordinator = BackupCoordinator(engine, gate, store)
}
