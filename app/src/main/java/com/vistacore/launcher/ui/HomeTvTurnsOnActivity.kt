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
    private val HIDE_DELAY_MS = 6000L

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

        ribbon.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

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
        // Show a buffering spinner over the video so a slow stream doesn't look frozen.
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            }
        })
        loadChannels()
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

    override fun onLoadingStateChanged(loading: Boolean) {
        loadingView.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun refreshRibbon() {
        ribbonAdapter = ChannelRibbonAdapter(
            displayedChannels, currentChannel, favoritesManager,
            onChannelMenu = { ch -> showChannelContextMenu(ch) },
            onClick = { ch -> if (ch.id == currentChannel?.id) goFullScreen(ch) else tuneToChannel(ch) }
        )
        ribbon.adapter = ribbonAdapter
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
                // system launcher. Require a confirming second press.
                if (backPressedOnce) { finish(); return true }
                backPressedOnce = true
                Toast.makeText(this, R.string.home_press_back_again, Toast.LENGTH_LONG).show()
                handler.postDelayed({ backPressedOnce = false }, 3500)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                val focused = currentFocus
                val nothingAbove = focused == null || focused.focusSearch(View.FOCUS_UP) == null
                if (nothingAbove) {
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

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
