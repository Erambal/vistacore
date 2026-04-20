package com.vistacore.launcher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vistacore.launcher.R
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.databinding.ActivityShowDetailBinding
import com.vistacore.launcher.iptv.CastMember
import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.SeriesDetail
import com.vistacore.launcher.iptv.TmdbClient
import com.vistacore.launcher.iptv.TmdbType
import com.vistacore.launcher.iptv.XtreamAuth
import com.vistacore.launcher.iptv.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShowDetailActivity : BaseActivity() {

    companion object {
        private const val EXTRA_SHOW_NAME = "show_name"
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_POSTER = "poster_url"
        private const val EXTRA_XTREAM_ID = "xtream_series_id"

        // Episodes can be a long list; passing via Intent runs into the
        // Binder transaction cap on weak devices, so we stash them here.
        private var pendingEpisodes: List<Channel> = emptyList()

        fun launch(
            activity: Activity,
            showName: String,
            category: String,
            posterUrl: String,
            episodes: List<Channel>,
            xtreamSeriesId: Int = 0
        ) {
            pendingEpisodes = episodes
            val intent = Intent(activity, ShowDetailActivity::class.java).apply {
                putExtra(EXTRA_SHOW_NAME, showName)
                putExtra(EXTRA_CATEGORY, category)
                putExtra(EXTRA_POSTER, posterUrl)
                if (xtreamSeriesId > 0) putExtra(EXTRA_XTREAM_ID, xtreamSeriesId)
            }
            activity.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityShowDetailBinding
    private var episodes: List<Channel> = emptyList()
    private var seasons: Map<Int, List<Channel>> = emptyMap()
    private var currentSeason: Int = 1
    private var xtreamSeriesId: Int = 0
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShowDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val showName = intent.getStringExtra(EXTRA_SHOW_NAME) ?: "Show"
        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: ""
        val posterUrl = intent.getStringExtra(EXTRA_POSTER) ?: ""
        xtreamSeriesId = intent.getIntExtra(EXTRA_XTREAM_ID, 0)

        episodes = pendingEpisodes
        pendingEpisodes = emptyList()

        binding.showTitle.text = showName
        binding.showCategory.text = category

        if (posterUrl.isNotBlank()) {
            Glide.with(this).load(posterUrl).into(binding.showPoster)
            Glide.with(this).load(posterUrl).into(binding.detailBackdrop)
        }

        seasons = parseSeasons(episodes)
        binding.showEpisodeCount.text = "${episodes.size} episode${if (episodes.size != 1) "s" else ""} · ${seasons.size} season${if (seasons.size != 1) "s" else ""}"

        setupSeasonTabs()
        showSeason(seasons.keys.minOrNull() ?: 1)

        binding.castList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        binding.trailerCloseBtn.setOnClickListener { hideFullscreenTrailer() }

        // Kick off the rich-metadata fetch in the background. If it
        // succeeds, we'll swap in plot/rating/badges/cast above the
        // seasons. If it fails (M3U-only provider, no Xtream, network
        // error), the screen just stays on the baseline layout.
        if (xtreamSeriesId > 0) {
            loadSeriesMetadata(showName)
        }
    }

    override fun onBackPressed() {
        if (binding.trailerOverlay.visibility == View.VISIBLE) {
            hideFullscreenTrailer()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private var resolvedTrailerId: String? = null

    private fun showFullscreenTrailer() {
        val id = resolvedTrailerId ?: return
        binding.trailerOverlay.visibility = View.VISIBLE
        TrailerPlayer.configureFullscreen(binding.trailerFullscreen, id)
        binding.trailerCloseBtn.requestFocus()
    }

    private fun hideFullscreenTrailer() {
        TrailerPlayer.stop(binding.trailerFullscreen)
        binding.trailerOverlay.visibility = View.GONE
    }

    /**
     * Fetch rich series info + TMDB cast credits. Runs on a background
     * thread; UI updates happen on main. Any failure is silent — the
     * screen degrades to the baseline layout.
     */
    private fun loadSeriesMetadata(showName: String) {
        scope.launch {
            val detail = withContext(Dispatchers.IO) {
                try {
                    val prefs = PrefsManager(this@ShowDetailActivity)
                    val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                    XtreamClient(auth).getSeriesDetail(xtreamSeriesId)
                } catch (_: Exception) { null }
            } ?: return@launch

            applyDetail(detail)

            val tmdbIdResolved = withContext(Dispatchers.IO) {
                try {
                    val tmdb = TmdbClient(this@ShowDetailActivity)
                    detail.tmdbId.toIntOrNull()
                        ?: tmdb.searchId(showName, detail.year, TmdbType.TV)
                } catch (_: Exception) { null }
            }

            val cast = if (tmdbIdResolved != null) {
                withContext(Dispatchers.IO) {
                    try {
                        TmdbClient(this@ShowDetailActivity).getCredits(tmdbIdResolved, TmdbType.TV)
                    } catch (_: Exception) { emptyList() }
                }
            } else emptyList()

            // Xtream providers rarely populate mpaa_rating on series.
            // Fall back to TMDB's /content_ratings so the "Rated TV-MA"
            // line shows up for the whole catalog.
            if (detail.mpaa.isBlank() && tmdbIdResolved != null) {
                val cert = withContext(Dispatchers.IO) {
                    try {
                        TmdbClient(this@ShowDetailActivity)
                            .getCertification(tmdbIdResolved, TmdbType.TV)
                    } catch (_: Exception) { null }
                }
                if (!cert.isNullOrBlank()) applyMpaa(cert)
            }

            val fallback = fallbackCast(detail.cast)
            val render = if (cast.isNotEmpty()) cast else fallback
            if (render.isNotEmpty()) {
                binding.castTitle.visibility = View.VISIBLE
                binding.castList.visibility = View.VISIBLE
                binding.castList.adapter = CastAdapter(render)
            }

            val ytId = TrailerPlayer.extractId(detail.trailer) ?: run {
                if (tmdbIdResolved == null) null else withContext(Dispatchers.IO) {
                    try {
                        TmdbClient(this@ShowDetailActivity)
                            .getTrailerYoutubeId(tmdbIdResolved, TmdbType.TV)
                    } catch (_: Exception) { null }
                }
            }
            if (!ytId.isNullOrBlank()) onTrailerResolved(ytId)
        }
    }

    private fun onTrailerResolved(youtubeId: String) {
        resolvedTrailerId = youtubeId
        binding.showTrailerBtn.visibility = View.VISIBLE
        binding.showTrailerBtn.setOnClickListener { showFullscreenTrailer() }
        if (PrefsManager(this).bannerAutoplayTrailer) {
            binding.detailBackdropTrailer.visibility = View.VISIBLE
            TrailerPlayer.configureBackdropPreview(binding.detailBackdropTrailer, youtubeId)
            binding.detailBackdropTrailer.animate()
                .alpha(1f).setDuration(600).setStartDelay(1500).start()
        }
    }

    private fun applyDetail(d: SeriesDetail) {
        if (d.tagline.isNotBlank()) {
            binding.showTagline.text = "\"${d.tagline}\""
            binding.showTagline.visibility = View.VISIBLE
        }
        applyMpaa(d.mpaa)
        DetailBinders.renderBadges(
            binding.showBadges,
            rating = d.rating,
            mpaa = d.mpaa,
            runtime = DetailBinders.formatRuntime("", 0L, d.episodeRunTime),
            year = d.year
        )
        val meta = DetailBinders.buildMetaLine(d.genre, d.country, d.director)
        if (meta.isNotBlank()) {
            binding.showMeta.text = meta
            binding.showMeta.visibility = View.VISIBLE
        } else binding.showMeta.visibility = View.GONE

        if (d.plot.isNotBlank()) {
            binding.showPlot.text = d.plot
            binding.showPlot.visibility = View.VISIBLE
        } else binding.showPlot.visibility = View.GONE

        if (d.backdropUrl.isNotBlank()) {
            Glide.with(this).load(d.backdropUrl).into(binding.detailBackdrop)
        }
        if (d.posterUrl.isNotBlank()) {
            Glide.with(this).load(d.posterUrl).into(binding.showPoster)
        }

        val trailerUrl = resolveTrailerUrl(d.trailer)
        if (trailerUrl != null) {
            binding.showTrailerBtn.visibility = View.VISIBLE
            binding.showTrailerBtn.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trailerUrl)))
            }
        }
    }

    /**
     * Show the "Rated TV-MA" line. Called from both applyDetail (Xtream
     * data) and the TMDB certification fallback. Leaves the badge row
     * alone — that's owned by applyDetail.
     */
    private fun applyMpaa(mpaa: String) {
        if (mpaa.isBlank()) return
        DetailBinders.formatRatedLine(mpaa)?.let { line ->
            binding.showRatedLine.text = line
            binding.showRatedLine.visibility = View.VISIBLE
        }
    }

    private fun fallbackCast(castStr: String): List<CastMember> {
        if (castStr.isBlank()) return emptyList()
        return castStr.split(Regex("""\s*,\s*"""))
            .mapNotNull { it.trim().ifBlank { null } }
            .take(12)
            .map { CastMember(name = it, character = "", profileUrl = "") }
    }

    /**
     * Xtream's youtube_trailer is usually a bare video id; occasionally a
     * full URL. Accept both.
     */
    private fun resolveTrailerUrl(raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return "https://www.youtube.com/watch?v=${Uri.encode(raw)}"
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
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
            // Build a queue from the current season so the player can
            // auto-advance through the season on episode end.
            val queueJson = org.json.JSONArray().apply {
                for (ep in eps) {
                    put(org.json.JSONObject().apply {
                        put("url", ep.streamUrl)
                        put("name", ep.name)
                    })
                }
            }.toString()
            val idx = eps.indexOfFirst { it.streamUrl == episode.streamUrl }
            val intent = Intent(this, IPTVPlayerActivity::class.java).apply {
                putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, episode.streamUrl)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, episode.name)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, episode.logoUrl)
                putExtra(IPTVPlayerActivity.EXTRA_IS_VOD, true)
                putExtra(IPTVPlayerActivity.EXTRA_EPISODE_QUEUE_JSON, queueJson)
                putExtra(IPTVPlayerActivity.EXTRA_EPISODE_INDEX, idx.coerceAtLeast(0))
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
