package com.vistacore.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vistacore.launcher.R
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.databinding.ActivityVodNetflixBinding
import com.vistacore.launcher.data.ContentCache
import com.vistacore.launcher.iptv.*
import com.vistacore.launcher.system.ChannelUpdateWorker
import kotlinx.coroutines.*

class VODBrowserActivity : BaseActivity() {

    companion object {
        const val EXTRA_CONTENT_TYPE = "content_type"
        const val EXTRA_FILTER = "filter"
        const val TYPE_MOVIES = "movies"
        const val TYPE_SHOWS = "shows"
        const val FILTER_KIDS = "kids"
        private const val BANNER_ROTATE_MS = 10000L
    }

    private lateinit var binding: ActivityVodNetflixBinding
    private lateinit var prefs: PrefsManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var allItems: List<Channel> = emptyList()
    private var contentType: String = TYPE_MOVIES
    private var filter: String? = null
    private var bannerItems: List<Channel> = emptyList()
    private var bannerIndex = 0
    private var bannerRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVodNetflixBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)
        contentType = intent.getStringExtra(EXTRA_CONTENT_TYPE) ?: TYPE_MOVIES
        filter = intent.getStringExtra(EXTRA_FILTER)

        binding.netflixList.layoutManager = LinearLayoutManager(this)

        setupSearch()
        loadContent()
    }

    private fun setupSearch() {
        binding.netflixSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                if (query.length >= 2) {
                    showSearchResults(query)
                } else if (query.isEmpty()) {
                    scope.launch {
                        val rows = withContext(Dispatchers.IO) { buildRows(allItems) }
                        showNetflixRows(rows)
                    }
                }
            }
        })
    }

    private fun loadContent() {
        // Check preloaded cache first (instant)
        val preloadedRows = if (contentType == TYPE_MOVIES) ContentCache.movieRows else ContentCache.showRows
        val preloadedItems = if (contentType == TYPE_MOVIES) ContentCache.movieItems else ContentCache.showItems

        if (preloadedRows != null && preloadedItems != null && preloadedRows.isNotEmpty()) {
            allItems = preloadedItems
            if (contentType == TYPE_SHOWS) {
                showEpisodesIndex = ContentCache.showEpisodesIndex
            }
            showNetflixRows(preloadedRows)
            return
        }

        // Fallback: read from cache file
        showLoading(true)

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val cached = if (contentType == TYPE_MOVIES)
                    ChannelUpdateWorker.getCachedMovies(this@VODBrowserActivity)
                else
                    ChannelUpdateWorker.getCachedSeries(this@VODBrowserActivity)

                if (cached != null && cached.isNotEmpty()) {
                    val filtered = applyFilter(cached)
                    if (contentType == TYPE_SHOWS) {
                        // Try loading precomputed show names from disk (no regex needed)
                        var nameMap = ContentCache.showNameMap
                            ?: ContentCache.loadShowNameMap(this@VODBrowserActivity)

                        if (nameMap == null || nameMap.size < filtered.size / 2) {
                            // No cached map or too stale — compute (only happens once)
                            val computed = HashMap<String, String>(filtered.size)
                            for (ch in filtered) computed[ch.id] = extractShowName(ch.name)
                            nameMap = computed
                            ContentCache.saveShowNameMap(this@VODBrowserActivity, computed)
                        }

                        ContentCache.showNameMap = nameMap
                        precomputedShowNames = nameMap

                        val idx = HashMap<String, MutableList<Channel>>()
                        for (ch in filtered) {
                            idx.getOrPut(nameMap[ch.id] ?: ch.name) { mutableListOf() }.add(ch)
                        }
                        showEpisodesIndex = idx
                    }
                    val rows = buildRows(filtered)
                    Pair(filtered, rows)
                } else null
            }

            if (result != null) {
                allItems = result.first
                showLoading(false)
                showNetflixRows(result.second)
            } else {
                ChannelUpdateWorker.refreshNow(this@VODBrowserActivity)
                showLoading(false)
                binding.netflixEmpty.text = "Content is being downloaded.\nThis may take 2–3 minutes. Please wait or press Back and try again."
                showEmpty()
            }
        }
    }

    private fun applyFilter(items: List<Channel>): List<Channel> {
        if (filter != FILTER_KIDS) return items
        val kidsKeywords = listOf("kids", "family", "animation", "cartoon", "disney", "pixar",
            "children", "nickelodeon", "nick", "junior", "jr", "baby", "sesame", "paw patrol")
        return items.filter { ch ->
            val cat = ch.category.lowercase()
            val name = ch.name.lowercase()
            kidsKeywords.any { cat.contains(it) || name.contains(it) }
        }
    }

    private fun refreshInBackground() {
        ChannelUpdateWorker.refreshNow(this)
    }

    // --- Netflix Layout Builder ---

    /** For shows, deduplicate episodes → one entry per show (first episode as representative) */
    private fun deduplicateShows(items: List<Channel>): List<Channel> {
        if (contentType != TYPE_SHOWS) return items
        val nameMap = precomputedShowNames
        val seen = mutableSetOf<String>()
        return items.filter { ch ->
            val showName = nameMap?.get(ch.id) ?: extractShowName(ch.name)
            seen.add(showName)
        }
    }

    /** Build rows on background thread — no UI access. Limits to top categories for speed. */
    private fun buildRows(items: List<Channel>): List<NetflixRow> {
        if (items.isEmpty()) return emptyList()

        val displayItems = deduplicateShows(items)
        val rows = mutableListOf<NetflixRow>()

        // Banner: pick random items with posters (sample from first 500 to avoid scanning all 60K)
        val samplePool = displayItems.take(500)
        val withPosters = samplePool.filter { it.logoUrl.isNotBlank() }.shuffled().take(5)
        if (withPosters.isNotEmpty()) {
            bannerItems = withPosters
            rows.add(NetflixRow.Banner(withPosters.first()))
        }

        // Group by category, filter hidden, sort by user usage then size
        val tracker = com.vistacore.launcher.data.UsageTracker(this)
        val grouped = displayItems.groupBy { it.category }
            .filter { !tracker.isCategoryHidden(it.key) } // Remove hidden categories

        // Boost categories matching the app language, then most-used, then by size
        val langKeywords = languageKeywords()
        val sortedCategories = grouped.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<Channel>>> { entry ->
                val catLower = entry.key.lowercase()
                if (langKeywords.any { catLower.contains(it) }) 1 else 0
            }
                .thenByDescending { tracker.getCategoryUsage(it.key) }
                .thenByDescending { it.value.size }
        ).take(25)

        for ((category, categoryItems) in sortedCategories) {
            rows.add(NetflixRow.CategoryRow(category, categoryItems))
        }

        return rows
    }

    /** Returns keywords for boosting categories that match the app language. */
    private fun languageKeywords(): List<String> {
        val lang = com.vistacore.launcher.data.PrefsManager(this).appLanguage
        return when (lang) {
            "es" -> listOf("spanish", "español", "latino", "latina")
            "fr" -> listOf("french", "français", "francais")
            "pt" -> listOf("portuguese", "português", "portugues", "brasileiro", "brazil")
            "de" -> listOf("german", "deutsch")
            else -> emptyList() // English — no boost needed
        }
    }

    /** Apply pre-built rows to the UI — must run on main thread */
    private fun showNetflixRows(rows: List<NetflixRow>) {
        if (rows.isEmpty()) {
            showEmpty()
            return
        }

        val adapter = NetflixAdapter(rows, this)
        binding.netflixList.adapter = adapter
        binding.netflixEmpty.visibility = View.GONE
        binding.netflixList.visibility = View.VISIBLE

        // Load more rows as user scrolls down
        binding.netflixList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 2) {
                    adapter.loadMore()
                }
            }
        })

        if (bannerItems.size > 1) {
            startBannerRotation()
        }
    }

    private fun normalizeApostrophes(text: String): String =
        text.replace('\u2018', '\'')  // left single quote
            .replace('\u2019', '\'')  // right single quote
            .replace('\u02BC', '\'')  // modifier letter apostrophe
            .replace('\u0060', '\'')  // grave accent
            .replace('\u00B4', '\'')  // acute accent

    private fun showSearchResults(query: String) {
        scope.launch {
            val rows = withContext(Dispatchers.IO) {
                val normalizedQuery = normalizeApostrophes(query)
                val results = allItems.filter {
                    val name = normalizeApostrophes(it.name)
                    val showName = precomputedShowNames?.get(it.id)?.let { sn -> normalizeApostrophes(sn) }
                    name.contains(normalizedQuery, ignoreCase = true) ||
                            it.category.contains(normalizedQuery, ignoreCase = true) ||
                            (showName != null && showName.contains(normalizedQuery, ignoreCase = true))
                }
                val display = deduplicateShows(results)
                if (display.isNotEmpty()) {
                    listOf(NetflixRow.CategoryRow("Results for \"$query\"", display.take(50)))
                } else emptyList()
            }
            binding.netflixList.adapter = NetflixAdapter(rows, this@VODBrowserActivity)
        }
    }

    private fun startBannerRotation() {
        bannerRunnable?.let { handler.removeCallbacks(it) }
        bannerRunnable = object : Runnable {
            override fun run() {
                if (bannerItems.size > 1) {
                    bannerIndex = (bannerIndex + 1) % bannerItems.size
                    val adapter = binding.netflixList.adapter as? NetflixAdapter
                    adapter?.updateBanner(bannerItems[bannerIndex])
                }
                handler.postDelayed(this, BANNER_ROTATE_MS)
            }
        }
        handler.postDelayed(bannerRunnable!!, BANNER_ROTATE_MS)
    }

    // Pre-built index: show name → list of episodes (built once, not on every click)
    private var showEpisodesIndex: Map<String, List<Channel>>? = null
    // Precomputed show names to avoid re-running regex
    private var precomputedShowNames: Map<String, String>? = null

    private fun getShowEpisodes(showName: String): List<Channel> {
        if (showEpisodesIndex == null) {
            showEpisodesIndex = allItems.groupBy { extractShowName(it.name) }
        }
        return showEpisodesIndex?.get(showName) ?: emptyList()
    }

    private val usageTracker by lazy { com.vistacore.launcher.data.UsageTracker(this) }

    fun onItemClicked(item: Channel) {
        // Track category usage for personalization
        usageTracker.trackCategoryUsage(item.category)

        if (contentType == TYPE_SHOWS) {
            val showName = precomputedShowNames?.get(item.id) ?: extractShowName(item.name)

            // Xtream series: fetch episodes from API
            val xtreamSeriesId = if (item.id.startsWith("xt_series_")) {
                item.id.removePrefix("xt_series_").toIntOrNull()
            } else null

            // Jellyfin series: fetch episodes from Jellyfin API
            val jellyfinSeriesId = if (item.id.startsWith("jf_series_")) {
                item.id.removePrefix("jf_series_")
            } else null

            if (jellyfinSeriesId != null) {
                showLoading(true)
                scope.launch {
                    val episodes = withContext(Dispatchers.IO) {
                        try {
                            val prefs = PrefsManager(this@VODBrowserActivity)
                            val jf = com.vistacore.launcher.iptv.JellyfinClient(
                                com.vistacore.launcher.iptv.JellyfinAuth(
                                    prefs.jellyfinServer, prefs.jellyfinUsername, prefs.jellyfinPassword
                                )
                            )
                            jf.authenticate()
                            jf.getEpisodes(jellyfinSeriesId)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    showLoading(false)
                    if (episodes.isNotEmpty()) {
                        ShowDetailActivity.launch(this@VODBrowserActivity, showName, item.category, item.logoUrl, episodes)
                    } else {
                        Toast.makeText(this@VODBrowserActivity, "Could not load episodes", Toast.LENGTH_SHORT).show()
                    }
                }
            } else if (xtreamSeriesId != null) {
                showLoading(true)
                scope.launch {
                    val episodes = withContext(Dispatchers.IO) {
                        try {
                            val prefs = PrefsManager(this@VODBrowserActivity)
                            val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                            XtreamClient(auth).getSeriesInfo(xtreamSeriesId)
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                    showLoading(false)
                    if (episodes.isNotEmpty()) {
                        ShowDetailActivity.launch(this@VODBrowserActivity, showName, item.category, item.logoUrl, episodes)
                    } else {
                        Toast.makeText(this@VODBrowserActivity, "Could not load episodes", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                // M3U-based series: episodes already in the index
                val episodes = getShowEpisodes(showName)
                ShowDetailActivity.launch(this, showName, item.category, item.logoUrl, episodes)
            }
        } else {
            // Extract year from title if present, e.g. "The Matrix (1999)"
            val yearMatch = Regex("""\((\d{4})\)""").find(item.name)
            val intent = Intent(this, IPTVPlayerActivity::class.java).apply {
                putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, item.streamUrl)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, item.name)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, item.logoUrl)
                putExtra(IPTVPlayerActivity.EXTRA_IS_VOD, true)
                if (yearMatch != null) {
                    putExtra(IPTVPlayerActivity.EXTRA_CONTENT_YEAR, yearMatch.groupValues[1])
                }
            }
            startActivity(intent)
        }
    }

    fun getDisplayName(item: Channel): String {
        if (contentType != TYPE_SHOWS) return item.name
        return precomputedShowNames?.get(item.id) ?: extractShowName(item.name)
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

    private fun showLoading(show: Boolean) {
        binding.netflixLoading.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showEmpty() {
        binding.netflixList.visibility = View.GONE
        binding.netflixEmpty.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        bannerRunnable?.let { handler.removeCallbacks(it) }
        scope.cancel()
    }
}

// --- Netflix Data Models ---

sealed class NetflixRow {
    data class Banner(val item: Channel) : NetflixRow()
    data class CategoryRow(val title: String, val items: List<Channel>) : NetflixRow()
}

// --- Netflix Main Adapter (banner + category rows) ---

class NetflixAdapter(
    private val rows: List<NetflixRow>,
    private val activity: VODBrowserActivity
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_BANNER = 0
        const val TYPE_ROW = 1
        private const val INITIAL_ROWS = 4 // banner + 3 category rows
    }

    private var currentBanner: Channel? = (rows.firstOrNull() as? NetflixRow.Banner)?.item
    private var visibleCount = minOf(INITIAL_ROWS, rows.size)

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is NetflixRow.Banner -> TYPE_BANNER
        is NetflixRow.CategoryRow -> TYPE_ROW
    }

    override fun getItemCount() = visibleCount

    fun loadMore() {
        if (visibleCount >= rows.size) return
        val prev = visibleCount
        visibleCount = minOf(visibleCount + 3, rows.size)
        notifyItemRangeInserted(prev, visibleCount - prev)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_BANNER -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_netflix_banner, parent, false)
                BannerVH(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_netflix_row, parent, false)
                RowVH(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is NetflixRow.Banner -> (holder as BannerVH).bind(currentBanner ?: row.item)
            is NetflixRow.CategoryRow -> (holder as RowVH).bind(row)
        }
    }

    fun updateBanner(item: Channel) {
        currentBanner = item
        if (rows.isNotEmpty() && rows[0] is NetflixRow.Banner) {
            notifyItemChanged(0)
        }
    }

    inner class BannerVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.banner_image)
        private val title: TextView = itemView.findViewById(R.id.banner_title)
        private val category: TextView = itemView.findViewById(R.id.banner_category)
        private val playBtn: Button = itemView.findViewById(R.id.banner_play)

        fun bind(item: Channel) {
            title.text = activity.getDisplayName(item)
            category.text = item.category

            if (item.logoUrl.isNotBlank()) {
                Glide.with(itemView.context).load(item.logoUrl).into(image)
            }

            playBtn.setOnClickListener { activity.onItemClicked(item) }
            playBtn.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
        }
    }

    inner class RowVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.row_title)
        private val recycler: RecyclerView = itemView.findViewById(R.id.row_recycler)

        fun bind(row: NetflixRow.CategoryRow) {
            title.text = "${row.title} (${row.items.size})"
            recycler.layoutManager = LinearLayoutManager(
                itemView.context, LinearLayoutManager.HORIZONTAL, false
            )
            val posterAdapter = PosterAdapter(row.items, activity)
            recycler.adapter = posterAdapter
            recycler.clearOnScrollListeners()
            recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dx <= 0) return
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    if (lm.findLastVisibleItemPosition() >= posterAdapter.itemCount - 6) {
                        posterAdapter.loadMore()
                    }
                }
            })
        }
    }
}

// --- Poster Adapter (horizontal row of posters, lazy-loads 30 at a time) ---

class PosterAdapter(
    private val items: List<Channel>,
    private val activity: VODBrowserActivity
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    private var visibleCount = minOf(30, items.size)

    /** Load the next batch when the user scrolls near the end of the row. */
    fun loadMore() {
        if (visibleCount >= items.size) return
        val prev = visibleCount
        visibleCount = minOf(visibleCount + 20, items.size)
        notifyItemRangeInserted(prev, visibleCount - prev)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.poster_image)
        val title: TextView = itemView.findViewById(R.id.poster_title)
        val focusBorder: View = itemView.findViewById(R.id.poster_focus_border)
        val sourceBadge: ImageView = itemView.findViewById(R.id.poster_source_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_netflix_poster, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = activity.getDisplayName(item)

        if (item.logoUrl.isNotBlank()) {
            Glide.with(holder.itemView.context)
                .load(item.logoUrl)
                .placeholder(R.drawable.ic_movies)
                .error(R.drawable.ic_movies)
                .into(holder.image)
        } else {
            holder.image.setImageResource(R.drawable.ic_movies)
            holder.image.scaleType = ImageView.ScaleType.CENTER
        }

        if (item.source == com.vistacore.launcher.iptv.ContentSource.JELLYFIN) {
            holder.sourceBadge.setImageResource(R.drawable.ic_source_jellyfin)
            holder.sourceBadge.visibility = View.VISIBLE
        } else {
            holder.sourceBadge.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { activity.onItemClicked(item) }
        holder.itemView.setOnFocusChangeListener { v, f ->
            MainActivity.animateFocus(v, f)
            holder.focusBorder.visibility = if (f) View.VISIBLE else View.GONE
        }
    }

    override fun getItemCount() = visibleCount
}
