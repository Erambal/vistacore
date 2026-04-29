package com.vistacore.launcher.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vistacore.launcher.R
import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.EpgData
import java.text.SimpleDateFormat
import java.util.*

/**
 * EPG-first layout. The guide IS the main screen. Each row shows the channel
 * along with what's playing now and what's next. Selecting a row tunes the
 * preview player at the top.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveTVEpgActivity : BaseLiveTVActivity() {

    private lateinit var miniPlayer: androidx.media3.ui.PlayerView
    private lateinit var channelName: TextView
    private lateinit var programTitle: TextView
    private lateinit var programDesc: TextView
    private lateinit var channelSearch: EditText
    private lateinit var categoryPicker: android.widget.Button
    private lateinit var channelList: RecyclerView
    private lateinit var loadingView: View
    private lateinit var noResultsText: TextView

    private var epgAdapter: LiveTVChannelRowAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_livetv_epg)

        miniPlayer = findViewById(R.id.epg_mini_player)
        channelName = findViewById(R.id.epg_channel_name)
        programTitle = findViewById(R.id.epg_program_title)
        programDesc = findViewById(R.id.epg_program_desc)
        channelSearch = findViewById(R.id.epg_channel_search)
        categoryPicker = findViewById(R.id.epg_category_chips)
        channelList = findViewById(R.id.epg_channel_list)
        loadingView = findViewById(R.id.epg_loading)
        noResultsText = findViewById(R.id.epg_no_results_text)

        channelList.layoutManager = LinearLayoutManager(this)

        channelSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterChannels(s?.toString()?.trim() ?: "")
            }
        })

        findViewById<View>(R.id.epg_btn_number_pad).setOnClickListener { showNumberPadOverlay() }

        intent.getStringExtra(EXTRA_SEARCH_QUERY)?.let { query ->
            if (query.isNotBlank()) {
                channelSearch.setText(query)
                channelSearch.clearFocus()
                channelList.requestFocus()
                window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            }
        }

        setupPlayer(miniPlayer)
        loadChannels()
    }

    override fun onChannelsLoaded() {
        refreshList()
        channelList.post {
            channelList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    override fun onCategoriesChanged(categories: List<String>) {
        bindCategoryButton(categoryPicker, categories)
    }

    override fun currentSearchQuery(): String =
        channelSearch.text?.toString()?.trim().orEmpty()

    override fun onDisplayedChannelsChanged() {
        refreshList()
    }

    override fun onSelectedChannelChanged(previous: Channel?, current: Channel) {
        channelName.text = current.name
        updateProgramInfo(current)
    }

    override fun onEpgLoaded() {
        refreshList()
        currentChannel?.let { updateProgramInfo(it) }
    }

    override fun onLoadingStateChanged(loading: Boolean) {
        loadingView.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun refreshList() {
        epgAdapter = LiveTVChannelRowAdapter(
            displayedChannels, epgData, favoritesManager,
            onFavoriteToggle = { id -> toggleChannelFavorite(id) },
            onClick = { ch ->
                if (ch.id == currentChannel?.id) goFullScreen(ch) else tuneToChannel(ch)
            }
        )
        channelList.adapter = epgAdapter

        val q = channelSearch.text?.toString()?.trim().orEmpty()
        if (displayedChannels.isEmpty() && q.isNotEmpty()) {
            noResultsText.text = "No channels matching \"$q\""
            noResultsText.visibility = View.VISIBLE
            channelList.visibility = View.GONE
        } else {
            noResultsText.visibility = View.GONE
            channelList.visibility = View.VISIBLE
        }
    }

    private fun updateProgramInfo(channel: Channel) {
        val epg = epgData
        if (epg != null) {
            val now = epg.getNowPlaying(channel.epgId.ifBlank { channel.id })
                ?: epg.getNowPlaying(channel.name)
            if (now != null) {
                val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                programTitle.text = "${now.title}  ·  ${fmt.format(now.startTime)} - ${fmt.format(now.endTime)}"
                programDesc.text = now.description.ifBlank { "" }
            } else {
                programTitle.text = "No guide info"
                programDesc.text = ""
            }
        } else {
            programTitle.text = ""
            programDesc.text = ""
        }
    }
}

class LiveTVChannelRowAdapter(
    private val channels: List<Channel>,
    private val epgData: EpgData?,
    private val favoritesManager: com.vistacore.launcher.data.FavoritesManager,
    private val onFavoriteToggle: (String) -> Boolean,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<LiveTVChannelRowAdapter.VH>() {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val number: TextView = itemView.findViewById(R.id.lvrow_number)
        val logo: ImageView = itemView.findViewById(R.id.lvrow_logo)
        val name: TextView = itemView.findViewById(R.id.lvrow_name)
        val now: TextView = itemView.findViewById(R.id.lvrow_now)
        val next: TextView = itemView.findViewById(R.id.lvrow_next)
        val progress: ProgressBar = itemView.findViewById(R.id.lvrow_progress)
        val favIcon: ImageView = itemView.findViewById(R.id.lvrow_fav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_livetv_epg_row, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val channel = channels[position]
        holder.number.text = channel.number.toString()
        holder.name.text = channel.name

        if (channel.logoUrl.isNotBlank()) {
            Glide.with(holder.itemView.context).load(channel.logoUrl)
                .placeholder(R.drawable.ic_iptv).into(holder.logo)
        } else {
            holder.logo.setImageResource(R.drawable.ic_iptv)
        }

        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        if (epgData != null) {
            val nowProgram = epgData.getNowPlaying(channel.epgId.ifBlank { channel.id })
                ?: epgData.getNowPlaying(channel.name)
            if (nowProgram != null) {
                holder.now.text = nowProgram.title
                holder.progress.progress = (nowProgram.progress * 100).toInt()
                holder.progress.visibility = View.VISIBLE
            } else {
                holder.now.text = "—"
                holder.progress.visibility = View.INVISIBLE
            }

            val upcoming = epgData.getUpcoming(channel.epgId.ifBlank { channel.id }, 1)
                .let { if (it.isEmpty()) epgData.getUpcoming(channel.name, 1) else it }
            holder.next.text = if (upcoming.isNotEmpty()) {
                "Next: ${upcoming[0].title} · ${fmt.format(upcoming[0].startTime)}"
            } else ""
        } else {
            holder.now.text = "—"
            holder.next.text = ""
            holder.progress.visibility = View.INVISIBLE
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
