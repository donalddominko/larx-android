package london.aipartner.echo.ui.detail

/**
 * An honest warning shown ABOVE a transcript when [MisdecodeGuard] finds the decode likely went
 * wrong (repetition / foreign-annotation signals in the ACTUAL output).
 *
 * **Single generic shape (Direction A step 3, 2026-07-19).** The pre-decode whisper-`base`
 * language-detect was removed — it was unreliable on real phone-mic audio and produced false
 * "not English" warnings under correct transcripts (and drove a no-longer-existing
 * language-switch prompt). The warning is now evidence-based (post-decode text signals only),
 * always generic (never names a language), and references NO tier/Pro (v1 is free-only).
 */
sealed interface TranscriptWarning {

    /**
     * A suspected mis-decode: the transcript shows repetition / foreign-script signatures, so it
     * may be inaccurate. Generic by design — never names a language, never mentions a tier.
     */
    data object LikelyUnsupportedLanguage : TranscriptWarning
}
