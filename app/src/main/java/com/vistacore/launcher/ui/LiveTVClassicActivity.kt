package com.vistacore.launcher.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vistacore.launcher.R
import com.vistacore.launcher.databinding.ActivityLivetvBinding
import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.EpgData
import java.text.SimpleDateFormat
import java.util.*

/**
 * Classic two-panel layout: sidebar with search/chips/channel list on the left,
 * mini player and EPG strip on the right. This is the original design.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveTVClassicActivity : BaseLiveTVActivity() {

    private lateinit var binding: ActivityLivetvBinding
    private var channelAdapter: LiveChannelAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLivetvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.channelList.layoutManager = LinearLayoutManager(this)

        setupSearchBar()
        setupButtons()
        setupPlayer(binding.miniPlayer)
        loadChannels()

        // Pre-fill search query if provided
        intent.getStringExtra(EXTRA_SEARCH_QUERY)?.let { query ->
            if (query.isNotBlank()) {
                binding.channelSearch.setText(query)
                binding.channelSearch.clearFocus()
                binding.channelSidebar.requestFocus()
                window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            }
        }
    }

    private fun setupSearchBar() {
        binding.channelSearch.clearFocus()
        binding.channelSidebar.requestFocus()
        binding.channelSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterChannels(s?.toString()?.trim() ?: "")
            }
        })
    }

    private fun setupButtons() {
        binding.btnNumberPad.setOnClickListener { showNumberPadOverlay() }
        binding.btnNumberPad.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
        binding.btnNumberPad.nextFocusLeftId = R.id.channel_list
        binding.btnNumberPad.isFocusable = false
        binding.btnNumberPad.isFocusableInTouchMode = false

        binding.btnToggleEpg.setOnClickListener {
            startActivity(android.content.Intent(this, EpgGuideActivity::class.java))
        }
        binding.btnToggleEpg.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    override fun onChannelsLoaded() {
        updateChannelList()
        binding.channelList.post {
            binding.channelList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    override fun onCategoriesChanged(categories: List<String>) {
        bindCategoryButton(binding.categoryChips, categories)
    }

    override fun currentSearchQuery(): String =
        binding.channelSearch.text?.toString()?.trim().orEmpty()

    override fun onDisplayedChannelsChanged() {
        updateChannelList()
    }

    override fun onSelectedChannelChanged(previous: Channel?, current: Channel) {
        binding.stripChannelName.text = current.name
        updateEpgStrip(current)
        channelAdapter?.let { adapter ->
            adapter.currentChannel = current
            val oldIdx = displayedChannels.indexOf(previous)
            val newIdx = displayedChannels.indexOf(current)
            if (oldIdx >= 0) adapter.notifyItemChanged(oldIdx)
            if (newIdx >= 0) adapter.notifyItemChanged(newIdx)
        }
    }

    override fun onEpgLoaded() {
        updateChannelList()
        currentChannel?.let { updateEpgStrip(it) }
    }

    override fun onLoadingStateChanged(loading: Boolean) {
        binding.livetvLoading.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun updateChannelList() {
        binding.channelCountLabel.text = "${displayedChannels.size} ch"
        channelAdapter = LiveChannelAdapter(
            displayedChannels, epgData, prefs.showEpgInChannelList, currentChannel, favoritesManager,
            onFavoriteToggle = { id -> toggleChannelFavorite(id) },
            onClick = { ch ->
                if (ch.id == currentChannel?.id) goFullScreen(ch) else tuneToChannel(ch)
            }
        )
        binding.channelList.adapter = channelAdapter

        val searchQuery = binding.channelSearch.text?.toString()?.trim() ?: ""
        if (displayedChannels.isEmpty() && searchQuery.isNotEmpty()) {
            binding.noResultsText.text = "No channels matching \"$searchQuery\""
            binding.noResultsText.visibility = View.VISIBLE
            binding.channelList.visibility = View.GONE
        } else {
            binding.noResultsText.visibility = View.GONE
            binding.channelList.visibility = View.VISIBLE
        }
    }

    private fun updateEpgStrip(channel: Channel) {
        val epg = epgData ?: return
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        val now = epg.getNowPlaying(channel.epgId.ifBlank { channel.id })
            ?: epg.getNowPlaying(channel.name)

        if (now != null) {
            binding.stripNowTitle.text = now.title
            binding.stripNowTime.text = "${fmt.format(now.startTime)} - ${fmt.format(now.endTime)}"
            binding.stripProgress.progress = (now.progress * 100).toInt()
        } else {
            binding.stripNowTitle.text = "No guide info"
            binding.stripNowTime.text = ""
            binding.stripProgress.progress = 0
        }

        val upcoming = epg.getUpcoming(channel.epgId.ifBlank { channel.id }, 3)
            .let { if (it.isEmpty()) epg.getUpcoming(channel.name, 3) else it }
        if (upcoming.isNotEmpty()) {
            val next = upcoming.first()
            binding.stripNextTitle.text = next.title
            binding.stripNextTime.text = fmt.format(next.startTime)
        } else {
            binding.stripNextTitle.text = ""
            binding.stripNextTime.text = ""
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                currentChannel?.let { goFullScreen(it) }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (binding.miniPlayer.hasFocus()) {
                    val idx = displayedChannels.indexOf(currentChannel)
                    if (idx > 0) tuneToChannel(displayedChannels[idx - 1])
                    true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (binding.miniPlayer.hasFocus()) {
                    val idx = displayedChannels.indexOf(currentChannel)
                    if (idx < displayedChannels.size - 1) tuneToChannel(displayedChannels[idx + 1])
                    true
                } else super.onKeyDown(keyCode, event)
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}

// --- Channel List Adapter (shared across some variants) ---

class LiveChannelAdapter(
    private val channels: List<Channel>,
    private val epgData: EpgData?,
    private val showEpg: Boolean,
    var currentChannel: Channel?,
    private val favoritesManager: com.vistacore.launcher.data.FavoritesManager,
    private val onFavoriteToggle: (String) -> Boolean,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<LiveChannelAdapter.VH>() {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val number: TextView = itemView.findViewById(R.id.ch_number)
        val logo: ImageView = itemView.findViewById(R.id.ch_logo)
        val name: TextView = itemView.findViewById(R.id.ch_name)
        val nowPlaying: TextView = itemView.findViewById(R.id.ch_now_playing)
        val favIcon: ImageView = itemView.findViewById(R.id.ch_fav_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_livetv_channel, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val channel = channels[position]
        holder.number.text = channel.number.toString()
        holder.name.text = channel.name

        val isCurrent = channel.id == currentChannel?.id
        holder.number.setTextColor(holder.itemView.context.getColor(
            if (isCurrent) R.color.accent_gold else R.color.text_hint
        ))
        holder.name.setTextColor(holder.itemView.context.getColor(
            if (isCurrent) R.color.accent_gold else R.color.text_primary
        ))

        if (channel.logoUrl.isNotBlank()) {
            Glide.with(holder.itemView.context).load(channel.logoUrl)
                .placeholder(R.drawable.ic_iptv).into(holder.logo)
        } else {
            holder.logo.setImageResource(R.drawable.ic_iptv)
        }

        if (showEpg && epgData != null) {
            val now = epgData.getNowPlaying(channel.epgId.ifBlank { channel.id })
                ?: epgData.getNowPlaying(channel.name)
            if (now != null) {
                holder.nowPlaying.text = now.title
                holder.nowPlaying.visibility = View.VISIBLE
            } else {
                holder.nowPlaying.visibility = View.GONE
            }
        } else {
            holder.nowPlaying.visibility = View.GONE
        }

        val isFav = favoritesManager.isFavoriteChannel(channel.id)
        holder.favIcon.visibility = if (isFav) View.VISIBLE else View.GONE
        if (isFav) holder.favIcon.setImageResource(R.drawable.ic_favorite)

        holder.itemView.setOnClickListener { onClick(channel) }
        holder.itemView.setOnLongClickListener {
            val nowFav = onFavoriteToggle(channel.id)
            if (nowFav) {
                holder.favIcon.setImageResource(R.drawable.ic_favorite)
                holder.favIcon.visibility = View.VISIBLE
            } else {
                holder.favIcon.visibility = View.GONE
            }
            true
        }
        holder.itemView.setOnFocusChangeListener { v, f ->
            MainActivity.animateFocus(v, f)
            val btn = (v.context as? BaseLiveTVActivity)?.findViewById<View>(R.id.btn_number_pad)
            if (btn != null) {
                btn.isFocusable = f
                btn.isFocusableInTouchMode = f
            }
        }
        holder.itemView.nextFocusRightId = R.id.btn_number_pad
    }

    override fun getItemCount() = channels.size
}

