package london.aipartner.echo.core.capture

/**
 * v1 resolver: MEMO → [MicRecorder]. The seam is kept so a future source/job
 * registers here without touching callers.
 */
class RealCaptureSourceResolver(
    private val mic: MicRecorder,
) : CaptureSourceResolver {

    override fun resolve(job: CaptureJob): CaptureSource = when (job) {
        CaptureJob.MEMO -> mic
    }
}
