package london.aipartner.echo.core.capture

/**
 * Hook for downstream processing of a recording once capture has finalized and the
 * audio is persisted (Phase 6 Step 4: on-device transcription-on-stop).
 *
 * The seam keeps `:core:capture` decoupled from `:core:transcribe`: the foreground
 * service invokes this AFTER the audio is safely on disk, and the real implementation
 * (in `:app`) owns the transcription + its native-resource lifetime. By contract:
 *  - the recording is already persisted before this runs — a failure here must NEVER
 *    lose the audio (it only affects the derivation/transcript state);
 *  - implementations are self-contained re: heavy native resources (load → use →
 *    FREE) so a transcription pass does not stay resident.
 */
interface RecordingPostProcessor {
    /** Process a finalized recording. [audioRef] is the encrypted local audio path. */
    suspend fun process(recordingId: String, audioRef: String)
}
