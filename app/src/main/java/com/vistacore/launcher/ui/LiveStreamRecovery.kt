package com.vistacore.launcher.ui

import android.os.Handler
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Self-healing watchdog for LIVE IPTV streams.
 *
 * IPTV feeds — especially the looped "24/7 [show]" channels — routinely stall,
 * drop the TCP connection mid-stream, or splice with a PTS discontinuity
 * (which surfaces as AudioSink.UnexpectedDiscontinuityException). Media3 reacts
 * by stopping, and the picture then freezes until the user leaves and re-enters
 * the app. On-device capture (onn. 4K Google TV, 2026-07-02) confirmed two
 * freeze shapes: a dropped-connection SocketTimeout, and a decoder-starved
 * stall where playback stays READY + playing but the position stops advancing.
 *
 * This driver reconnects the current stream on its own:
 *   • on a recoverable player error (network / timeout / audio discontinuity)
 *   • when the picture is frozen — stuck BUFFERING, or READY + playing with a
 *     non-advancing position
 * Reconnects are capped and backed off, and the budget resets the instant
 * playback is healthy again, so a genuinely dead stream stops hammering the
 * server after a few tries instead of looping forever.
 *
 * NOT for VOD: a slow-buffering movie must never be yanked back to the start,
 * so callers gate this to live playback only.
 *
 * All methods are main-thread only (the supplied [handler] must be the main
 * looper's) and every scheduled callback is cancelled by [stop].
 */
@UnstableApi
class LiveStreamRecovery(
    private val handler: Handler,
    /** Current player, or null when it has been released. */
    private val player: () -> ExoPlayer?,
    /** Rebuild the current stream's media source and re-prepare it. */
    private val reconnect: () -> Unit,
    /** Called once when reconnects are exhausted. */
    private val onGiveUp: () -> Unit = {},
    private val tag: String = "LiveRecovery",
) {
    private var retries = 0
    private var pending: Runnable? = null
    private var lastPos = -1L
    private var frozenTicks = 0
    private var bufferingTicks = 0
    private var running = false

    private val watchdog = object : Runnable {
        override fun run() {
            check()
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    /** Begin (or restart) the freeze watchdog. Idempotent. */
    fun start() {
        running = true
        lastPos = -1L
        frozenTicks = 0
        bufferingTicks = 0
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, TICK_MS)
    }

    /** Stop the watchdog and cancel any queued reconnect. */
    fun stop() {
        running = false
        handler.removeCallbacks(watchdog)
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    /** New channel / fresh stream — forget the retry budget. */
    fun reset() {
        retries = 0
        frozenTicks = 0
        bufferingTicks = 0
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    /** Playback is healthy — clear the budget so the next incident starts fresh. */
    fun onHealthy() {
        retries = 0
        frozenTicks = 0
        bufferingTicks = 0
    }

    /**
     * Feed a player error in. Returns true when a reconnect was scheduled — the
     * caller should show its loading state and NOT surface an error. Returns
     * false when the failure is permanent (content gone / auth / unsupported
     * container), so the caller can handle it as a hard error.
     */
    fun onError(error: PlaybackException): Boolean {
        if (!isRecoverable(error)) return false
        schedule("error ${error.errorCodeName}")
        return true
    }

    private fun check() {
        val p = player() ?: return
        val state = p.playbackState
        val pos = p.currentPosition
        when {
            state == Player.STATE_BUFFERING -> {
                bufferingTicks++
                frozenTicks = 0
                if (bufferingTicks >= BUFFERING_LIMIT) schedule("buffering ${bufferingTicks * TICK_MS / 1000}s")
            }
            state == Player.STATE_READY && p.isPlaying && pos == lastPos -> {
                frozenTicks++
                bufferingTicks = 0
                if (frozenTicks >= FROZEN_LIMIT) schedule("frozen ${frozenTicks * TICK_MS / 1000}s")
            }
            else -> {
                // Advancing, paused, idle or ended — not a freeze.
                frozenTicks = 0
                bufferingTicks = 0
            }
        }
        lastPos = pos
    }

    private fun schedule(reason: String) {
        // A reconnect is already queued — let it run rather than stack another.
        if (pending != null) return
        if (retries >= MAX_RETRIES) {
            Log.w(tag, "giving up after $retries reconnects ($reason)")
            stop()
            onGiveUp()
            return
        }
        retries++
        frozenTicks = 0
        bufferingTicks = 0
        val delay = (BASE_BACKOFF_MS * retries).coerceAtMost(MAX_BACKOFF_MS)
        Log.w(tag, "reconnect #$retries in ${delay}ms ($reason)")
        val r = Runnable {
            pending = null
            if (running) reconnect()
        }
        pending = r
        handler.postDelayed(r, delay)
    }

    private fun isRecoverable(e: PlaybackException): Boolean {
        val msg = (e.cause?.message ?: e.message ?: "").lowercase()
        // Permanent: the title is gone or the account can't reach it. Reconnecting
        // just hammers the server, so bail to the caller's hard-error path.
        if (msg.contains("404") || msg.contains("not found") ||
            msg.contains("403") || msg.contains("forbidden")
        ) return false
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> false
            // Everything else on a live feed — timeouts, connection resets, audio
            // discontinuities, transient decode errors, behind-live-window — is
            // worth a reconnect.
            else -> true
        }
    }

    companion object {
        private const val TICK_MS = 2_000L
        private const val FROZEN_LIMIT = 6      // ~12s of frozen picture
        private const val BUFFERING_LIMIT = 8   // ~16s stuck buffering
        private const val MAX_RETRIES = 8
        private const val BASE_BACKOFF_MS = 600L
        private const val MAX_BACKOFF_MS = 5_000L
    }
}
