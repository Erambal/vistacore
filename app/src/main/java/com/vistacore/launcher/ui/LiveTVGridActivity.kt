package com.vistacore.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vistacore.launcher.R
import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.EpgData
import java.text.SimpleDateFormat
import java.util.*

/**
 * Grid (tile wall) layout. Channels shown as big logo cards in a grid.
 * Top bar has a preview player, current channel info, and controls.
 * Category tabs run across the middle.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveTVGridActivity : BaseLiveTVActivity() {

    private lateinit var miniPlayer: androidx.media3.ui.PlayerView
    private lateinit var channelName: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nextTitle: TextView
    private lateinit var channelSearch: EditText
    private lateinit var categoryPicker: android.widget.Button
    private lateinit var channelGrid: RecyclerView
    private lateinit var loadingView: View

    private var gridAdapter: ChannelTileAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_livetv_grid)

        miniPlayer = findViewById(R.id.grid_mini_player)
        channelName = findViewById(R.id.grid_channel_name)
        nowTitle = findViewById(R.id.grid_now_title)
        nextTitle = findViewById(R.id.grid_next_title)
        channelSearch = findViewById(R.id.grid_channel_search)
        categoryPicker = findViewById(R.id.grid_category_chips)
        channelGrid = findViewById(R.id.grid_channel_grid)
        loadingView = findViewById(R.id.grid_loading)

        channelGrid.layoutManager = GridLayoutManager(this, 6)

        channelSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterChannels(s?.toString()?.trim() ?: "")
            }
        })

        findViewById<View>(R.id.grid_btn_guide).setOnClickListener {
            startActivity(Intent(this, EpgGuideActivity::class.java))
        }
        findViewById<View>(R.id.grid_btn_number_pad).setOnClickListener { showNumberPadOverlay() }

        setupPlayer(miniPlayer)
        loadChannels()
    }

    override fun onChannelsLoaded() {
        refreshGrid()
        channelGrid.post {
            channelGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    override fun onCategoriesChanged(categories: List<String>) {
        bindCategoryButton(categoryPicker, categories)
    }

    override fun onDisplayedChannelsChanged() {
        refreshGrid()
    }

    override fun onSelectedChannelChanged(previous: Channel?, current: Channel) {
        channelName.text = current.name
        updatePreview(current)
        gridAdapter?.let { adapter ->
            adapter.currentChannel = current
            val oldIdx = displayedChannels.indexOf(previous)
            val newIdx = displayedChannels.indexOf(current)
            if (oldIdx >= 0) adapter.notifyItemChanged(oldIdx)
            if (newIdx >= 0) adapter.notifyItemChanged(newIdx)
        }
    }

    override fun onEpgLoaded() {
        refreshGrid()
        currentChannel?.let { updatePreview(it) }
    }

    override fun onLoadingStateChanged(loading: Boolean) {
        loadingView.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun refreshGrid() {
        gridAdapter = ChannelTileAdapter(displayedChannels, epgData, currentChannel, favoritesManager) { ch ->
            if (ch.id == currentChannel?.id) goFullScreen(ch) else tuneToChannel(ch)
        }
        channelGrid.adapter = gridAdapter
    }

    private fun updatePreview(channel: Channel) {
        val epg = epgData
        if (epg != null) {
            val now = epg.getNowPlaying(channel.epgId.ifBlank { channel.id }) ?: epg.getNowPlaying(channel.name)
            nowTitle.text = now?.title ?: "No guide info"
            val upcoming = epg.getUpcoming(channel.epgId.ifBlank { channel.id }, 1)
                .let { if (it.isEmpty()) epg.getUpcoming(channel.name, 1) else it }
            nextTitle.text = if (upcoming.isNotEmpty()) {
                val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                "Next: ${upcoming[0].title} at ${fmt.format(upcoming[0].startTime)}"
            } else ""
        } else {
            nowTitle.text = ""
            nextTitle.text = ""
        }
    }
}

class ChannelTileAdapter(
    private val channels: List<Channel>,
    private val epgData: EpgData?,
    var currentChannel: Channel?,
    private val favoritesManager: com.vistacore.launcher.data.FavoritesManager,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelTileAdapter.VH>() {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val logo: ImageView = itemView.findViewById(R.id.tile_logo)
        val number: TextView = itemView.findViewById(R.id.tile_number)
        val name: TextView = itemView.findViewById(R.id.tile_name)
        val now: TextView = itemView.findViewById(R.id.tile_now)
        val favIcon: ImageView = itemView.findViewById(R.id.tile_fav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_tile, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val channel = channels[position]
        holder.number.text = channel.number.toString()
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

        if (epgData != null) {
            val nowProgram = epgData.getNowPlaying(channel.epgId.ifBlank { channel.id })
                ?: epgData.getNowPlaying(channel.name)
            holder.now.text = nowProgram?.title ?: ""
        } else {
            holder.now.text = ""
        }

        holder.favIcon.visibility =
            if (favoritesManager.isFavoriteChannel(channel.id)) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onClick(channel) }
        holder.itemView.setOnLongClickListener {
            val nowFav = favoritesManager.toggleFavoriteChannel(channel.id)
            holder.favIcon.visibility = if (nowFav) View.VISIBLE else View.GONE
            true
        }
        holder.itemView.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    override fun getItemCount() = channels.size
}
