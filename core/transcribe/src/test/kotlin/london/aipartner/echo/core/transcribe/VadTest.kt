package london.aipartner.echo.core.transcribe

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadTest {

    private val vad = EnergyVad()

    @Test fun silenceHasNoSpeech() {
        assertFalse(vad.hasSpeech(ShortArray(16_000))) // 1s of pure zeros
    }

    @Test fun emptyHasNoSpeech() {
        assertFalse(vad.hasSpeech(ShortArray(0)))
    }

    @Test fun quietNoiseFloorIsTreatedAsSilence() {
        // ~ ±50 LSB dither, well under the voiced threshold — must NOT trip speech.
        val pcm = ShortArray(16_000) { (((it * 7) % 101) - 50).toShort() }
        assertFalse(vad.hasSpeech(pcm))
    }

    @Test fun aLoudToneIsSpeech() {
        // 1s 200 Hz tone at ~half-scale — plenty of voiced energy.
        val pcm = ShortArray(16_000) { i ->
            (16_000 * sin(2 * PI * 200 * i / 16_000)).toInt().toShort()
        }
        assertTrue(vad.hasSpeech(pcm))
    }

    @Test fun aSingleClickIsNotSpeech() {
        // One loud sample amid silence — below the cumulative voiced-duration floor.
        val pcm = ShortArray(16_000)
        pcm[8_000] = 20_000
        assertFalse(vad.hasSpeech(pcm))
    }
}
