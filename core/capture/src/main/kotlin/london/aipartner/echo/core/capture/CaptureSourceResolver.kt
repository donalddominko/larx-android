package london.aipartner.echo.core.capture

/**
 * Resolves the [CaptureSource] for the requested [CaptureJob]. This is the seam
 * that lets the capture set grow (a new source/job) without touching callers.
 * v1 has a single source; the indirection is deliberately kept.
 */
interface CaptureSourceResolver {
    fun resolve(job: CaptureJob): CaptureSource

    /** The capability the resolved source advertises — for the honest UI badge. */
    fun resolvedCapability(job: CaptureJob): CaptureCapability = resolve(job).capability
}
