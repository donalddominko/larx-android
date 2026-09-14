package london.aipartner.echo.transcribe

import java.util.Locale

/**
 * One choice in the language picker. [tag] is a BCP-47 language tag (e.g. "en", "sl"),
 * or `null` for **"Device default"** (follow the phone's system locale — the app default).
 */
data class LanguageOption(val tag: String?) {
    /**
     * Human label in the user's own UI language. For "Device default" we also name the
     * resolved language in parentheses so the user can SEE what "device default" means on
     * their phone right now — honesty over a bare "Device default".
     */
    fun label(): String = when (tag) {
        null -> "Device default (${displayName(Locale.getDefault().toLanguageTag())})"
        else -> displayName(tag)
    }

    companion object {
        /** Endonym-aware display name for a tag, capitalised, in the current UI locale. */
        fun displayName(tag: String): String =
            Locale.forLanguageTag(tag)
                .getDisplayLanguage(Locale.getDefault())
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
}

/**
 * The **curated** language list offered in the picker.
 *
 * Honesty rule (v1 language scope): we only present languages we've **spot-checked on the
 * A03**. English is verified (the live-mic gate + Gate B `b4`). Adding a language here is a
 * one-line change — but it MUST be backed by a recorded on-device spot-check first, never
 * added speculatively. Whisper-`base` is multilingual, but "the model can" is not "we've
 * verified it," and the picker must not imply support we haven't proven.
 *
 * "Device default" always leads (the app default, `null` override).
 */
object LanguageCatalog {

    /**
     * The bar for offering a language (Donald, 2026-07-02): a recorded A03 spot-check where
     * **Donald reads the transcript as a native/fluent speaker and judges it ACCEPTABLE** —
     * not merely "the output is in that language." Whisper-`base` is weaker on lower-resource
     * languages, so "it's Slovenian-shaped" is NOT the bar; "good enough to offer" is. And that
     * bar is applied **per tier**: a language may clear it on the Pro cloud model (a larger
     * whisper) yet fall short on the free on-device `base` model.
     */

    /** BCP-47 tags acceptable on the **free on-device** (`base`) path, in offer order. */
    private val onDeviceTags: List<String> = listOf(
        "en", // English — verified: live-mic gate + Gate B b4.
    )

    /**
     * BCP-47 tags acceptable **only on the Pro cloud** path (better model), NOT on-device.
     *
     * `sl` (Slovenian): A03 spot-check 2026-07-02 — forced-`sl` on `base` read back as
     * *understandable but not good enough to ship free* (Donald, native speaker: "definitely
     * cloud version only"). The forced-`en` decode of the same speech was nonsense (the incident
     * repro). So Slovenian is offered **only when the cloud path is active** — surfaced in the
     * picker in Phase 9 when Pro/cloud lands, NOT in the v1 free on-device list.
     */
    private val cloudOnlyTags: List<String> = listOf(
        "sl",
    )

    /**
     * The picker options for the given tier. Device default always leads. In v1 the cloud path
     * isn't wired ([cloudEnabled] defaults false), so only [onDeviceTags] show; Phase 9 flips
     * [cloudEnabled] true when the Pro cloud transcriber is active, adding [cloudOnlyTags].
     */
    fun options(cloudEnabled: Boolean = false): List<LanguageOption> {
        val tags = onDeviceTags + if (cloudEnabled) cloudOnlyTags else emptyList()
        return listOf(LanguageOption(null)) + tags.map { LanguageOption(it) }
    }

    /** v1 convenience: the on-device (free) picker options. */
    val options: List<LanguageOption> get() = options(cloudEnabled = false)

    /** True if [tag]'s primary subtag is acceptable on the free ON-DEVICE path (Layer-2 case a). */
    fun isOnDeviceAvailable(tag: String): Boolean = primaryOf(tag) in onDeviceTags

    /** The canonical on-device tag for [tag] (e.g. "en-GB" → "en"), for setting the override. */
    fun onDeviceTagFor(tag: String): String = primaryOf(tag)

    private fun primaryOf(tag: String): String =
        java.util.Locale.forLanguageTag(tag).language.ifBlank { tag.substringBefore('-') }.lowercase()
}
