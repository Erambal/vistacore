package com.vistacore.launcher.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vistacore.launcher.R
import com.vistacore.launcher.iptv.Channel

/**
 * Split hero layout. Top 65% of the screen is a massive preview of the current
 * channel with overlaid info. Bottom 35% is categories + horizontal channel ribbon.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveTVSplitHeroActivity : BaseLiveTVActivity() {

    private lateinit var playerView: androidx.media3.ui.PlayerView
    private lateinit var channelNumber: TextView
    private lateinit var channelName: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowProgress: ProgressBar
    private lateinit var channelSearch: EditText
    private lateinit var categoryPicker: Button
    private lateinit var channelRibbon: RecyclerView
    private lateinit var loadingView: View

    private var ribbonAdapter: ChannelRibbonAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_livetv_splithero)

        playerView = findViewById(R.id.sh_player)
        channelNumber = findViewById(R.id.sh_channel_number)
        channelName = findViewById(R.id.sh_channel_name)
        nowTitle = findViewById(R.id.sh_now_title)
        nowProgress = findViewById(R.id.sh_now_progress)
        channelSearch = findViewById(R.id.sh_channel_search)
        categoryPicker = findViewById(R.id.sh_category_chips)
        channelRibbon = findViewById(R.id.sh_channel_ribbon)
        loadingView = findViewById(R.id.sh_loading)

        channelRibbon.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        channelSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterChannels(s?.toString()?.trim() ?: "")
            }
        })

        findViewById<Button>(R.id.sh_btn_number_pad).setOnClickListener { showNumberPadOverlay() }

        setupPlayer(playerView)
        loadChannels()
    }

    override fun onChannelsLoaded() {
        refreshRibbon()
        channelRibbon.post {
            channelRibbon.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    override fun onCategoriesChanged(categories: List<String>) {
        bindCategoryButton(categoryPicker, categories)
    }

    override fun currentSearchQuery(): String =
        channelSearch.text?.toString()?.trim().orEmpty()

    override fun onDisplayedChannelsChanged() {
        refreshRibbon()
    }

    override fun onSelectedChannelChanged(previous: Channel?, current: Channel) {
        channelNumber.text = "CH ${current.number}  ·  ${current.category}"
        channelName.text = current.name
        updateNow(current)
        ribbonAdapter?.let { adapter ->
            adapter.currentChannel = current
            val oldIdx = displayedChannels.indexOf(previous)
            val newIdx = displayedChannels.indexOf(current)
            if (oldIdx >= 0) adapter.notifyItemChanged(oldIdx)
            if (newIdx >= 0) adapter.notifyItemChanged(newIdx)
        }
    }

    override fun onEpgLoaded() {
        currentChannel?.let { updateNow(it) }
    }

    override fun onLoadingStateChanged(loading: Boolean) {
        loadingView.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun refreshRibbon() {
        ribbonAdapter = ChannelRibbonAdapter(displayedChannels, currentChannel) { ch ->
            if (ch.id == currentChannel?.id) goFullScreen(ch) else tuneToChannel(ch)
        }
        channelRibbon.adapter = ribbonAdapter
    }

    private fun updateNow(channel: Channel) {
        val epg = epgData
        val now = epg?.getNowPlaying(channel.epgId.ifBlank { channel.id })
            ?: epg?.getNowPlaying(channel.name)
        if (now != null) {
            nowTitle.text = now.title
            nowProgress.progress = (now.progress * 100).toInt()
            nowProgress.visibility = View.VISIBLE
        } else {
            nowTitle.text = ""
            nowProgress.visibility = View.INVISIBLE
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                currentChannel?.let { goFullScreen(it) }
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                showNumberPadOverlay()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
