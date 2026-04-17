package com.vistacore.launcher.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vistacore.launcher.R
import com.vistacore.launcher.iptv.Channel

/**
 * Netflix-style carousel layout. Hero card on top showing current channel,
 * horizontal rows below grouped by category (Favorites, Recent, News, Sports…).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveTVCarouselActivity : BaseLiveTVActivity() {

    private lateinit var heroPlayer: androidx.media3.ui.PlayerView
    private lateinit var heroTitle: TextView
    private lateinit var heroSubtitle: TextView
    private lateinit var channelSearch: EditText
    private lateinit var rowsList: RecyclerView
    private lateinit var loadingView: View

    private var rowsAdapter: CarouselRowsAdapter? = null
    private data class Row(val title: String, val channels: List<Channel>)
    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_livetv_carousel)

        heroPlayer = findViewById(R.id.car_hero_player)
        heroTitle = findViewById(R.id.car_hero_title)
        heroSubtitle = findViewById(R.id.car_hero_subtitle)
        channelSearch = findViewById(R.id.car_channel_search)
        rowsList = findViewById(R.id.car_rows)
        loadingView = findViewById(R.id.car_loading)

        rowsList.layoutManager = LinearLayoutManager(this)

        channelSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterChannels(s?.toString()?.trim() ?: "")
            }
        })

        findViewById<Button>(R.id.car_btn_fullscreen).setOnClickListener {
            currentChannel?.let { goFullScreen(it) }
        }
        findViewById<Button>(R.id.car_btn_number_pad).setOnClickListener { showNumberPadOverlay() }

        setupPlayer(heroPlayer)
        loadChannels()
    }

    override fun onChannelsLoaded() {
        rebuildRows()
    }

    override fun onCategoriesChanged(categories: List<String>) {
        rebuildRows()
    }

    override fun onDisplayedChannelsChanged() {
        rebuildRows()
    }

    override fun onSelectedChannelChanged(previous: Channel?, current: Channel) {
        heroTitle.text = current.name
        updateHeroSubtitle(current)
    }

    override fun onEpgLoaded() {
        currentChannel?.let { updateHeroSubtitle(it) }
    }

    override fun onLoadingStateChanged(loading: Boolean) {
        loadingView.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun updateHeroSubtitle(channel: Channel) {
        val epg = epgData
        val now = epg?.getNowPlaying(channel.epgId.ifBlank { channel.id })
            ?: epg?.getNowPlaying(channel.name)
        heroSubtitle.text = now?.title ?: "CH ${channel.number}  ·  ${channel.category}"
    }

    private fun rebuildRows() {
        val r = mutableListOf<Row>()
        val source = displayedChannels

        // Favorites row
        val favs = favoritesManager.filterFavorites(source)
        if (favs.isNotEmpty()) r.add(Row("Favorites", favs))

        // Recents row
        val recs = recents.getRecentChannels(source)
        if (recs.isNotEmpty()) r.add(Row("Recently Watched", recs))

        // Category rows
        val categories = source.map { it.category }.distinct().sorted()
        for (cat in categories) {
            val channels = source.filter { it.category == cat }
            if (channels.isNotEmpty()) r.add(Row(cat, channels))
        }

        rows = r
        rowsAdapter = CarouselRowsAdapter(rows.map { it.title to it.channels }, currentChannel) { ch ->
            if (ch.id == currentChannel?.id) goFullScreen(ch) else tuneToChannel(ch)
        }
        rowsList.adapter = rowsAdapter
    }
}

class CarouselRowsAdapter(
    private val rows: List<Pair<String, List<Channel>>>,
    private val currentChannel: Channel?,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<CarouselRowsAdapter.VH>() {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.crow_title)
        val list: RecyclerView = itemView.findViewById(R.id.crow_list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_carousel_row, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (title, channels) = rows[position]
        holder.title.text = title
        holder.list.layoutManager = LinearLayoutManager(holder.itemView.context, LinearLayoutManager.HORIZONTAL, false)
        holder.list.adapter = ChannelRibbonAdapter(channels, currentChannel, onClick)
    }

    override fun getItemCount() = rows.size
}
