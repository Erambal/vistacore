package com.vistacore.launcher.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vistacore.launcher.R
import com.vistacore.launcher.data.ContentCache
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.iptv.*
import com.vistacore.launcher.system.ChannelUpdateWorker
import kotlinx.coroutines.*

/**
 * Kids section — Disney+ inspired two-panel layout.
 * Left sidebar: Movies / Shows / Live TV tabs.
 * Right panel: content for selected tab.
 */
class KidsBrowserActivity : BaseActivity() {

    private enum class Tab { MOVIES, SHOWS, LIVE_TV }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var contentList: RecyclerView
    private lateinit var tabMovies: LinearLayout
    private lateinit var tabShows: LinearLayout
    private lateinit var tabLive: LinearLayout
    private lateinit var loadingView: View
    private lateinit var emptyView: TextView
    private lateinit var searchInput: EditText

    private var currentTab = Tab.MOVIES
    private var movieRows: List<NetflixRow> = emptyList()
    private var showRows: List<NetflixRow> = emptyList()
    private var liveRows: List<NetflixRow> = emptyList()
    private var allKidsContent: List<Channel> = emptyList()
    private var kidsShowIndex: Map<String, List<Channel>>? = null

    // Adventure removed — only these exact categories
    private val allowedCategories = setOf("animation", "family", "kids")
    private val blockedCategories = setOf("action & adventure (shows)", "family (shows)")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kids_browser)

        contentList = findViewById(R.id.kids_content_list)
        tabMovies = findViewById(R.id.kids_tab_movies)
        tabShows = findViewById(R.id.kids_tab_shows)
        tabLive = findViewById(R.id.kids_tab_live)
        loadingView = findViewById(R.id.kids_loading)
        emptyView = findViewById(R.id.kids_empty)
        searchInput = findViewById(R.id.kids_search_input)

        contentList.layoutManager = LinearLayoutManager(this)
        setupSearch()

        fun setupTab(tab: LinearLayout, type: Tab) {
            tab.setOnClickListener { selectTab(type) }
            tab.setOnFocusChangeListener { v, focused ->
                MainActivity.animateFocus(v, focused)
                if (focused) selectTab(type)
            }
        }
        setupTab(tabMovies, Tab.MOVIES)
        setupTab(tabShows, Tab.SHOWS)
        setupTab(tabLive, Tab.LIVE_TV)

        loadContent()
    }

    private fun isKidsContent(channel: Channel): Boolean {
        val cat = channel.category.lowercase().trim()
        if (cat in blockedCategories) return false
        return allowedCategories.any { cat == it }
    }

    private fun loadContent() {
        // Try pre-loaded cache (instant path)
        if (ContentCache.kidsRows != null && ContentCache.kidsItems != null) {
            allKidsContent = ContentCache.kidsItems!!
            kidsShowIndex = ContentCache.kidsShowIndex
            // Rebuild split rows from cache
            scope.launch {
                val split = withContext(Dispatchers.IO) {
                    buildSplitRows(
                        allKidsContent.filter { it.contentType == ContentType.MOVIE },
                        allKidsContent.filter { it.contentType == ContentType.SERIES },
                        allKidsContent.filter { it.contentType == ContentType.LIVE }
                    )
                }
                movieRows = split.first
                showRows = split.second
                liveRows = split.third
                selectTab(Tab.MOVIES)
            }
            return
        }

        showLoading(true)

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val movies = ChannelUpdateWorker.getCachedMovies(this@KidsBrowserActivity) ?: emptyList()
                    val series = ChannelUpdateWorker.getCachedSeries(this@KidsBrowserActivity) ?: emptyList()
                    val live = ChannelUpdateWorker.getCachedChannels(this@KidsBrowserActivity) ?: emptyList()

                    val kidsMovies = movies.filter { isKidsContent(it) }
                    val kidsSeries = series.filter { isKidsContent(it) }
                    val kidsLive = live.filter { isKidsContent(it) }

                    val all = kidsMovies + kidsSeries + kidsLive
                    if (all.isEmpty()) return@withContext null

                    kidsShowIndex = kidsSeries.groupBy { extractShowName(it.name) }
                    val split = buildSplitRows(kidsMovies, kidsSeries, kidsLive)
                    Pair(all, split)
                }

                showLoading(false)

                if (result == null) {
                    showEmpty()
                    return@launch
                }

                allKidsContent = result.first
                movieRows = result.second.first
                showRows = result.second.second
                liveRows = result.second.third
                selectTab(Tab.MOVIES)

            } catch (e: Exception) {
                showLoading(false)
                Toast.makeText(this@KidsBrowserActivity, "Failed to load kids content", Toast.LENGTH_LONG).show()
                showEmpty()
            }
        }
    }

    /**
     * Build separate row lists for Movies, Shows, and Live TV tabs.
     * Returns Triple(movieRows, showRows, liveRows).
     */
    private fun buildSplitRows(
        movies: List<Channel>,
        series: List<Channel>,
        live: List<Channel>
    ): Triple<List<NetflixRow>, List<NetflixRow>, List<NetflixRow>> {
        // --- Movies ---
        val movieRows = mutableListOf<NetflixRow>()
        val movieGroups = movies.groupBy { it.category }
        for ((cat, items) in movieGroups.entries.sortedByDescending { it.value.size }.take(20)) {
            if (items.isNotEmpty()) movieRows.add(NetflixRow.CategoryRow(cat, items))
        }

        // --- Shows ---
        val showRows = mutableListOf<NetflixRow>()
        val uniqueShows = deduplicateShows(series)
        val showGroups = uniqueShows.groupBy { it.category }
        for ((cat, items) in showGroups.entries.sortedByDescending { it.value.size }.take(15)) {
            if (items.isNotEmpty()) showRows.add(NetflixRow.CategoryRow(cat, items))
        }

        // --- Live TV ---
        val liveRows = mutableListOf<NetflixRow>()
        if (live.isNotEmpty()) liveRows.add(NetflixRow.CategoryRow("Kids Live TV", live))

        return Triple(movieRows, showRows, liveRows)
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        updateTabHighlights()
        val rows = when (tab) {
            Tab.MOVIES -> movieRows
            Tab.SHOWS -> showRows
            Tab.LIVE_TV -> liveRows
        }
        if (rows.isNotEmpty()) {
            showContent(rows)
        } else if (allKidsContent.isNotEmpty()) {
            showEmpty()
        }
    }

    private fun updateTabHighlights() {
        fun style(tab: LinearLayout, labelId: Int, iconId: Int, selected: Boolean) {
            val label = tab.findViewById<TextView>(labelId)
            if (selected) {
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10f
                    setColor(0x22FFC107) // kids_yellow 13% opacity
                }
                tab.background = bg
                label.setTextColor(0xFFFFC107.toInt()) // kids_yellow
            } else {
                tab.background = ColorDrawable(Color.TRANSPARENT)
                label.setTextColor(0xCCB0B8C8.toInt()) // text_secondary
            }
        }
        style(tabMovies, R.id.kids_tab_movies_label, R.id.kids_tab_movies_icon, currentTab == Tab.MOVIES)
        style(tabShows, R.id.kids_tab_shows_label, R.id.kids_tab_shows_icon, currentTab == Tab.SHOWS)
        style(tabLive, R.id.kids_tab_live_label, R.id.kids_tab_live_icon, currentTab == Tab.LIVE_TV)
    }

    private fun showContent(rows: List<NetflixRow>) {
        contentList.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        contentList.adapter = KidsNetflixAdapter(rows, this)
        contentList.scrollToPosition(0)
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                if (query.length >= 2) {
                    showSearchResults(query)
                } else if (query.isEmpty()) {
                    selectTab(currentTab)
                }
            }
        })
    }

    private fun showSearchResults(query: String) {
        scope.launch {
            val rows = withContext(Dispatchers.IO) {
                val results = allKidsContent.filter {
                    it.name.contains(query, ignoreCase = true) ||
                            it.category.contains(query, ignoreCase = true)
                }
                val rowList = mutableListOf<NetflixRow>()

                // Split results by content type so series are handled correctly
                val seriesResults = results.filter { it.contentType == ContentType.SERIES }
                val movieResults = results.filter { it.contentType == ContentType.MOVIE }
                val liveResults = results.filter { it.contentType == ContentType.LIVE }

                // For series: deduplicate by show name, but prefer series items
                if (seriesResults.isNotEmpty()) {
                    val uniqueShows = deduplicateShows(seriesResults)
                    rowList.add(NetflixRow.CategoryRow("Shows matching \"$query\"", uniqueShows.take(50)))
                }
                if (movieResults.isNotEmpty()) {
                    rowList.add(NetflixRow.CategoryRow("Movies matching \"$query\"", movieResults.take(50)))
                }
                if (liveResults.isNotEmpty()) {
                    rowList.add(NetflixRow.CategoryRow("Live TV matching \"$query\"", liveResults.take(50)))
                }
                rowList
            }
            if (rows.isNotEmpty()) {
                showContent(rows)
            } else {
                contentList.adapter = KidsNetflixAdapter(emptyList(), this@KidsBrowserActivity)
                emptyView.visibility = View.VISIBLE
                emptyView.text = "No results for \"$query\""
            }
        }
    }

    fun onItemClicked(item: Channel) {
        if (item.contentType == ContentType.SERIES) {
            // Xtream series: id starts with "xt_series_" and needs episode fetch
            val xtreamSeriesId = if (item.id.startsWith("xt_series_")) {
                item.id.removePrefix("xt_series_").toIntOrNull()
            } else null

            if (xtreamSeriesId != null) {
                // Fetch actual episodes from Xtream API
                val showName = extractShowName(item.name)
                showLoading(true)
                scope.launch {
                    val episodes = withContext(Dispatchers.IO) {
                        try {
                            val prefs = PrefsManager(this@KidsBrowserActivity)
                            val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                            XtreamClient(auth).getSeriesInfo(xtreamSeriesId)
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                    showLoading(false)
                    if (episodes.isNotEmpty()) {
                        ShowDetailActivity.launch(this@KidsBrowserActivity, showName, item.category, item.logoUrl, episodes)
                    } else {
                        Toast.makeText(this@KidsBrowserActivity, "Could not load episodes", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                // M3U-based series: episodes already in the show index
                val showName = extractShowName(item.name)
                if (kidsShowIndex == null) {
                    val allSeries = allKidsContent.filter { it.contentType == ContentType.SERIES }
                    kidsShowIndex = allSeries.groupBy { extractShowName(it.name) }
                }
                val episodes = kidsShowIndex?.get(showName) ?: emptyList()
                if (episodes.size > 1) {
                    ShowDetailActivity.launch(this, showName, item.category, item.logoUrl, episodes)
                } else {
                    // Single episode or no index match — play directly
                    startActivity(Intent(this, IPTVPlayerActivity::class.java).apply {
                        putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, item.streamUrl)
                        putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, item.name)
                        putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, item.logoUrl)
                        putExtra(IPTVPlayerActivity.EXTRA_IS_VOD, true)
                    })
                }
            }
        } else {
            startActivity(Intent(this, IPTVPlayerActivity::class.java).apply {
                putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, item.streamUrl)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, item.name)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, item.logoUrl)
                if (item.contentType == ContentType.LIVE) {
                    putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_ID, item.id)
                } else {
                    putExtra(IPTVPlayerActivity.EXTRA_IS_VOD, true)
                }
            })
        }
    }

    private fun deduplicateShows(items: List<Channel>): List<Channel> {
        val seen = mutableSetOf<String>()
        return items.filter { seen.add(extractShowName(it.name)) }
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
        loadingView.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showEmpty() {
        contentList.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        emptyView.text = "No kids content found.\nMake sure your playlist includes kids categories."
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
    }
}

// --- Kids Adapter (two-panel version, no banner) ---

class KidsNetflixAdapter(
    private val rows: List<NetflixRow>,
    private val activity: KidsBrowserActivity
) : RecyclerView.Adapter<KidsNetflixAdapter.RowVH>() {

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_netflix_row, parent, false)
        return RowVH(view)
    }

    override fun onBindViewHolder(holder: RowVH, position: Int) {
        val row = rows[position] as? NetflixRow.CategoryRow ?: return
        holder.bind(row)
    }

    inner class RowVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.row_title)
        private val recycler: RecyclerView = itemView.findViewById(R.id.row_recycler)

        fun bind(row: NetflixRow.CategoryRow) {
            title.text = "${row.title} (${row.items.size})"
            recycler.layoutManager = LinearLayoutManager(
                itemView.context, LinearLayoutManager.HORIZONTAL, false
            )
            val posterAdapter = KidsPosterAdapter(row.items, activity)
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

class KidsPosterAdapter(
    private val items: List<Channel>,
    private val activity: KidsBrowserActivity
) : RecyclerView.Adapter<KidsPosterAdapter.VH>() {

    private var visibleCount = minOf(30, items.size)

    fun loadMore() {
        if (visibleCount >= items.size) return
        val prev = visibleCount
        visibleCount = minOf(visibleCount + 20, items.size)
        notifyItemRangeInserted(prev, visibleCount - prev)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.poster_image)
        val title: TextView = itemView.findViewById(R.id.poster_title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_netflix_poster, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.name
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
        holder.itemView.setOnClickListener { activity.onItemClicked(item) }
        holder.itemView.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    override fun getItemCount() = visibleCount
}
