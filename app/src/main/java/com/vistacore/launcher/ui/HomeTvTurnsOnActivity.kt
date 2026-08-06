package com.vistacore.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vistacore.launcher.R
import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.ProviderText

/**
 * "TV Turns On" home: opens straight into a fullscreen live channel — exactly
 * like switching on an old TV — with a channel ribbon along the bottom and big
 * buttons to jump to Movies, TV Shows and Settings. Tunes to the last channel
 * watched (via the Recent category) so the very first thing the user sees is
 * something already playing.
 *
 * Reuses the battle-tested [BaseLiveTVActivity] player/loading machinery.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HomeTvTurnsOnActivity : BaseLiveTVActivity() {

    private lateinit var playerView: androidx.media3.ui.PlayerView
    private lateinit var channelNumber: TextView
    private lateinit var channelName: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var categoryPicker: Button
    private lateinit var ribbon: RecyclerView
    private lateinit var noResults: TextView
    private lateinit var loadingView: View
    private lateinit var topbar: View
    private lateinit var controls: View
    private lateinit var infoOverlay: View
    private lateinit var buffering: View

    private var ribbonAdapter: ChannelRibbonAdapter? = null
    private var backPressedOnce = false
    private var controlsHidden = false
    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }

    /**
     * How long the top bar + ribbon stay up with no input.
     *
     * 6s was too aggressive: hideControls() sets those views GONE without checking
     * whether one of them holds focus, and Android drops focus when the focused view
     * disappears — so a user reading the Movies/Shows/Settings buttons would find the
     * whole bar vanish and their cursor gone. See also the focus guard in hideControls().
     */
    private val HIDE_DELAY_MS = 12000L

    /**
     * This layout is the home screen, but it extends the Live TV base — which pins
     * FLAG_KEEP_SCREEN_ON for passive viewing. The other two home layouts arm a screen
     * saver; this one never did, so the Settings screen-saver slider silently did nothing
     * here and the panel stayed lit indefinitely with a live stream decoding behind it.
     */
    private var idleRunnable: Runnable? = null

    private fun resetIdleTimer() {
        idleRunnable?.let { handler.removeCallbacks(it) }
        val timeout = prefs.screenSaverTimeout
        if (timeout <= 0) return
        idleRunnable = Runnable {
            // Release the decoder before handing over — no point holding a hardware
            // decoder and its buffer while a black screen saver is showing.
            player?.pause()
            startActivity(Intent(this, ScreenSaverActivity::class.java))
        }
        handler.postDelayed(idleRunnable!!, timeout * 60 * 1000L)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_tv_turns_on)

        playerView = findViewById(R.id.tvon_player)
        channelNumber = findViewById(R.id.tvon_channel_number)
        channelName = findViewById(R.id.tvon_channel_name)
        nowPlaying = findViewById(R.id.tvon_now_playing)
        categoryPicker = findViewById(R.id.tvon_category)
        ribbon = findViewById(R.id.tvon_ribbon)
        noResults = findViewById(R.id.tvon_no_results)
        loadingView = findViewById(R.id.tvon_loading)
        topbar = findViewById(R.id.tvon_topbar)
        controls = findViewById(R.id.tvon_controls)
        infoOverlay = findViewById(R.id.tvon_info)
        buffering = findViewById(R.id.tvon_buffering)

        ribbon.layoutManager = RibbonLayoutManager(this)

        findViewById<Button>(R.id.tvon_btn_search).setOnClickListener {
            startActivity(Intent(this, VoiceSearchActivity::class.java))
        }
        findViewById<Button>(R.id.tvon_btn_movies).setOnClickListener {
            startActivity(Intent(this, VODBrowserActivity::class.java).apply {
                putExtra(VODBrowserActivity.EXTRA_CONTENT_TYPE, VODBrowserActivity.TYPE_MOVIES)
            })
        }
        findViewById<Button>(R.id.tvon_btn_shows).setOnClickListener {
            startActivity(Intent(this, VODBrowserActivity::class.java).apply {
                putExtra(VODBrowserActivity.EXTRA_CONTENT_TYPE, VODBrowserActivity.TYPE_SHOWS)
            })
        }
        findViewById<Button>(R.id.tvon_btn_apps).setOnClickListener { showAppsDialog() }
        findViewById<Button>(R.id.tvon_btn_settings).setOnClickListener {
            if (prefs.pinEnabled && prefs.settingsPin.isNotBlank()) {
                PinDialogHelper.showPinDialog(this, "Enter PIN to open Settings",
                    onSuccess = { startActivity(Intent(this, SettingsActivity::class.java)) })
            } else {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

        setupPlayer(playerView)
        loadChannels()
    }

    /**
     * Show a buffering spinner over the video so a slow stream doesn't look
     * frozen. Attached here (not in onCreate) so it's re-wired every time the
     * base class rebuilds the player after a fullscreen handoff or pause/resume
     * — otherwise the spinner silently stops working after the first rebuild.
     */
    override fun onPlayerBuilt(player: androidx.media3.exoplayer.ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            }
        })
    }

    // --- Auto-hiding on-screen controls (cable-box style OSD) ---

    private fun showControls() {
        controlsHidden = false
        topbar.visibility = View.VISIBLE
        controls.visibility = View.VISIBLE
        infoOverlay.visibility = View.VISIBLE
        scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, HIDE_DELAY_MS)
    }

    private fun hideControls() {
        handler.removeCallbacks(hideControlsRunnable)

        // Never hide the thing the user is currently on. Setting a focused view GONE makes
        // Android drop focus entirely, which left Movies/Shows/Apps/Settings unreachable
        // for anyone who paused to read them — the cursor simply disappeared. If focus is
        // up here, the user is still working; re-arm and try again later.
        if (topbar.hasFocus() || controls.hasFocus()) {
            handler.postDelayed(hideControlsRunnable, HIDE_DELAY_MS)
            return
        }

        controlsHidden = true
        topbar.visibility = View.GONE
        controls.visibility = View.GONE
        infoOverlay.visibility = View.GONE
    }

    private fun isRevealKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
        else -> false
    }

    private fun focusRibbonOnCurrent() {
        val idx = displayedChannels.indexOf(currentChannel).coerceAtLeast(0)
        ribbon.post {
            ribbon.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus()
                ?: ribbon.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    /** Tune the channel above/below the current one (CH+/- on the remote). */
    private fun tuneRelative(delta: Int) {
        if (displayedChannels.isEmpty()) return
        val cur = displayedChannels.indexOf(currentChannel)
        val next = ((if (cur < 0) 0 else cur) + delta + displayedChannels.size) % displayedChannels.size
        tuneToChannel(displayedChannels[next])
    }

    override fun onChannelsLoaded() {
        refreshRibbon()
        ribbon.post {
            ribbon.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
        scheduleHide()
    }

    override fun onCategoriesChanged(categories: List<String>) {
        bindCategoryButton(categoryPicker, categories)
    }

    override fun onDisplayedChannelsChanged() {
        refreshRibbon()
    }

    override fun onSelectedChannelChanged(previous: Channel?, current: Channel) {
        channelNumber.text = "CH ${current.number}"
        channelName.text = current.name
        updateNowPlaying(current)
        ribbonAdapter?.let { adapter ->
            adapter.currentChannel = current
            val oldIdx = displayedChannels.indexOf(previous)
            val newIdx = displayedChannels.indexOf(current)
            if (oldIdx >= 0) adapter.notifyItemChanged(oldIdx)
            if (newIdx >= 0) adapter.notifyItemChanged(newIdx)
        }
        // Flash the channel banner / controls on every tune, then auto-hide.
        showControls()
    }

    override fun onEpgLoaded() {
        currentChannel?.let { updateNowPlaying(it) }
    }

    // This screen is the one most likely to sit untouched for hours, so the
    // now-playing line advancing on its own matters most here.
    override fun onEpgTick() {
        currentChannel?.let { updateNowPlaying(it) }
    }

    override fun onLoadingStateChanged(loading: Boolean) {
        loadingView.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun refreshRibbon() {
        ribbonAdapter = ChannelRibbonAdapter(
            displayedChannels, currentChannel, favoritesManager,
            onChannelMenu = { ch -> showChannelContextMenu(ch) },
            onClick = { ch -> if (ch.id == currentChannel?.id) goFullScreen(ch) else tuneToChannel(ch) }
        )
        ribbon.setAdapterPreservingFocus(ribbonAdapter)
        if (displayedChannels.isEmpty()) {
            noResults.visibility = View.VISIBLE
            ribbon.visibility = View.GONE
        } else {
            noResults.visibility = View.GONE
            ribbon.visibility = View.VISIBLE
        }
    }

    private fun updateNowPlaying(channel: Channel) {
        val epg = epgData
        val now = epg?.getNowPlaying(channel.epgId.ifBlank { channel.id })
            ?: epg?.getNowPlaying(channel.name)
        nowPlaying.text = now?.title?.let { ProviderText.cleanDisplay(it) } ?: ""
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            resetIdleTimer()
            if (controlsHidden) {
                // First press just brings the OSD back (don't also move focus).
                if (isRevealKey(event.keyCode)) {
                    showControls(); focusRibbonOnCurrent(); return true
                }
            } else {
                scheduleHide()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // It's the home screen — Back should not silently drop to the
                // system launcher. When we ARE the launcher there is no target at
                // all, so Back does nothing; otherwise require a confirming press.
                if (isDefaultLauncher()) return true
                if (backPressedOnce) { finish(); return true }
                backPressedOnce = true
                Toast.makeText(this, R.string.home_press_back_again, Toast.LENGTH_LONG).show()
                handler.postDelayed({ backPressedOnce = false }, 3500)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                val focused = currentFocus
                if (focused == null) {
                    // Focus was lost (we just resumed from the player). Don't read a null
                    // focus as "nothing above me, so go fullscreen" — that re-opened the
                    // channel the user had just backed out of. Put the cursor back on the
                    // ribbon and let the next press navigate normally.
                    focusRibbonOnCurrent()
                    return true
                }
                // Genuinely at the top with a real focused view and nothing above it:
                // this is the "push up to watch" gesture.
                if (focused.focusSearch(View.FOCUS_UP) == null) {
                    currentChannel?.let { goFullScreen(it) }
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> { showNumberPadOverlay(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { tuneRelative(+1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { tuneRelative(-1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showAppsDialog() {
        val apps = AppShortcuts.extraApps(this)
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.settings_apps_summary, Toast.LENGTH_SHORT).show()
            return
        }
        val names = apps.map { it.label }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_VistaCore_Dialog)
            .setTitle(R.string.section_all_apps)
            .setItems(names) { _, which -> AppShortcuts.launch(this, apps[which]) }
            .show()
    }

    override fun onResume() {
        super.onResume()
        resetIdleTimer()
        // Returning from the fullscreen player (or a browser) leaves this window with
        // nothing focused — the player took focus and nothing hands it back. With focus
        // null, the UP handler below treats "no view above" as "watch fullscreen", so the
        // very next UP press bounces the user straight back into the channel they just
        // left. Restore the ribbon highlight so focus is never null and the cursor is
        // visible again. Guarded on hasFocus() so we never yank focus off a view the user
        // is already on (e.g. a returning browser that restored its own focus).
        ribbon.post { if (!hasWindowFocusOnAControl()) focusRibbonOnCurrent() }
    }

    /** True when focus is already on one of our controls, so onResume must not move it. */
    private fun hasWindowFocusOnAControl(): Boolean =
        ribbon.hasFocus() || topbar.hasFocus() || controls.hasFocus()

    override fun onPause() {
        super.onPause()
        // Don't let the idle timer fire while we're off-screen — it would launch the
        // screen saver on top of whatever the user actually opened.
        idleRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
