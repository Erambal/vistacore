package com.vistacore.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vistacore.launcher.R
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.data.UsageTracker
import com.vistacore.launcher.iptv.ProviderText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Big Preview + Simple Rows" home: one large preview banner up top that
 * updates as you move, with a few clearly-labelled horizontal rows beneath
 * (Live TV, Movies, TV Shows, Apps). Familiar Netflix-style browsing, but
 * calmer than the four-column Three Lanes.
 */
class HomeSimpleRowsActivity : BaseActivity() {

    private lateinit var prefs: PrefsManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var backdrop: ImageView
    private lateinit var clock: TextView
    private lateinit var category: TextView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var prompt: TextView
    private lateinit var continueSection: View
    private lateinit var continueRow: RecyclerView
    private lateinit var liveRow: RecyclerView
    private lateinit var moviesRow: RecyclerView
    private lateinit var showsRow: RecyclerView
    private lateinit var appsRow: RecyclerView
    private lateinit var liveHeader: TextView
    private lateinit var moviesHeader: TextView
    private lateinit var showsHeader: TextView
    private lateinit var loading: View

    private var backPressedOnce = false
    private var screenSaverRunnable: Runnable? = null

    // Category browsing: the loaded content plus the active category per section
    // (null = All). Picking a category re-filters just that row.
    private enum class Section { LIVE, MOVIES, SHOWS }
    private var homeData: HomeData? = null
    private var liveCategory: String? = null
    private var moviesCategory: String? = null
    private var showsCategory: String? = null

    private val clockTicker = object : Runnable {
        override fun run() {
            clock.text = android.text.format.DateFormat.getTimeFormat(this@HomeSimpleRowsActivity)
                .format(java.util.Date())
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_simple_rows)
        prefs = PrefsManager(this)

        backdrop = findViewById(R.id.hs_backdrop)
        clock = findViewById(R.id.hs_clock)
        category = findViewById(R.id.hs_category)
        title = findViewById(R.id.hs_title)
        subtitle = findViewById(R.id.hs_subtitle)
        prompt = findViewById(R.id.hs_prompt)
        continueSection = findViewById(R.id.hs_continue_section)
        continueRow = findViewById(R.id.hs_continue_row)
        liveRow = findViewById(R.id.hs_live_row)
        moviesRow = findViewById(R.id.hs_movies_row)
        showsRow = findViewById(R.id.hs_shows_row)
        appsRow = findViewById(R.id.hs_apps_row)
        liveHeader = findViewById(R.id.hs_live_header)
        moviesHeader = findViewById(R.id.hs_movies_header)
        showsHeader = findViewById(R.id.hs_shows_header)
        loading = findViewById(R.id.hs_loading)

        for (rv in listOf(continueRow, liveRow, moviesRow, showsRow, appsRow)) {
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        }

        findViewById<View>(R.id.hs_btn_search).setOnClickListener {
            startActivity(Intent(this, VoiceSearchActivity::class.java))
        }
        findViewById<View>(R.id.hs_btn_help).setOnClickListener {
            startActivity(Intent(this, RemoteHelpActivity::class.java))
        }
        findViewById<View>(R.id.hs_btn_settings).setOnClickListener {
            if (prefs.pinEnabled && prefs.settingsPin.isNotBlank()) {
                PinDialogHelper.showPinDialog(this, "Enter PIN to open Settings",
                    onSuccess = { startActivity(Intent(this, SettingsActivity::class.java)) })
            } else startActivity(Intent(this, SettingsActivity::class.java))
        }

        loadContent()
    }

    private fun loadContent() {
        loading.visibility = View.VISIBLE
        scope.launch {
            val data = HomeContentLoader.load(this@HomeSimpleRowsActivity)
            homeData = data

            val continueTiles = HomeTiles.continueWatching(this@HomeSimpleRowsActivity)
            if (continueTiles.isNotEmpty()) {
                continueSection.visibility = View.VISIBLE
                continueRow.adapter = HomeTileAdapter(continueTiles) { updatePreview(it) }
            } else {
                continueSection.visibility = View.GONE
            }
            liveRow.adapter = HomeTileAdapter(liveTiles(data)) { updatePreview(it) }
            moviesRow.adapter = HomeTileAdapter(movieTiles(data)) { updatePreview(it) }
            showsRow.adapter = HomeTileAdapter(showTiles(data)) { updatePreview(it) }
            appsRow.adapter = HomeTileAdapter(HomeTiles.apps(this@HomeSimpleRowsActivity)) { updatePreview(it) }

            loading.visibility = View.GONE

            // Land on the first real content tile (index 1 of a content row skips
            // its leading "Categories" tile), not the top-bar chips.
            when {
                continueTiles.isNotEmpty() -> focusTileAt(continueRow, 0)
                data.live.isNotEmpty() -> focusTileAt(liveRow, 1)
                data.movies.isNotEmpty() -> focusTileAt(moviesRow, 1)
                data.shows.isNotEmpty() -> focusTileAt(showsRow, 1)
                else -> focusTileAt(appsRow, 0)
            }
            if (data.live.isEmpty() && data.movies.isEmpty() && data.shows.isEmpty()) {
                title.text = getString(R.string.home_empty_title)
                subtitle.text = getString(R.string.home_empty_subtitle)
                prompt.visibility = View.GONE
            }
        }
    }

    // ---- Category browsing ------------------------------------------------

    /** The leading "Categories" control tile that opens the picker for a row. */
    private fun categoryTile(sectionLabel: String, accentRes: Int, onOpen: () -> Unit) = HomeTile(
        title = getString(R.string.home_categories),
        subtitle = getString(R.string.home_filter_by_category),
        category = sectionLabel,
        imageUrl = null,
        iconRes = R.drawable.ic_categories,
        iconOnly = true,
        cropToFill = false,
        accentColorRes = accentRes,
        open = onOpen,
    )

    private fun liveTiles(data: HomeData) =
        listOf(categoryTile(getString(R.string.home_lane_live), R.color.nf_accent_live) { showCategoryDialog(Section.LIVE) }) +
            HomeTiles.live(this, data.live.filter { liveCategory == null || it.category == liveCategory })

    private fun movieTiles(data: HomeData) =
        listOf(categoryTile(getString(R.string.home_lane_movies), R.color.nf_accent_movies) { showCategoryDialog(Section.MOVIES) }) +
            HomeTiles.movies(this, data.movies.filter { moviesCategory == null || it.category == moviesCategory })

    private fun showTiles(data: HomeData) =
        listOf(categoryTile(getString(R.string.home_lane_shows), R.color.nf_accent_shows) { showCategoryDialog(Section.SHOWS) }) +
            HomeTiles.shows(this, scope, data.shows.filter { showsCategory == null || it.category == showsCategory },
                { b -> loading.visibility = if (b) View.VISIBLE else View.GONE },
                { Toast.makeText(this, R.string.home_show_unavailable, Toast.LENGTH_SHORT).show() })

    private fun selectedCategory(s: Section) = when (s) {
        Section.LIVE -> liveCategory; Section.MOVIES -> moviesCategory; Section.SHOWS -> showsCategory
    }

    private fun showCategoryDialog(section: Section) {
        val data = homeData ?: return
        val tracker = UsageTracker(this)
        val channels = when (section) {
            Section.LIVE -> data.live; Section.MOVIES -> data.movies; Section.SHOWS -> data.shows
        }
        // Offer only categories that have content and aren't hidden in Settings.
        val rawCats = channels.asSequence()
            .map { it.category }
            .filter { it.isNotBlank() && !tracker.isCategoryHidden(it) }
            .distinct()
            .sortedBy { ProviderText.cleanCategory(it).lowercase() }
            .toList()
        if (rawCats.isEmpty()) {
            Toast.makeText(this, R.string.home_no_categories, Toast.LENGTH_SHORT).show()
            return
        }
        val items = (listOf(getString(R.string.home_all_categories)) +
            rawCats.map { ProviderText.cleanCategory(it) }).toTypedArray()
        val current = selectedCategory(section)
        val checked = if (current == null) 0 else rawCats.indexOf(current).let { if (it >= 0) it + 1 else 0 }
        AlertDialog.Builder(this, R.style.Theme_VistaCore_Dialog)
            .setTitle(R.string.home_pick_category)
            .setSingleChoiceItems(items, checked) { dialog, which ->
                val pick = if (which == 0) null else rawCats[which - 1]
                when (section) {
                    Section.LIVE -> liveCategory = pick
                    Section.MOVIES -> moviesCategory = pick
                    Section.SHOWS -> showsCategory = pick
                }
                dialog.dismiss()
                rebuildSection(section)
            }
            .show()
    }

    private fun rebuildSection(section: Section) {
        val data = homeData ?: return
        val (row, tiles, header, baseRes) = when (section) {
            Section.LIVE -> RowRebuild(liveRow, liveTiles(data), liveHeader, R.string.home_lane_live)
            Section.MOVIES -> RowRebuild(moviesRow, movieTiles(data), moviesHeader, R.string.home_lane_movies)
            Section.SHOWS -> RowRebuild(showsRow, showTiles(data), showsHeader, R.string.home_lane_shows)
        }
        row.adapter = HomeTileAdapter(tiles) { updatePreview(it) }
        val cat = selectedCategory(section)
        header.text = if (cat == null) getString(baseRes)
                      else "${getString(baseRes)} · ${ProviderText.cleanCategory(cat)}"
        // Land on the first filtered result (index 1) so the user sees the change.
        row.post { focusTileAt(row, if ((row.adapter?.itemCount ?: 0) > 1) 1 else 0) }
    }

    private data class RowRebuild(
        val row: RecyclerView, val tiles: List<HomeTile>, val header: TextView, val baseRes: Int,
    )

    /**
     * Focus a tile by index, not the top-bar chips. The ViewHolder may not be
     * laid out yet when content/filter changes land, so findViewHolder returns
     * null and requestFocus no-ops; retry for a few frames until it exists.
     */
    private fun focusTileAt(row: RecyclerView, index: Int, attempts: Int = 12) {
        val vh = row.findViewHolderForAdapterPosition(index)
        if (vh != null) {
            vh.itemView.requestFocus()
        } else if (attempts > 0) {
            row.postDelayed({ focusTileAt(row, index, attempts - 1) }, 16)
        }
    }

    private fun updatePreview(tile: HomeTile) {
        category.text = tile.category
        title.text = tile.title
        subtitle.text = tile.subtitle
        prompt.visibility = if (tile.iconOnly) View.GONE else View.VISIBLE
        backdrop.setBackgroundColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.nf_surface)
        )
        if (tile.imageUrl != null && !tile.iconOnly) {
            // Posters fill the hero; channel logos are small, so fit them
            // instead of stretching a blurry crop across the banner.
            backdrop.scaleType = if (tile.cropToFill) ImageView.ScaleType.CENTER_CROP
                                 else ImageView.ScaleType.FIT_CENTER
            Glide.with(this).load(tile.imageUrl).into(backdrop)
        } else {
            backdrop.setImageDrawable(null)
        }
    }

    /**
     * Central D-pad containment for the home rows, intercepted before the rows
     * or the NestedScrollView see the key (a per-tile OnKeyListener had a timing
     * hole at the boundary during fast key-repeat and let focus escape):
     *  - LEFT past the first tile / RIGHT past the last tile of a row: swallow,
     *    so focus never jumps sideways into another row or up to the top bar.
     *  - UP from the top content row: jump to the nearest top-bar chip. Left to
     *    the framework this is flaky — the NestedScrollView sometimes eats the
     *    press as a zero-distance scroll, leaving Search/Settings unreachable.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val rows = listOf(continueRow, liveRow, moviesRow, showsRow, appsRow)
            val focusedRow = rows.firstOrNull { it.hasFocus() }
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val firstRow = if (continueSection.visibility == View.VISIBLE) continueRow else liveRow
                    val focused = currentFocus
                    if (focused != null && firstRow.hasFocus()) {
                        nearestChipTo(focused)?.requestFocus()
                        resetScreenSaverTimer()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val focused = currentFocus
                    val pos = focused?.getTag(R.id.tile_position_tag) as? Int
                    if (focused != null && focusedRow != null && pos != null) {
                        // Fully own horizontal nav: move to the adjacent tile (or
                        // stay at the edge). Always consume so the framework never
                        // runs its own focus search, which during fast key-repeat
                        // jumps to the top-bar chips when the next tile is still
                        // off-screen — the "stuck on Settings" the user hit.
                        moveFocusInRow(focusedRow, pos, event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                        resetScreenSaverTimer()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Move focus one tile left/right within [row]. At the edge it stays put.
     * The adjacent tile is almost always already laid out (RecyclerView keeps a
     * small off-screen cache), so requestFocus is immediate and the row scrolls
     * to reveal it; the post() fallback covers the rare not-yet-laid-out case.
     */
    private fun moveFocusInRow(row: RecyclerView, fromPos: Int, goingRight: Boolean) {
        val count = row.adapter?.itemCount ?: 0
        val target = if (goingRight) fromPos + 1 else fromPos - 1
        if (target < 0 || target >= count) return  // at the edge: stay
        val vh = row.findViewHolderForAdapterPosition(target)
        if (vh != null) {
            vh.itemView.requestFocus()
        } else {
            row.scrollToPosition(target)
            row.post { row.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        }
    }

    private fun nearestChipTo(view: View): View? {
        val chips = listOf(R.id.hs_btn_search, R.id.hs_btn_help, R.id.hs_btn_settings)
            .mapNotNull { findViewById<View>(it) }
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val cx = loc[0] + view.width / 2
        return chips.minByOrNull { c ->
            val l = IntArray(2); c.getLocationOnScreen(l)
            kotlin.math.abs((l[0] + c.width / 2) - cx)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        resetScreenSaverTimer()
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (backPressedOnce) { finish(); return true }
            backPressedOnce = true
            Toast.makeText(this, R.string.home_press_back_again, Toast.LENGTH_LONG).show()
            handler.postDelayed({ backPressedOnce = false }, 3500)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun resetScreenSaverTimer() {
        screenSaverRunnable?.let { handler.removeCallbacks(it) }
        val timeout = prefs.screenSaverTimeout
        if (timeout <= 0) return
        screenSaverRunnable = Runnable { startActivity(Intent(this, ScreenSaverActivity::class.java)) }
        handler.postDelayed(screenSaverRunnable!!, timeout * 60 * 1000L)
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockTicker)
        resetScreenSaverTimer()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTicker)
        screenSaverRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
    }
}
