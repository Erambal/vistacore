package com.vistacore.launcher.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vistacore.launcher.R
import com.vistacore.launcher.iptv.Channel

/**
 * Immersive fullscreen layout. Player fills the screen with channel info
 * and a ribbon of channels overlaid on top with gradient scrims.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveTVImmersiveActivity : BaseLiveTVActivity() {

    private lateinit var player_view: androidx.media3.ui.PlayerView
    private lateinit var infoOverlay: View
    private lateinit var bottomScrim: View
    private lateinit var ribbonContainer: View
    private lateinit var channelNumber: TextView
    private lateinit var channelName: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var channelSearch: EditText
    private lateinit var categoryPicker: Button
    private lateinit var channelRibbon: RecyclerView
    private lateinit var loadingView: View
    private lateinit var noResultsText: TextView

    private var ribbonAdapter: ChannelRibbonAdapter? = null
    private var overlayVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_livetv_immersive)

        player_view = findViewById(R.id.imm_player)
        infoOverlay = findViewById(R.id.imm_info_overlay)
        bottomScrim = findViewById(R.id.imm_bottom_scrim)
        ribbonContainer = findViewById(R.id.imm_ribbon_container)
        channelNumber = findViewById(R.id.imm_channel_number)
        channelName = findViewById(R.id.imm_channel_name)
        nowPlaying = findViewById(R.id.imm_now_playing)
        channelSearch = findViewById(R.id.imm_channel_search)
        categoryPicker = findViewById(R.id.imm_category_chips)
        channelRibbon = findViewById(R.id.imm_channel_ribbon)
        loadingView = findViewById(R.id.imm_loading)
        noResultsText = findViewById(R.id.imm_no_results_text)

        channelRibbon.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        channelSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterChannels(s?.toString()?.trim() ?: "")
            }
        })

        findViewById<Button>(R.id.imm_btn_number_pad).setOnClickListener { showNumberPadOverlay() }

        intent.getStringExtra(EXTRA_SEARCH_QUERY)?.let { query ->
            if (query.isNotBlank()) {
                channelSearch.setText(query)
                channelSearch.clearFocus()
                channelRibbon.requestFocus()
                window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            }
        }

        setupPlayer(player_view)
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
            onFavoriteToggle = { id -> toggleChannelFavorite(id) },
            onClick = { ch ->
                if (ch.id == currentChannel?.id) goFullScreen(ch) else tuneToChannel(ch)
            }
        )
        channelRibbon.adapter = ribbonAdapter

        val q = channelSearch.text?.toString()?.trim().orEmpty()
        if (displayedChannels.isEmpty() && q.isNotEmpty()) {
            noResultsText.text = "No channels matching \"$q\""
            noResultsText.visibility = View.VISIBLE
            channelRibbon.visibility = View.GONE
        } else {
            noResultsText.visibility = View.GONE
            channelRibbon.visibility = View.VISIBLE
        }
    }

    private fun updateNowPlaying(channel: Channel) {
        val epg = epgData
        val now = epg?.getNowPlaying(channel.epgId.ifBlank { channel.id })
            ?: epg?.getNowPlaying(channel.name)
        nowPlaying.text = now?.title ?: ""
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                // UP goes to fullscreen only when there's nothing else above
                // to focus — search, category picker, and the number-pad button
                // are navigated normally. Once focus reaches the topmost row of
                // controls, the next UP press triggers fullscreen.
                val focused = currentFocus
                val nothingAbove = focused == null || focused.focusSearch(View.FOCUS_UP) == null
                if (nothingAbove) {
                    currentChannel?.let { goFullScreen(it) }
                    true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_MENU -> {
                showNumberPadOverlay()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}

class ChannelRibbonAdapter(
    private val channels: List<Channel>,
    var currentChannel: Channel?,
    private val favoritesManager: com.vistacore.launcher.data.FavoritesManager,
    private val onFavoriteToggle: (String) -> Boolean,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelRibbonAdapter.VH>() {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val logo: ImageView = itemView.findViewById(R.id.ribbon_logo)
        val number: TextView = itemView.findViewById(R.id.ribbon_number)
        val name: TextView = itemView.findViewById(R.id.ribbon_name)
        val favIcon: ImageView = itemView.findViewById(R.id.ribbon_fav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_ribbon, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val channel = channels[position]
        holder.number.text = "CH ${channel.number}"
        holder.name.text = channel.name

        val isCurrent = channel.id == currentChannel?.id
        holder.name.setTextColor(holder.itemView.context.getColor(
            if (isCurrent) R.color.accent_gold else R.color.text_primary
        ))

        if (channel.logoUrl.isNotBlank()) {
            Glide.with(holder.itemView.context).load(channel.logoUrl)
                .placeholder(R.drawable.ic_iptv).into(holder.logo)
        } else {
            holder.logo.setImageResource(R.drawable.ic_iptv)
        }

        holder.favIcon.visibility =
            if (favoritesManager.isFavoriteChannel(channel.id)) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onClick(channel) }
        holder.itemView.setOnLongClickListener {
            val nowFav = onFavoriteToggle(channel.id)
            holder.favIcon.visibility = if (nowFav) View.VISIBLE else View.GONE
            true
        }
        holder.itemView.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    override fun getItemCount() = channels.size
}
