package london.aipartner.echo.playback

import android.media.MediaPlayer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the audio clock for the karaoke player. It plays a **transiently decrypted**
 * audio file (decrypted via `AudioEncryptor` into cache by the caller) and exposes a
 * [positionMs] Flow the Compose layer observes to drive the segment highlight. The
 * "which segment is active at time *t*" decision lives in [activeSegmentIndexAt] — a
 * pure function — not here, so it is testable without a device.
 *
 * **No plaintext audio leak (Hard rule):** the controller owns the decrypted temp
 * file's lifetime and **shreds it on [release]** (overwrite-then-delete). Nothing
 * plaintext outlives the playback session.
 */
class PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var decryptedFile: File? = null
    private var ticker: Job? = null

    // Karaoke seek-flash guard (the proven d98961a design — direction-agnostic). While a seek is in
    // flight, `MediaPlayer.currentPosition` still reports the OLD position (below the target on a
    // forward tap, ABOVE it on a backward tap) until the seek physically lands. If the ticker
    // published that, the karaoke highlight would flash the segment you came from for a frame. So we
    // author the target position immediately on seek and suppress ticker writes until
    // `onSeekComplete` fires — which signals the seek LANDED regardless of direction. The tapped
    // line is active straight away, no twitch. (A prior attempt to replace this with a position >=
    // target threshold reintroduced the flash on BACKWARD seeks — the old position is already past
    // the target, so the threshold cleared instantly and published it. onSeekComplete is the correct
    // signal; do not replace it with a position comparison.)
    @Volatile private var seeking = false

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _ready = MutableStateFlow(false)
    /** True once an audio file is prepared and seek/play are valid. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _failed = MutableStateFlow(false)
    /** True when the audio couldn't be opened/prepared (corrupt/empty file). The UI
     *  shows an honest "can't play" note instead of crashing — and the recording can
     *  still be deleted. */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /**
     * Prepares playback from an already-decrypted [file] (the caller decrypts via
     * `AudioEncryptor`); the controller takes ownership and will shred it on [release].
     *
     * A corrupt/empty file (e.g. a broken legacy recording) makes `setDataSource` or
     * async prepare fail — caught here so it surfaces as [failed], never an app crash.
     */
    fun load(file: File) {
        release() // idempotent: clear any prior session first
        decryptedFile = file
        try {
            player = MediaPlayer().apply {
                setOnErrorListener { _, what, extra ->
                    // Async prepare/playback error: mark failed, don't crash. true = handled.
                    _ready.value = false
                    _isPlaying.value = false
                    stopTicker()
                    _failed.value = true
                    true
                }
                setDataSource(file.absolutePath)
                setOnSeekCompleteListener {
                    // Seek physically landed (direction-agnostic): currentPosition is authoritative
                    // again, so the ticker may resume publishing it.
                    seeking = false
                }
                setOnCompletionListener {
                    _isPlaying.value = false
                    stopTicker()
                    seekTo(0)
                }
                setOnPreparedListener {
                    _durationMs.value = it.duration.coerceAtLeast(0).toLong()
                    _ready.value = true
                }
                prepareAsync()
            }
        } catch (t: Throwable) {
            // setDataSource on a corrupt/empty file throws synchronously — handle it.
            runCatching { player?.release() }
            player = null
            _ready.value = false
            _failed.value = true
        }
    }

    fun playPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            _isPlaying.value = false
            stopTicker()
        } else {
            p.start()
            _isPlaying.value = true
            startTicker()
        }
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val clamped = ms.coerceIn(0L, _durationMs.value)
        // Author the target position first and suppress ticker writes until onSeekComplete fires,
        // so the highlight goes straight to the tapped line with no stale-position flash.
        seeking = true
        _positionMs.value = clamped
        p.seekTo(clamped.toInt())
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (true) {
                // Skip while a seek is in flight — currentPosition is still the pre-seek value
                // (stale in either direction). onSeekComplete clears `seeking` when it lands.
                player?.let { if (it.isPlaying && !seeking) _positionMs.value = it.currentPosition.toLong() }
                delay(POLL_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** Tears down the player and **shreds the decrypted temp file** (Hard rule). */
    fun release() {
        stopTicker()
        seeking = false
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        _isPlaying.value = false
        _ready.value = false
        _failed.value = false
        _positionMs.value = 0L
        _durationMs.value = 0L
        decryptedFile?.let { shred(it) }
        decryptedFile = null
    }

    fun dispose() {
        release()
        scope.cancel()
    }

    /** Overwrite the file's bytes then delete it, so no plaintext audio lingers. */
    private fun shred(file: File) {
        runCatching {
            if (file.exists()) {
                val len = file.length()
                if (len > 0) {
                    file.outputStream().use { out ->
                        val zeros = ByteArray(8 * 1024)
                        var written = 0L
                        while (written < len) {
                            val n = minOf(zeros.size.toLong(), len - written).toInt()
                            out.write(zeros, 0, n)
                            written += n
                        }
                        out.flush()
                    }
                }
                file.delete()
            }
        }
    }

    private companion object {
        // ~50ms keeps the highlight visibly smooth without burning the main thread.
        const val POLL_MS = 50L
    }
}
