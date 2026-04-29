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
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vistacore.launcher.R
import com.vistacore.launcher.data.ContentCache
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.data.WatchHistoryManager
import com.vistacore.launcher.iptv.*
import com.vistacore.launcher.system.ChannelUpdateWorker
import kotlinx.coroutines.*

/**
 * Kids section — discovery-driven layout with age bands.
 *
 * Sidebar tabs are age bands (Toddler / Younger / Older / All Ages) instead
 * of content type (Movies / Shows / Live TV) — kids and grandparents pick
 * "what's appropriate?" first, "what kind?" never.
 *
 * Right pane shows shelves: Continue Watching → Franchise rows
 * (Bluey, Paw Patrol, etc.) → Mood rows (Animals, Vehicles, Sing-Along…) →
 * source category rows. Adult-coded items are filtered out via KidsDiscovery.BLOCK_RE.
 */
class KidsBrowserActivity : BaseActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var contentList: RecyclerView
    private lateinit var tabToddler: LinearLayout
    private lateinit var tabYounger: LinearLayout
    private lateinit var tabOlder: LinearLayout
    private lateinit var tabAll: LinearLayout
    private lateinit var loadingView: View
    private lateinit var emptyView: TextView
    private lateinit var searchInput: EditText

    private lateinit var bandPrefs: KidsBandPrefs
    private var currentBand: KidsDiscovery.AgeBand = KidsDiscovery.AgeBand.ALL

    private var allKidsContent: List<Channel> = emptyList()
    private var kidsShowIndex: Map<String, List<Channel>>? = null
    // In-flight search-or-shelf-rebuild job. Cancelled before launching a
    // new one so a slow earlier query (or band change) can't paint over a
    // newer one.
    private var searchJob: Job? = null
    // True once the list is actually bound to a search-results / no-match
    // state. Used so a sub-2-char keystroke (including backspacing from
    // "bluey" → "b") triggers a shelf rebuild only when we *were* showing
    // search results — not on every stray character while shelves are up.
    private var showingSearchResults: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kids_browser)

        contentList = findViewById(R.id.kids_content_list)
        // The 3 original tabs were repurposed; IDs kept so this binding stays stable.
        tabToddler = findViewById(R.id.kids_tab_movies)
        tabYounger = findViewById(R.id.kids_tab_shows)
        tabOlder = findViewById(R.id.kids_tab_live)
        tabAll = findViewById(R.id.kids_tab_all)
        loadingView = findViewById(R.id.kids_loading)
        emptyView = findViewById(R.id.kids_empty)
        searchInput = findViewById(R.id.kids_search_input)

        bandPrefs = KidsBandPrefs(this)
        currentBand = bandPrefs.get()

        contentList.layoutManager = LinearLayoutManager(this)
        // Holding the D-pad down fires rapid fling scrolls on the outer
        // rows list. With default item animations enabled, RecyclerView's
        // internal state can fall out of sync with the adapter (the user
        // saw a crash). Disabling item animations keeps the scroll path
        // deterministic.
        contentList.itemAnimator = null
        setupSearch()

        fun setupTab(tab: LinearLayout, band: KidsDiscovery.AgeBand) {
            tab.setOnClickListener { selectBand(band) }
            tab.setOnFocusChangeListener { v, focused ->
                MainActivity.animateFocus(v, focused)
                if (focused) selectBand(band)
            }
        }
        setupTab(tabToddler, KidsDiscovery.AgeBand.TODDLER)
        setupTab(tabYounger, KidsDiscovery.AgeBand.YOUNGER)
        setupTab(tabOlder, KidsDiscovery.AgeBand.OLDER)
        setupTab(tabAll, KidsDiscovery.AgeBand.ALL)

        loadContent()
    }

    /**
     * D-pad navigation. Holding RIGHT while focused on a poster should step
     * one tile to the right; UP/DOWN should jump to the next row. Default
     * Android focus search picks the nearest focusable, which with tinted
     * row backgrounds and laggy poster layout sometimes lands on the
     * wrong item (especially: RIGHT from a sidebar tab not entering the
     * row at all). Pre-scroll + explicit requestFocus keeps it smooth.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> if (kidsJumpRow(true)) return true
                KeyEvent.KEYCODE_DPAD_UP -> if (kidsJumpRow(false)) return true
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (kidsStepPoster(true)) return true
                KeyEvent.KEYCODE_DPAD_LEFT -> if (kidsStepPoster(false)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun kidsStepPoster(right: Boolean): Boolean {
        val focused = currentFocus ?: return false
        var parent: android.view.ViewParent? = focused.parent
        var innerRv: RecyclerView? = null
        while (parent != null) {
            if (parent is RecyclerView && parent.id != R.id.kids_content_list) {
                innerRv = parent
                break
            }
            parent = parent.parent
        }
        if (innerRv == null) {
            // Focus is outside the rows — handle the case the comment above
            // claims is fixed: RIGHT from a sidebar age-band tab should land
            // on the first poster of the first row. Default focus search
            // misses with tinted hero rows whose inner RV hasn't laid out
            // yet, so we explicitly jump.
            if (right && focused.isOnSidebarTab()) return jumpToFirstPosterInVisibleRow()
            return false
        }
        val rv = innerRv
        val adapter = rv.adapter as? KidsPosterAdapter ?: return false
        val posterView = rv.findContainingItemView(focused) ?: return false
        val pos = rv.getChildAdapterPosition(posterView)
        if (pos == RecyclerView.NO_POSITION) return false

        val target = if (right) pos + 1 else pos - 1
        if (target < 0) return true
        while (right && target >= adapter.itemCount && adapter.itemCount < adapter.totalItems()) {
            adapter.loadMore()
        }
        if (target >= adapter.itemCount) return true

        rv.smoothScrollToPosition(target)
        fun tryFocus(): Boolean =
            rv.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() == true
        rv.post { if (!tryFocus()) rv.postDelayed({ tryFocus() }, 140) }
        return true
    }

    private fun View.isOnSidebarTab(): Boolean =
        this == tabToddler || this == tabYounger || this == tabOlder || this == tabAll

    private fun jumpToFirstPosterInVisibleRow(): Boolean {
        val list = contentList
        val lm = list.layoutManager as? LinearLayoutManager ?: return false
        val firstVisible = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        fun tryFocus(): Boolean {
            val rowVH = list.findViewHolderForAdapterPosition(firstVisible) ?: return false
            val innerRv = rowVH.itemView.findViewById<RecyclerView>(R.id.row_recycler)
                ?: return false
            return innerRv.findViewHolderForAdapterPosition(0)
                ?.itemView?.requestFocus() == true
        }
        if (tryFocus()) return true
        // The inner row adapter may not have laid out yet on first paint;
        // post + delayed retry mirrors the same pattern as kidsJumpRow.
        list.post { if (!tryFocus()) list.postDelayed({ tryFocus() }, 160) }
        return true
    }

    private fun kidsJumpRow(down: Boolean): Boolean {
        val focused = currentFocus ?: return false
        val list = contentList
        val rowView = list.findContainingItemView(focused) ?: return false
        val currentRow = list.getChildAdapterPosition(rowView)
        if (currentRow == RecyclerView.NO_POSITION) return false

        val adapter = list.adapter ?: return false
        val targetRow = if (down) currentRow + 1 else currentRow - 1
        if (targetRow < 0 || targetRow >= adapter.itemCount) return false

        val horizPos = rowView.findViewById<RecyclerView>(R.id.row_recycler)?.let { innerRv ->
            innerRv.findContainingItemView(focused)
                ?.let { innerRv.getChildAdapterPosition(it) }
                ?.coerceAtLeast(0)
        } ?: 0

        list.smoothScrollToPosition(targetRow)
        fun tryFocus(): Boolean {
            val vh = list.findViewHolderForAdapterPosition(targetRow) ?: return false
            val innerRv = vh.itemView.findViewById<RecyclerView>(R.id.row_recycler) ?: return false
            val innerCount = innerRv.adapter?.itemCount ?: 0
            if (innerCount == 0) return false
            val target = horizPos.coerceIn(0, innerCount - 1)
            val itemView = innerRv.findViewHolderForAdapterPosition(target)?.itemView
                ?: innerRv.findViewHolderForAdapterPosition(0)?.itemView
            return itemView?.requestFocus() == true
        }
        list.post { if (!tryFocus()) list.postDelayed({ tryFocus() }, 160) }
        return true
    }

    private fun loadContent() {
        // Try the preloaded ContentCache first for instant display.
        if (ContentCache.kidsItems != null && ContentCache.kidsItems!!.isNotEmpty()) {
            val filtered = filterToKids(ContentCache.kidsItems!!)
            kidsShowIndex = ContentCache.kidsShowIndex
                ?: filtered
                    .filter { it.contentType == ContentType.SERIES }
                    .groupBy { extractShowName(it.name) }
            allKidsContent = dedupShowEpisodes(filtered)
            applyCurrentBand()
            return
        }

        showLoading(true)
        scope.launch {
            try {
                val combined = withContext(Dispatchers.IO) {
                    val movies = ChannelUpdateWorker.getCachedMovies(this@KidsBrowserActivity) ?: emptyList()
                    val series = ChannelUpdateWorker.getCachedSeries(this@KidsBrowserActivity) ?: emptyList()
                    val live = ChannelUpdateWorker.getCachedChannels(this@KidsBrowserActivity) ?: emptyList()
                    val all = movies + series + live
                    filterToKids(all)
                }
                showLoading(false)
                if (combined.isEmpty()) { showEmpty(); return@launch }
                kidsShowIndex = combined
                    .filter { it.contentType == ContentType.SERIES }
                    .groupBy { extractShowName(it.name) }
                allKidsContent = dedupShowEpisodes(combined)
                applyCurrentBand()
            } catch (e: Exception) {
                showLoading(false)
                Toast.makeText(this@KidsBrowserActivity, "Failed to load kids content", Toast.LENGTH_LONG).show()
                showEmpty()
            }
        }
    }

    private fun filterToKids(items: List<Channel>): List<Channel> {
        // The kids classifier blocks adult tokens (BLOCK_RE) but not rating
        // tokens like "(R)" or "TV-MA", and franchise matches like Toy
        // Story or Madagascar are explicitly allowed regardless of name.
        // Honour the global Hide-Restricted-Ratings pref here so a
        // restricted-by-name title never reaches the Kids catalog when
        // the user has the toggle on, matching the Movies/Shows browser.
        val kids = items.filter { KidsDiscovery.isKidsItem(it) }
        val hideRestricted = PrefsManager(this).hideRestrictedRatings
        return Discovery.applyRestrictedFilter(kids, hideRestricted)
    }

    /**
     * Collapse multi-episode M3U shows down to one representative entry per
     * show name. Without this, raw episode lists (very common from M3U
     * providers) flood shelves and search with many cards for the same show
     * — e.g. 24 "Bluey - S01E04" entries on the Bluey franchise shelf.
     * Non-series items pass through untouched. The full episode list is
     * still preserved in kidsShowIndex for click-to-detail resolution.
     *
     * The representative also has its name rewritten to the cleaned show
     * name so poster titles read "Bluey" instead of "Bluey - S01E03".
     * extractShowName is idempotent, so kidsShowIndex lookups still hit.
     */
    private fun dedupShowEpisodes(items: List<Channel>): List<Channel> {
        val seen = mutableSetOf<String>()
        return items.mapNotNull { ch ->
            if (ch.contentType != ContentType.SERIES) return@mapNotNull ch
            val showName = extractShowName(ch.name)
            if (seen.add(showName)) ch.copy(name = showName) else null
        }
    }

    private fun selectBand(band: KidsDiscovery.AgeBand) {
        if (currentBand == band && contentList.adapter != null) return
        currentBand = band
        bandPrefs.set(band)
        // Update the highlight up front. selectBand is the source of truth
        // for the current band — if we leave the highlight refresh to
        // applyCurrentBand() it never runs on the search-active path
        // (showSearchResults doesn't touch tab styling), so the user can
        // see results from the new band while the old tab stays gold.
        updateTabHighlights()
        // Preserve an active search across band changes so the user doesn't
        // see the typed query but unfiltered shelves underneath. With <2
        // chars (no real search), fall back to shelves for the new band.
        val q = searchInput.text?.toString()?.trim().orEmpty()
        if (q.length >= 2) showSearchResults(q) else applyCurrentBand()
    }

    private fun applyCurrentBand() {
        updateTabHighlights()
        searchJob?.cancel()
        if (allKidsContent.isEmpty()) {
            showingSearchResults = false
            showEmpty()
            return
        }
        searchJob = scope.launch {
            val rows = withContext(Dispatchers.IO) { buildKidsDiscoveryRows(currentBand) }
            if (rows.isEmpty()) showEmpty() else showContent(rows)
            showingSearchResults = false
        }
    }

    /** Build the stack of shelves: Continue Watching → franchises → moods → categories. */
    private fun buildKidsDiscoveryRows(band: KidsDiscovery.AgeBand): List<NetflixRow> {
        val pool = KidsDiscovery.all(allKidsContent, band)
        if (pool.isEmpty()) return emptyList()

        val rows = mutableListOf<NetflixRow>()

        // Continue Watching — oversized hero row at the top.
        // We can't use Discovery.continueWatching here because the kids
        // catalog is deduped to one tile per show, while playback records
        // the actual episode URL. A direct streamUrl lookup against the
        // deduped pool would miss every episode that isn't the show's
        // representative. kidsContinueWatching walks kidsShowIndex and
        // maps episode URLs back to the show tile.
        val history = WatchHistoryManager(this)
        val cw = kidsContinueWatching(history, pool)
        if (cw.isNotEmpty()) {
            rows.add(NetflixRow.CategoryRow(
                title = "▶  Pick Up Where You Left Off",
                items = cw,
                hero = true,
                origin = "kids-continue"
            ))
        }
        val seenUrls = Discovery.seenStreamUrls(history)

        // Franchise shelves — Bluey, Paw Patrol, Disney, Pixar, Marvel, Star Wars, etc.
        for (fr in KidsDiscovery.FRANCHISES) {
            val items = KidsDiscovery.byFranchise(pool, fr, band)
            if (items.isEmpty()) continue
            rows.add(NetflixRow.CategoryRow(
                title = "${fr.emoji}  ${fr.label}",
                items = items,
                tintHex = fr.tintHex,
                origin = "kids-franchise:${fr.key}"
            ))
        }

        // Mood shelves — Animals, Vehicles, Sing-Along, etc. Skip in-progress items
        // so we don't keep showing the same poster after a few rewatches.
        for (mood in KidsDiscovery.MOODS) {
            val items = KidsDiscovery.byMood(pool, mood, band, exclude = seenUrls)
            if (items.size < 3) continue
            rows.add(NetflixRow.CategoryRow(
                title = "${mood.emoji}  ${mood.label}",
                items = items,
                tintHex = mood.tintHex,
                origin = "kids-mood:${mood.key}"
            ))
        }

        // Source categories last — only the largest few, to give a familiar fallback.
        val grouped = pool.groupBy { it.category }
            .filter { (_, list) -> list.size >= 3 }
            .entries.sortedByDescending { it.value.size }
            .take(8)
        for ((cat, items) in grouped) {
            rows.add(NetflixRow.CategoryRow(cat, items))
        }

        // Sparse-band fallback: if nothing above qualified (no franchise
        // matches, no mood >= 3, no category >= 3), the user otherwise
        // sees an empty screen even though `pool` had matching items.
        // Surface them in a single "More for Kids" shelf so the band
        // selection always produces something usable.
        if (rows.isEmpty()) {
            rows.add(NetflixRow.CategoryRow(
                title = "More for Kids",
                items = pool.take(60),
                origin = "kids-fallback"
            ))
        }

        return rows
    }

    /**
     * Continue-Watching list for the deduped Kids catalog. For movies and
     * live channels we match by streamUrl as usual. For series we map
     * each watched episode URL back to its show via kidsShowIndex, then
     * surface the show's representative tile (deduped by show name so
     * back-to-back episodes of one show only show one CW card).
     */
    private fun kidsContinueWatching(
        history: WatchHistoryManager,
        pool: List<Channel>,
        limit: Int = 12,
    ): List<Channel> {
        val byUrl = pool.associateBy { it.streamUrl }
        val episodeUrlToShow: Map<String, Channel> = run {
            val showByName = pool
                .filter { it.contentType == ContentType.SERIES }
                .associateBy { it.name }
            val map = HashMap<String, Channel>()
            for ((name, eps) in (kidsShowIndex ?: emptyMap())) {
                val rep = showByName[name] ?: continue
                for (ep in eps) map[ep.streamUrl] = rep
            }
            map
        }
        val seenShowIds = mutableSetOf<String>()
        val out = mutableListOf<Channel>()
        for (entry in history.getContinueWatching()) {
            val candidate = byUrl[entry.streamUrl] ?: episodeUrlToShow[entry.streamUrl] ?: continue
            if (candidate.contentType == ContentType.SERIES && !seenShowIds.add(candidate.id)) continue
            out.add(candidate)
            if (out.size >= limit) break
        }
        return out
    }

    private fun updateTabHighlights() {
        fun style(tab: LinearLayout, label: TextView, selected: Boolean) {
            if (selected) {
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f
                    setColor(0x33FFC107) // kids_yellow 20% opacity
                }
                tab.background = bg
                label.setTextColor(0xFFFFC107.toInt())
            } else {
                tab.background = ColorDrawable(Color.TRANSPARENT)
                label.setTextColor(0xCCB0B8C8.toInt())
            }
        }
        val toddlerLabel = findViewById<TextView>(R.id.kids_tab_movies_label)
        val youngerLabel = findViewById<TextView>(R.id.kids_tab_shows_label)
        val olderLabel = findViewById<TextView>(R.id.kids_tab_live_label)
        val allLabel = findViewById<TextView>(R.id.kids_tab_all_label)
        style(tabToddler, toddlerLabel, currentBand == KidsDiscovery.AgeBand.TODDLER)
        style(tabYounger, youngerLabel, currentBand == KidsDiscovery.AgeBand.YOUNGER)
        style(tabOlder, olderLabel, currentBand == KidsDiscovery.AgeBand.OLDER)
        style(tabAll, allLabel, currentBand == KidsDiscovery.AgeBand.ALL)
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
                searchJob?.cancel()
                when {
                    query.length >= 2 -> showSearchResults(query)
                    showingSearchResults -> {
                        // Sub-2-char (including empty) and we were showing
                        // search results / a no-match empty — restore the
                        // shelves so stale results don't linger.
                        applyCurrentBand()
                    }
                    // else: shelves already up; sub-2-char keystroke is a no-op
                }
            }
        })
    }

    private fun showSearchResults(query: String) {
        searchJob?.cancel()
        searchJob = scope.launch {
            val rows = withContext(Dispatchers.IO) {
                val pool = KidsDiscovery.all(allKidsContent, currentBand)
                val results = pool.filter {
                    it.name.contains(query, ignoreCase = true) ||
                            it.category.contains(query, ignoreCase = true)
                }
                if (results.isNotEmpty())
                    listOf(NetflixRow.CategoryRow("Results for \"$query\"", results.take(50)))
                else emptyList()
            }
            if (rows.isNotEmpty()) showContent(rows)
            else {
                contentList.adapter = KidsNetflixAdapter(emptyList(), this@KidsBrowserActivity)
                emptyView.visibility = View.VISIBLE
                emptyView.text = "No results for \"$query\""
            }
            showingSearchResults = true
        }
    }

    fun onItemClicked(item: Channel) {
        if (item.contentType == ContentType.SERIES) {
            val xtreamSeriesId = if (item.id.startsWith("xt_series_"))
                item.id.removePrefix("xt_series_").toIntOrNull() else null
            // Jellyfin series tiles carry a `jellyfin://series/<id>` marker
            // URL that the player can't actually stream — episode listing has
            // to come from JellyfinClient.getEpisodes(). Without this branch,
            // a Kids user clicking a Jellyfin show would launch the marker
            // URL in IPTVPlayerActivity and see a load failure.
            val jellyfinSeriesId = if (item.id.startsWith("jf_series_"))
                item.id.removePrefix("jf_series_") else null

            if (jellyfinSeriesId != null) {
                val showName = extractShowName(item.name)
                showLoading(true)
                scope.launch {
                    val episodes = withContext(Dispatchers.IO) {
                        try {
                            val prefs = PrefsManager(this@KidsBrowserActivity)
                            val jf = JellyfinClient(
                                JellyfinAuth(prefs.jellyfinServer, prefs.jellyfinUsername, prefs.jellyfinPassword)
                            )
                            jf.authenticate()
                            jf.getEpisodes(jellyfinSeriesId)
                        } catch (_: Exception) { emptyList() }
                    }
                    showLoading(false)
                    if (episodes.isNotEmpty())
                        ShowDetailActivity.launch(
                            this@KidsBrowserActivity, showName, item.category, item.logoUrl, episodes
                        )
                    else
                        Toast.makeText(this@KidsBrowserActivity, "Could not load episodes", Toast.LENGTH_SHORT).show()
                }
            } else if (xtreamSeriesId != null) {
                val showName = extractShowName(item.name)
                showLoading(true)
                scope.launch {
                    val episodes = withContext(Dispatchers.IO) {
                        try {
                            val prefs = PrefsManager(this@KidsBrowserActivity)
                            val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                            XtreamClient(auth).getSeriesInfo(xtreamSeriesId)
                        } catch (_: Exception) { emptyList() }
                    }
                    showLoading(false)
                    if (episodes.isNotEmpty())
                        ShowDetailActivity.launch(
                            this@KidsBrowserActivity, showName, item.category, item.logoUrl, episodes,
                            xtreamSeriesId = xtreamSeriesId
                        )
                    else
                        Toast.makeText(this@KidsBrowserActivity, "Could not load episodes", Toast.LENGTH_SHORT).show()
                }
            } else {
                // M3U series — always route through ShowDetailActivity, even
                // for single-episode shows, matching VODBrowserActivity's
                // shows path. Skipping straight to the player on size==1
                // bypasses season/episode context and the detail-screen
                // rating gate, so a kids-classified-but-actually-restricted
                // single-episode series would otherwise play unchecked.
                val showName = extractShowName(item.name)
                if (kidsShowIndex == null) {
                    val allSeries = allKidsContent.filter { it.contentType == ContentType.SERIES }
                    kidsShowIndex = allSeries.groupBy { extractShowName(it.name) }
                }
                val episodes = kidsShowIndex?.get(showName) ?: listOf(item)
                ShowDetailActivity.launch(this, showName, item.category, item.logoUrl, episodes)
            }
        } else if (item.contentType == ContentType.MOVIE && item.id.startsWith("xt_vod_")) {
            // Route Xtream movies through MovieDetailActivity so the per-title
            // MPAA gate (applyMpaa) can block restricted titles when Hide
            // Restricted Ratings is on. Without this, a restricted movie
            // that slipped past KidsDiscovery.isKidsItem (e.g. an R-rated
            // film mis-tagged "Family" by the provider) would play
            // straight out of Kids with no second-line check.
            val vodId = item.id.removePrefix("xt_vod_").toIntOrNull() ?: 0
            val year = Regex("""\((\d{4})\)""").find(item.name)?.groupValues?.get(1).orEmpty()
            MovieDetailActivity.launch(
                activity = this,
                title = item.name,
                category = item.category,
                posterUrl = item.logoUrl,
                streamUrl = item.streamUrl,
                vodId = vodId,
                year = year
            )
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
        emptyView.text = "No kids content for this age band.\nTry switching to All Ages."
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
    }
}

// ─── Kids adapter — applies tint/hero/origin from the row metadata. ───

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
            title.text = if (row.origin.isNotEmpty()) row.title
                         else "${row.title} (${row.items.size})"
            title.textSize = if (row.hero) 22f else 18f

            // Tinted background wash so kids can navigate by color even
            // before they can read the headers.
            if (row.tintHex.isNotBlank()) {
                try {
                    val base = Color.parseColor(row.tintHex)
                    val bg = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(
                            Color.argb(48, Color.red(base), Color.green(base), Color.blue(base)),
                            Color.TRANSPARENT
                        )
                    )
                    bg.cornerRadius = 20f
                    itemView.background = bg
                    itemView.setPadding(28, 24, 28, 12)
                } catch (_: Exception) {
                    itemView.background = null
                }
            } else {
                itemView.background = null
                itemView.setPadding(0, 0, 0, 0)
            }

            recycler.layoutManager = LinearLayoutManager(
                itemView.context, LinearLayoutManager.HORIZONTAL, false
            )
            val posterAdapter = KidsPosterAdapter(row.items, activity, row.hero, row.tintHex)
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
    private val activity: KidsBrowserActivity,
    private val hero: Boolean = false,
    private val tintHex: String = ""
) : RecyclerView.Adapter<KidsPosterAdapter.VH>() {

    private var visibleCount = minOf(30, items.size)

    fun loadMore() {
        if (visibleCount >= items.size) return
        val prev = visibleCount
        visibleCount = minOf(visibleCount + 20, items.size)
        notifyItemRangeInserted(prev, visibleCount - prev)
    }

    /** Full underlying list size, regardless of how many rows are currently bound. */
    fun totalItems(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.poster_image)
        val title: TextView = itemView.findViewById(R.id.poster_title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_netflix_poster, parent, false)
        // Bigger tiles for the Kids section overall — easier remote targeting.
        // Hero shelves (Continue Watching) get bigger still.
        val density = parent.context.resources.displayMetrics.density
        val widthDp = if (hero) 250 else 220
        val heightDp = if (hero) 360 else 320
        view.layoutParams = view.layoutParams?.also { it.width = (widthDp * density).toInt() }
            ?: ViewGroup.LayoutParams((widthDp * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        (view as? ViewGroup)?.getChildAt(0)?.let { card ->
            card.layoutParams = card.layoutParams?.apply { height = (heightDp * density).toInt() }
        }
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
