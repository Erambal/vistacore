package com.vistacore.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.vistacore.launcher.apps.AppId
import com.vistacore.launcher.apps.AppLauncher
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.databinding.ActivityVoiceSearchBinding
import com.vistacore.launcher.iptv.*
import com.vistacore.launcher.system.ChannelUpdateWorker
import kotlinx.coroutines.*

class VoiceSearchActivity : BaseActivity() {

    private lateinit var binding: ActivityVoiceSearchBinding
    private lateinit var prefs: PrefsManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var allContent: List<Channel> = emptyList()
    private var searchJob: Job? = null
    // The cold-load coroutine is asynchronous; the search field is live the
    // moment onCreate finishes. Without these flags a fast-typer would hit
    // performSearch with allContent still empty, see "No results", and not
    // get a re-run when the load eventually completes.
    private var contentLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)
        binding.searchResults.layoutManager = LinearLayoutManager(this)

        // Load all content from cache. Apply the same restricted-rating
        // and hidden-category gates the browsers use so this shortcut
        // path can't surface titles the user has explicitly hidden.
        scope.launch {
            val tracker = com.vistacore.launcher.data.UsageTracker(this@VoiceSearchActivity)
            val hideRestricted = prefs.hideRestrictedRatings
            allContent = withContext(Dispatchers.IO) {
                val live = ChannelUpdateWorker.getCachedChannels(this@VoiceSearchActivity) ?: emptyList()
                val movies = ChannelUpdateWorker.getCachedMovies(this@VoiceSearchActivity) ?: emptyList()
                val series = ChannelUpdateWorker.getCachedSeries(this@VoiceSearchActivity) ?: emptyList()
                val merged = live + movies + series
                Discovery.applyRestrictedFilter(merged, hideRestricted)
                    .filter { !tracker.isCategoryHidden(it.category) }
            }
            contentLoaded = true
            // Re-run search if the user already typed while we were loading.
            val q = binding.searchInput.text?.toString()?.trim().orEmpty()
            if (q.length >= 2) performSearch(q)
        }

        // Setup app deep-link buttons
        setupAppButtons()

        // Live search as user types
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                if (query.length >= 2) {
                    performSearch(query)
                    showAppButtons(query)
                } else {
                    binding.searchResults.visibility = View.GONE
                    binding.noResults.visibility = View.VISIBLE
                    binding.noResults.text = "Type to search across all your content."
                    binding.resultsCount.text = ""
                    binding.appSearchRow.visibility = View.GONE
                }
            }
        })

        // Auto-focus the search field
        binding.searchInput.requestFocus()
    }

    private fun setupAppButtons() {
        if (AppLauncher.isAppInstalled(this, AppId.DISNEY_PLUS)) {
            binding.btnSearchDisney.visibility = View.VISIBLE
            binding.btnSearchDisney.setOnClickListener {
                AppLauncher.launchApp(this, AppId.DISNEY_PLUS)
            }
        }
        if (AppLauncher.isAppInstalled(this, AppId.ESPN)) {
            binding.btnSearchEspn.visibility = View.VISIBLE
            binding.btnSearchEspn.setOnClickListener {
                AppLauncher.launchApp(this, AppId.ESPN)
            }
        }
        if (AppLauncher.isAppInstalled(this, AppId.ROKU)) {
            binding.btnSearchRoku.visibility = View.VISIBLE
            binding.btnSearchRoku.setOnClickListener {
                AppLauncher.launchApp(this, AppId.ROKU)
            }
        }
    }

    private fun showAppButtons(query: String) {
        val hasApps = binding.btnSearchDisney.visibility == View.VISIBLE ||
                binding.btnSearchEspn.visibility == View.VISIBLE ||
                binding.btnSearchRoku.visibility == View.VISIBLE
        binding.appSearchRow.visibility = if (hasApps) View.VISIBLE else View.GONE
    }

    private fun performSearch(query: String) {
        if (!contentLoaded) {
            // Hold the search instead of painting a false "No results" —
            // the cold-load completion handler in onCreate re-runs the
            // search once allContent is populated.
            binding.searchResults.visibility = View.GONE
            binding.noResults.visibility = View.VISIBLE
            binding.noResults.text = "Loading content…"
            binding.resultsCount.text = ""
            return
        }
        searchJob?.cancel()
        searchJob = scope.launch {
            val results = withContext(Dispatchers.IO) {
                val q = query.lowercase()

                // Simple, direct name search — exact substring match, scored by relevance
                allContent
                    .filter { it.name.lowercase().contains(q) }
                    .sortedByDescending { item ->
                        val nameLower = item.name.lowercase()
                        when {
                            nameLower == q -> 100 // Exact match
                            nameLower.startsWith(q) -> 80 // Starts with
                            else -> 50 // Contains
                        }
                    }
                    .take(50) // Cap results for performance
            }

            // Deduplicate shows — only show one entry per show title
            val seen = mutableSetOf<String>()
            val deduped = results.filter { ch ->
                if (ch.contentType == ContentType.SERIES) {
                    val showName = extractShowName(ch.name)
                    seen.add(showName)
                } else true
            }

            if (deduped.isEmpty()) {
                binding.searchResults.visibility = View.GONE
                binding.noResults.visibility = View.VISIBLE
                binding.noResults.text = "No results for \"$query\""
                binding.resultsCount.text = ""
            } else {
                binding.noResults.visibility = View.GONE
                binding.searchResults.visibility = View.VISIBLE
                binding.resultsCount.text = "${deduped.size} result${if (deduped.size != 1) "s" else ""}"
                binding.searchResults.adapter = SearchAdapter(deduped) { onResultClicked(it) }
            }
        }
    }

    private val showNameStripper = Regex(
        """[\s.,-]*(?:[Ss]\d{1,2}[\s.,-]*[Ee]\d{1,3}|\d{1,2}[xX]\d{1,3}|[Ss]eason\s*\d+|\bEp\.?\s*\d+|\bEpisode\s*\d+).*""",
        RegexOption.IGNORE_CASE
    )
    private val showNameCleanup = Regex(
        """[\s-]+\d{1,3}\s*$|\s*[\(\[]\d{4}[\)\]]|\s*\b(?:HD|FHD|SD|4K|UHD)\b.*$""",
        RegexOption.IGNORE_CASE
    )

    private fun extractShowName(name: String): String {
        var cleaned = showNameStripper.replace(name, "")
        cleaned = showNameCleanup.replace(cleaned, "")
        return cleaned.trim().ifBlank { name.trim() }
    }

    private fun onResultClicked(channel: Channel) {
        when (channel.contentType) {
            ContentType.SERIES -> {
                val showName = extractShowName(channel.name)
                val episodes = allContent.filter {
                    it.contentType == ContentType.SERIES &&
                    extractShowName(it.name) == showName
                }
                // Forward the Xtream series id when present so ShowDetailActivity
                // can fetch the rich metadata block (plot, cast, rating, trailer)
                // regardless of how the user got here.
                val xtreamSeriesId = if (channel.id.startsWith("xt_series_"))
                    channel.id.removePrefix("xt_series_").toIntOrNull() ?: 0
                else 0
                ShowDetailActivity.launch(
                    this, showName, channel.category, channel.logoUrl, episodes,
                    xtreamSeriesId = xtreamSeriesId
                )
            }
            ContentType.MOVIE -> {
                // Route Xtream movies through MovieDetailActivity so the
                // per-title MPAA gate runs (applyMpaa disables Play when
                // Hide Restricted Ratings is on and the rating resolves
                // to R/TV-MA/etc.). Movies without a vod id (M3U-only,
                // Jellyfin) have no metadata source for a gate, so they
                // play directly — the name/category filter applied to
                // allContent above is the only protection in that case.
                val vodId = if (channel.id.startsWith("xt_vod_"))
                    channel.id.removePrefix("xt_vod_").toIntOrNull() ?: 0
                else 0
                if (vodId > 0) {
                    val year = Regex("""\((\d{4})\)""").find(channel.name)?.groupValues?.get(1).orEmpty()
                    MovieDetailActivity.launch(
                        activity = this,
                        title = channel.name,
                        category = channel.category,
                        posterUrl = channel.logoUrl,
                        streamUrl = channel.streamUrl,
                        vodId = vodId,
                        year = year
                    )
                } else {
                    startActivity(Intent(this, IPTVPlayerActivity::class.java).apply {
                        putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
                        putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                        putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, channel.logoUrl)
                        putExtra(IPTVPlayerActivity.EXTRA_IS_VOD, true)
                    })
                }
            }
            else -> {
                startActivity(Intent(this, IPTVPlayerActivity::class.java).apply {
                    putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
                    putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                    putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, channel.logoUrl)
                    putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

// --- Search Result Adapter ---

class SearchAdapter(
    private val items: List<Channel>,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<SearchAdapter.VH>() {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val poster: ImageView = itemView.findViewById(R.id.search_item_poster)
        val title: TextView = itemView.findViewById(R.id.search_item_title)
        val info: TextView = itemView.findViewById(R.id.search_item_info)
        val badge: TextView = itemView.findViewById(R.id.search_item_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.name
        holder.info.text = item.category

        holder.badge.text = when (item.contentType) {
            ContentType.MOVIE -> "MOVIE"
            ContentType.SERIES -> "SHOW"
            ContentType.LIVE -> "LIVE"
        }
        holder.badge.setTextColor(holder.itemView.context.getColor(when (item.contentType) {
            ContentType.MOVIE -> R.color.movies_amber
            ContentType.SERIES -> R.color.shows_indigo
            ContentType.LIVE -> R.color.status_online
        }))

        if (item.logoUrl.isNotBlank()) {
            Glide.with(holder.itemView.context)
                .load(item.logoUrl)
                .placeholder(R.drawable.ic_movies)
                .into(holder.poster)
        } else {
            holder.poster.setImageResource(when (item.contentType) {
                ContentType.MOVIE -> R.drawable.ic_movies
                ContentType.SERIES -> R.drawable.ic_tv_shows
                else -> R.drawable.ic_iptv
            })
        }

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    override fun getItemCount() = items.size
}
