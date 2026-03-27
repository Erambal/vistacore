package com.vistacore.launcher.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vistacore.launcher.R
import com.vistacore.launcher.databinding.ActivityShowDetailBinding
import com.vistacore.launcher.iptv.Channel

class ShowDetailActivity : BaseActivity() {

    companion object {
        private const val EXTRA_SHOW_NAME = "show_name"
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_POSTER = "poster_url"
        private const val EXTRA_EPISODES_JSON = "episodes_json"

        // Store episodes in a static field to avoid serialization limits
        private var pendingEpisodes: List<Channel> = emptyList()

        fun launch(activity: Activity, showName: String, category: String, posterUrl: String, episodes: List<Channel>) {
            pendingEpisodes = episodes
            val intent = Intent(activity, ShowDetailActivity::class.java).apply {
                putExtra(EXTRA_SHOW_NAME, showName)
                putExtra(EXTRA_CATEGORY, category)
                putExtra(EXTRA_POSTER, posterUrl)
            }
            activity.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityShowDetailBinding
    private var episodes: List<Channel> = emptyList()
    private var seasons: Map<Int, List<Channel>> = emptyMap()
    private var currentSeason: Int = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShowDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val showName = intent.getStringExtra(EXTRA_SHOW_NAME) ?: "Show"
        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: ""
        val posterUrl = intent.getStringExtra(EXTRA_POSTER) ?: ""

        episodes = pendingEpisodes
        pendingEpisodes = emptyList()

        binding.showTitle.text = showName
        binding.showCategory.text = category

        if (posterUrl.isNotBlank()) {
            Glide.with(this).load(posterUrl).into(binding.showPoster)
        }

        // Parse seasons from episode names
        seasons = parseSeasons(episodes)
        binding.showEpisodeCount.text = "${episodes.size} episode${if (episodes.size != 1) "s" else ""} · ${seasons.size} season${if (seasons.size != 1) "s" else ""}"

        setupSeasonTabs()
        showSeason(seasons.keys.minOrNull() ?: 1)
    }

    private fun parseSeasons(episodes: List<Channel>): Map<Int, List<Channel>> {
        val seasonMap = mutableMapOf<Int, MutableList<Channel>>()
        val seasonPattern = Regex("""[Ss](\d{1,2})""")

        for (episode in episodes) {
            val match = seasonPattern.find(episode.name)
            val seasonNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
            seasonMap.getOrPut(seasonNum) { mutableListOf() }.add(episode)
        }

        // Sort episodes within each season by episode number
        val episodePattern = Regex("""[Ee](\d{1,3})""")
        return seasonMap.mapValues { (_, eps) ->
            eps.sortedBy { ep ->
                episodePattern.find(ep.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        }.toSortedMap()
    }

    private fun setupSeasonTabs() {
        binding.seasonTabs.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.seasonTabs.adapter = SeasonTabAdapter(seasons.keys.sorted()) { season ->
            currentSeason = season
            showSeason(season)
        }
    }

    private fun showSeason(season: Int) {
        currentSeason = season
        val eps = seasons[season] ?: emptyList()
        binding.episodeList.layoutManager = LinearLayoutManager(this)
        binding.episodeList.adapter = EpisodeAdapter(eps) { episode ->
            val intent = Intent(this, IPTVPlayerActivity::class.java).apply {
                putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, episode.streamUrl)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, episode.name)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, episode.logoUrl)
                putExtra(IPTVPlayerActivity.EXTRA_IS_VOD, true)
            }
            startActivity(intent)
        }
    }
}

// --- Season Tab Adapter ---

class SeasonTabAdapter(
    private val seasons: List<Int>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<SeasonTabAdapter.VH>() {

    private var selectedPos = 0

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val label: TextView = itemView.findViewById(R.id.season_label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_season_tab, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val season = seasons[position]
        holder.label.text = "Season $season"
        holder.label.setTextColor(holder.itemView.context.getColor(
            if (position == selectedPos) R.color.accent_gold else R.color.text_secondary
        ))

        holder.itemView.setOnClickListener {
            val prev = selectedPos
            selectedPos = position
            notifyItemChanged(prev)
            notifyItemChanged(position)
            onClick(season)
        }
        holder.itemView.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    override fun getItemCount() = seasons.size
}

// --- Episode Adapter ---

class EpisodeAdapter(
    private val episodes: List<Channel>,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumb: ImageView = itemView.findViewById(R.id.episode_thumb)
        val title: TextView = itemView.findViewById(R.id.episode_title)
        val info: TextView = itemView.findViewById(R.id.episode_info)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        return VH(view)
    }

    private fun formatEpisodeName(rawName: String): String {
        // Extract episode number and title from formats like:
        // "Bluey S01E03 - Bluey - S01E03 - Keepy Uppy"
        // "Show Name S01E03 - Episode Title"
        // "Show Name - S01E03"

        // Try to find SxxExx pattern and get the episode number
        val seMatch = Regex("""[Ss](\d{1,2})[Ee](\d{1,3})""").find(rawName)
        val epNum = seMatch?.groupValues?.get(2)?.toIntOrNull()

        // Find everything after the last SxxExx or show-name pattern
        var title = rawName
        // Strip everything up to and including the last SxxExx occurrence
        val lastSE = Regex(""".*[Ss]\d{1,2}[Ee]\d{1,3}\s*[-–.]\s*""").find(rawName)
        if (lastSE != null) {
            title = rawName.substring(lastSE.range.last + 1).trim()
        }
        // If title still has "ShowName - " prefix, strip it
        val dashParts = title.split(Regex("""\s*[-–]\s*"""))
        if (dashParts.size > 1) {
            // Take the last meaningful part as the episode title
            title = dashParts.last().trim()
        }
        if (title.isBlank()) title = rawName

        return if (epNum != null) {
            "Episode ${String.format("%02d", epNum)} - $title"
        } else {
            title
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val episode = episodes[position]
        holder.title.text = formatEpisodeName(episode.name)
        holder.info.text = episode.category

        if (episode.logoUrl.isNotBlank()) {
            Glide.with(holder.itemView.context)
                .load(episode.logoUrl)
                .placeholder(R.drawable.ic_tv_shows)
                .into(holder.thumb)
        } else {
            holder.thumb.setImageResource(R.drawable.ic_tv_shows)
        }

        holder.itemView.setOnClickListener { onClick(episode) }
        holder.itemView.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    override fun getItemCount() = episodes.size
}
