package com.vistacore.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vistacore.launcher.R
import com.vistacore.launcher.apps.AppId
import com.vistacore.launcher.apps.AppItem
import com.vistacore.launcher.apps.AppLauncher
import com.vistacore.launcher.data.FavoritesManager
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.data.RecentChannelsManager
import com.vistacore.launcher.data.WallpaperManager
import com.vistacore.launcher.databinding.ActivityMainBinding
import com.vistacore.launcher.iptv.*
import com.vistacore.launcher.system.ChannelUpdateWorker
import kotlinx.coroutines.*

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager
    private lateinit var favorites: FavoritesManager
    private lateinit var recents: RecentChannelsManager
    private lateinit var wallpaperManager: WallpaperManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val handler = Handler(Looper.getMainLooper())
    private var screenSaverRunnable: Runnable? = null
    private var backPressedOnce = false

    private var allChannels: List<Channel> = emptyList()
    private var cachedEpgData: EpgData? = null
    private var epgLastLoadTime: Long = 0
    private val EPG_CACHE_DURATION = 30 * 60 * 1000L // 30 minutes

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            prefs = PrefsManager(this)
            favorites = FavoritesManager(this)
            recents = RecentChannelsManager(this)
            wallpaperManager = WallpaperManager(this)

            setupAppCards()
            setupHeaderButtons()

            // Schedule auto-update if enabled
            if (prefs.autoUpdateEnabled) {
                ChannelUpdateWorker.schedule(this)
            }

            // Build full cache (including movies/series) in background on first launch
            if (prefs.hasIptvConfig()) {
                buildFullCacheIfNeeded()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to initialize", e)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        wallpaperManager.applyWallpaper(binding.root)
        // Refresh app cards (may have changed in settings)
        setupAppCards()
        // Force refresh channels every time we resume (fixes stale data after settings change)
        allChannels = emptyList()
        loadChannelsAndRefreshUI()
        loadUpcomingGames()
        resetScreenSaverTimer()
    }

    private fun buildFullCacheIfNeeded() {
        val hasCache = java.io.File(filesDir, "movies_cache.json.gz").exists() ||
            java.io.File(filesDir, "movies_cache.json").exists()
        if (!hasCache) {
            Log.d("MainActivity", "No movie cache — triggering background download via WorkManager")
            ChannelUpdateWorker.refreshNow(this)
        }
    }

    override fun onPause() {
        super.onPause()
        cancelScreenSaverTimer()
    }

    // --- Screen Saver Idle Timer ---

    private var screenSaverWarningRunnable: Runnable? = null

    private fun resetScreenSaverTimer() {
        cancelScreenSaverTimer()
        val timeout = prefs.screenSaverTimeout
        if (timeout <= 0) return

        val delayMs = timeout * 60 * 1000L
        // Show a warning 30 seconds before screen saver kicks in
        val warningMs = (delayMs - 30000L).coerceAtLeast(delayMs / 2)
        screenSaverWarningRunnable = Runnable {
            Toast.makeText(this, "Screen saver starting soon — press any button", Toast.LENGTH_LONG).show()
        }
        handler.postDelayed(screenSaverWarningRunnable!!, warningMs)

        screenSaverRunnable = Runnable {
            startActivity(Intent(this, ScreenSaverActivity::class.java))
        }
        handler.postDelayed(screenSaverRunnable!!, delayMs)
    }

    private fun cancelScreenSaverTimer() {
        screenSaverWarningRunnable?.let { handler.removeCallbacks(it) }
        screenSaverRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        resetScreenSaverTimer()
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (backPressedOnce) {
                finish()
                return true
            }
            backPressedOnce = true
            Toast.makeText(this, "Press back again to exit", Toast.LENGTH_LONG).show()
            handler.postDelayed({ backPressedOnce = false }, 3500)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // --- App Cards (configurable from Settings) ---

    private val allAppDefs: Map<String, AppItem> by lazy {
        mapOf(
            "IPTV" to AppItem(AppId.IPTV, getString(R.string.app_iptv), R.drawable.ic_iptv, getColor(R.color.iptv_teal)),
            "ESPN" to AppItem(AppId.ESPN, getString(R.string.app_espn), R.drawable.ic_espn, getColor(R.color.espn_red)),
            "ROKU" to AppItem(AppId.ROKU, getString(R.string.app_roku), R.drawable.ic_roku, getColor(R.color.roku_purple)),
            "DISNEY_PLUS" to AppItem(AppId.DISNEY_PLUS, getString(R.string.app_disney), R.drawable.ic_disney, getColor(R.color.disney_blue)),
            "MOVIES" to AppItem(AppId.MOVIES, getString(R.string.app_movies), R.drawable.ic_movies, getColor(R.color.movies_amber)),
            "TV_SHOWS" to AppItem(AppId.TV_SHOWS, getString(R.string.app_tv_shows), R.drawable.ic_tv_shows, getColor(R.color.shows_indigo)),
            "KIDS" to AppItem(AppId.KIDS, getString(R.string.app_kids), R.drawable.ic_kids, getColor(R.color.kids_yellow))
        )
    }

    private fun setupAppCards() {
        val enabledIds = prefs.enabledApps
        val apps = enabledIds.mapNotNull { id ->
            allAppDefs[id] ?: if (id.startsWith("custom:")) {
                createCustomAppItem(id)
            } else null
        }

        binding.appsRow.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = AppCardAdapter(apps) { onAppClicked(it) }
            clipChildren = false
            clipToPadding = false
            // Auto-focus the first app card so remote navigation works immediately
            post { findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
    }

    private fun createCustomAppItem(id: String): AppItem? {
        val pkg = id.removePrefix("custom:")
        return try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            val label = packageManager.getApplicationLabel(appInfo).toString()
            AppItem(AppId.SETTINGS, label, R.drawable.ic_settings, getColor(R.color.settings_gray), pkg)
        } catch (_: Exception) {
            null
        }
    }

    private fun setupHeaderButtons() {
        // Search bar
        binding.searchBar.setOnClickListener {
            startActivity(Intent(this, VoiceSearchActivity::class.java))
        }
        binding.searchBar.setOnFocusChangeListener { v, f -> animateFocus(v, f) }

        // Settings (PIN-gated)
        binding.btnSettings.setOnClickListener {
            launchSettingsWithPinCheck()
        }
        binding.btnSettings.setOnFocusChangeListener { v, f -> animateFocus(v, f) }

        // Remote Help (next to settings)
        binding.btnRemoteHelp.setOnClickListener {
            startActivity(Intent(this, RemoteHelpActivity::class.java))
        }
        binding.btnRemoteHelp.setOnFocusChangeListener { v, f -> animateFocus(v, f) }

        // Full Guide button
        binding.btnFullGuide.setOnClickListener {
            startActivity(Intent(this, EpgGuideActivity::class.java))
        }
        binding.btnFullGuide.setOnFocusChangeListener { v, f -> animateFocus(v, f) }
    }

    // --- Load channels and populate all rows ---

    private fun loadChannelsAndRefreshUI() {
        if (!prefs.hasIptvConfig()) {
            hideRow(binding.recentsHeader, binding.recentsRow)
            hideRow(binding.favoritesHeader, binding.favoritesRow)
            binding.nowPlayingCard.visibility = View.GONE
            binding.guideSection.visibility = View.GONE
            return
        }

        scope.launch {
            try {
                if (allChannels.isEmpty()) {
                    // Read cache on IO thread
                    val cached = withContext(Dispatchers.IO) {
                        ChannelUpdateWorker.getCachedChannels(this@MainActivity)
                    }
                    if (cached != null && cached.isNotEmpty()) {
                        allChannels = cached
                    } else {
                        // No cache — download from network (show a toast so user knows)
                        android.widget.Toast.makeText(this@MainActivity, "Downloading channels…", android.widget.Toast.LENGTH_LONG).show()
                        try {
                            allChannels = withContext(Dispatchers.IO) {
                                when (prefs.sourceType) {
                                    PrefsManager.SOURCE_M3U -> M3UParser().parse(prefs.m3uUrl)
                                    PrefsManager.SOURCE_XTREAM -> {
                                        val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                                        XtreamClient(auth).getChannels()
                                    }
                                    else -> emptyList()
                                }
                            }
                            if (allChannels.isNotEmpty()) {
                                withContext(Dispatchers.IO) {
                                    ChannelUpdateWorker.cacheChannels(this@MainActivity, allChannels)
                                }
                                android.widget.Toast.makeText(this@MainActivity, "Downloaded ${allChannels.size} items", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Download failed on home screen", e)
                            android.widget.Toast.makeText(this@MainActivity, "Download failed — try again later", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }

                loadContinueWatching()
                loadRecentChannels()
                loadFavoriteChannels()
                loadNowPlaying()
                loadInlineGuide()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load channels", e)
                binding.guideSection.visibility = View.VISIBLE
                binding.guideList.visibility = View.GONE
                binding.guideEmpty.visibility = View.VISIBLE
                binding.guideEmpty.text = "Could not load channels. Check your connection settings."
            }
        }
    }

    // --- Inline TV Guide (What's On Now) ---

    private suspend fun getEpgData(): EpgData? {
        val epgUrl = prefs.epgUrl
        if (epgUrl.isBlank()) return null

        // Use local cache if fresh enough
        val now = System.currentTimeMillis()
        if (cachedEpgData != null && (now - epgLastLoadTime) < EPG_CACHE_DURATION) {
            return cachedEpgData
        }

        // Check shared ContentCache
        val sharedEpg = com.vistacore.launcher.data.ContentCache.epgData
        val sharedAge = now - com.vistacore.launcher.data.ContentCache.epgLoadTime
        if (sharedEpg != null && sharedAge < EPG_CACHE_DURATION) {
            cachedEpgData = sharedEpg
            epgLastLoadTime = com.vistacore.launcher.data.ContentCache.epgLoadTime
            return sharedEpg
        }

        return try {
            val data = withContext(Dispatchers.IO) { EpgParser().parse(epgUrl) }
            cachedEpgData = data
            epgLastLoadTime = now
            // Store in shared cache for Live TV
            com.vistacore.launcher.data.ContentCache.epgData = data
            com.vistacore.launcher.data.ContentCache.epgLoadTime = now
            data
        } catch (e: Exception) {
            Log.e("MainActivity", "EPG parse failed", e)
            cachedEpgData
        }
    }

    private fun loadInlineGuide() {
        // Show recently watched channels with EPG data
        val recentChannelList = recents.getRecentChannels(allChannels)
        if (recentChannelList.isEmpty()) {
            binding.guideSection.visibility = View.GONE
            return
        }

        scope.launch {
            try {
                val epgData = getEpgData()
                val guideItems = recentChannelList.map { channel ->
                    val nowPlaying = if (epgData != null) findNowPlaying(epgData, channel) else null
                    GuideNowItem(
                        channel = channel,
                        programTitle = nowPlaying?.title ?: "",
                        programTime = if (nowPlaying != null) formatTimeRange(nowPlaying) else "",
                        isLive = nowPlaying?.isLive ?: false
                    )
                }

                binding.guideSection.visibility = View.VISIBLE
                binding.guideEmpty.visibility = View.GONE
                binding.guideList.visibility = View.VISIBLE
                binding.guideList.layoutManager = LinearLayoutManager(this@MainActivity)
                binding.guideList.adapter = GuideNowAdapter(guideItems) { channel ->
                    launchChannel(channel)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load guide", e)
                binding.guideSection.visibility = View.GONE
            }
        }
    }

    private fun findNowPlaying(epgData: EpgData, channel: Channel): EpgProgram? {
        // Try epgId first (most reliable — tvg-id from M3U maps to EPG channel id)
        if (channel.epgId.isNotBlank()) {
            val match = epgData.getNowPlaying(channel.epgId)
            if (match != null) return match
        }
        // Try internal channel id
        val byId = epgData.getNowPlaying(channel.id)
        if (byId != null) return byId
        // Try exact channel name
        val byName = epgData.getNowPlaying(channel.name)
        if (byName != null) return byName
        // Try fuzzy name match — strip common suffixes like "HD", "FHD", "SD"
        val cleanName = channel.name.replace(Regex("""(?i)\s*(hd|fhd|sd|4k|uhd|\(.*?\))\s*"""), "").trim()
        if (cleanName != channel.name) {
            val byClean = epgData.getNowPlaying(cleanName)
            if (byClean != null) return byClean
        }
        return null
    }

    private fun buildGuideItems(epgData: EpgData): List<GuideNowItem> {
        val items = mutableListOf<GuideNowItem>()
        // Only check live channels
        val liveChannels = allChannels.filter { it.contentType == ContentType.LIVE }
        val channelsToShow = liveChannels.take(50)

        for (channel in channelsToShow) {
            val nowPlaying = findNowPlaying(epgData, channel)
            if (nowPlaying != null) {
                items.add(
                    GuideNowItem(
                        channel = channel,
                        programTitle = nowPlaying.title,
                        programTime = formatTimeRange(nowPlaying),
                        isLive = nowPlaying.isLive
                    )
                )
            }
            if (items.size >= 8) break
        }
        return items
    }

    private fun formatTimeRange(program: EpgProgram): String {
        val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        return "${fmt.format(program.startTime)} - ${fmt.format(program.endTime)}"
    }

    // --- Upcoming Games ---

    private fun loadUpcomingGames() {
        val enabledSports = prefs.sportsTypes
        if (enabledSports.isEmpty()) {
            binding.gamesSection.visibility = View.GONE
            return
        }

        binding.gamesSection.visibility = View.VISIBLE
        binding.gamesLoading.visibility = View.VISIBLE
        binding.gamesRow.visibility = View.GONE

        scope.launch {
            try {
                val games = withContext(Dispatchers.IO) {
                    SportsDataManager().getUpcomingGames(enabledSports)
                }

                binding.gamesLoading.visibility = View.GONE

                if (games.isEmpty()) {
                    binding.gamesSection.visibility = View.GONE
                } else {
                    binding.gamesRow.visibility = View.VISIBLE
                    binding.gamesRow.layoutManager = LinearLayoutManager(
                        this@MainActivity, LinearLayoutManager.HORIZONTAL, false
                    )
                    binding.gamesRow.adapter = UpcomingGameAdapter(games)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load games", e)
                binding.gamesLoading.visibility = View.GONE
                binding.gamesSection.visibility = View.GONE
            }
        }
    }

    // --- Recent & Favorite Channels ---

    private fun loadContinueWatching() {
        val watchHistory = com.vistacore.launcher.data.WatchHistoryManager(this)
        val entries = watchHistory.getContinueWatching()

        if (entries.isEmpty()) {
            binding.continueWatchingSection.visibility = View.GONE
            return
        }

        binding.continueWatchingSection.visibility = View.VISIBLE
        binding.continueWatchingRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.continueWatchingRow.adapter = ContinueWatchingAdapter(entries, onClick = { entry ->
            val intent = Intent(this, IPTVPlayerActivity::class.java).apply {
                putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, entry.streamUrl)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, entry.name)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, entry.logoUrl)
                putExtra(IPTVPlayerActivity.EXTRA_RESUME_POSITION, entry.positionMs)
            }
            startActivity(intent)
        }, onLongClick = { entry ->
            showRemoveHistoryDialog(entry, watchHistory)
        })

        binding.btnClearWatchHistory.setOnClickListener {
            AlertDialog.Builder(this, R.style.Theme_VistaCore_Dialog)
                .setTitle("Clear watch history?")
                .setMessage("This will remove all Continue Watching entries.")
                .setPositiveButton("Clear") { _, _ ->
                    watchHistory.clearAll()
                    loadContinueWatching()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnClearWatchHistory.setOnFocusChangeListener { v, f -> animateFocus(v, f) }
    }

    private fun showRemoveHistoryDialog(entry: com.vistacore.launcher.data.WatchEntry, watchHistory: com.vistacore.launcher.data.WatchHistoryManager) {
        val items = arrayOf("Remove \"${entry.name}\"", "Clear all watch history")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Watch History")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        watchHistory.remove(entry.streamUrl)
                        loadContinueWatching()
                    }
                    1 -> {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Clear all watch history?")
                            .setMessage("This will remove all continue watching entries.")
                            .setPositiveButton("Clear") { _, _ ->
                                watchHistory.clearAll()
                                loadContinueWatching()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun loadRecentChannels() {
        val recentList = recents.getRecentChannels(allChannels)
        if (recentList.isNotEmpty()) {
            showRow(binding.recentsHeader, binding.recentsRow)
            binding.recentsRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.recentsRow.adapter = FavoriteChannelAdapter(recentList, onClick = { launchChannel(it) }, onLongClick = { channel ->
                showRemoveRecentDialog(channel)
            })
        } else {
            hideRow(binding.recentsHeader, binding.recentsRow)
        }
    }

    private fun showRemoveRecentDialog(channel: com.vistacore.launcher.iptv.Channel) {
        val items = arrayOf("Remove \"${channel.name}\"", "Clear all recent channels")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Recent Channels")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        recents.removeRecent(channel.id)
                        loadRecentChannels()
                    }
                    1 -> {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Clear all recent channels?")
                            .setMessage("This will remove all recent channel entries.")
                            .setPositiveButton("Clear") { _, _ ->
                                recents.clearRecents()
                                loadRecentChannels()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun loadFavoriteChannels() {
        val favChannels = favorites.filterFavorites(allChannels)
        if (favChannels.isNotEmpty()) {
            showRow(binding.favoritesHeader, binding.favoritesRow)
            binding.favoritesRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.favoritesRow.adapter = FavoriteChannelAdapter(favChannels, onClick = { launchChannel(it) })
        } else {
            hideRow(binding.favoritesHeader, binding.favoritesRow)
        }
    }

    private fun loadNowPlaying() {
        val epgUrl = prefs.epgUrl
        val lastChannelId = prefs.lastChannel
        if (epgUrl.isBlank() || lastChannelId.isBlank()) {
            binding.nowPlayingCard.visibility = View.GONE
            return
        }

        scope.launch {
            try {
                val epgData = getEpgData() ?: return@launch
                val nowPlaying = epgData.getNowPlaying(lastChannelId)
                if (nowPlaying != null) {
                    val channel = allChannels.find { it.id == lastChannelId }
                    binding.nowPlayingCard.visibility = View.VISIBLE
                    binding.nowPlayingTitle.text = nowPlaying.title
                    binding.nowPlayingChannel.text = channel?.name ?: lastChannelId
                    binding.nowPlayingProgress.progress = (nowPlaying.progress * 100).toInt()
                    binding.nowPlayingCard.setOnClickListener {
                        if (channel != null) launchChannel(channel)
                    }
                    binding.nowPlayingCard.setOnFocusChangeListener { v, f -> animateFocus(v, f) }
                } else {
                    binding.nowPlayingCard.visibility = View.GONE
                }
            } catch (_: Exception) {
                binding.nowPlayingCard.visibility = View.GONE
            }
        }
    }

    // --- Helpers ---

    private fun launchChannel(channel: Channel) {
        prefs.lastChannel = channel.id
        // Launch LiveTV first, then player on top — so Back goes to the guide
        val intents = arrayOf(
            Intent(this, LiveTVActivity::class.java),
            Intent(this, IPTVPlayerActivity::class.java).apply {
                putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, channel.logoUrl)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            }
        )
        startActivities(intents)
    }

    private val usageTracker by lazy { com.vistacore.launcher.data.UsageTracker(this) }

    private fun onAppClicked(app: AppItem) {
        // Track usage
        usageTracker.trackAppUsage(app.id.name)

        // Handle custom apps first
        if (app.customPackage != null) {
            launchCustomApp(app.customPackage)
            return
        }

        when (app.id) {
            AppId.IPTV -> startActivity(Intent(this, LiveTVActivity::class.java))
            AppId.MOVIES -> startActivity(Intent(this, VODBrowserActivity::class.java).apply {
                putExtra(VODBrowserActivity.EXTRA_CONTENT_TYPE, VODBrowserActivity.TYPE_MOVIES)
            })
            AppId.TV_SHOWS -> startActivity(Intent(this, VODBrowserActivity::class.java).apply {
                putExtra(VODBrowserActivity.EXTRA_CONTENT_TYPE, VODBrowserActivity.TYPE_SHOWS)
            })
            AppId.KIDS -> startActivity(Intent(this, KidsBrowserActivity::class.java))
            AppId.SETTINGS -> launchSettingsWithPinCheck()
            else -> AppLauncher.launchApp(this, app.id)
        }
    }

    private fun launchSettingsWithPinCheck() {
        if (prefs.pinEnabled && prefs.settingsPin.isNotBlank()) {
            PinDialogHelper.showPinDialog(this, "Enter PIN to open Settings",
                onSuccess = { startActivity(Intent(this, SettingsActivity::class.java)) }
            )
        } else {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun launchCustomApp(packageName: String) {
        if (!AppLauncher.launchPackage(this, packageName)) {
            android.widget.Toast.makeText(this, "App not found", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRow(header: View, row: View) {
        header.visibility = View.VISIBLE
        row.visibility = View.VISIBLE
    }

    private fun hideRow(header: View, row: View) {
        header.visibility = View.GONE
        row.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelScreenSaverTimer()
        scope.cancel()
    }

    companion object {
        fun animateFocus(view: View, hasFocus: Boolean) {
            // Focus is communicated entirely via the border highlight in each
            // view's background drawable (state_focused). Popup/scale animations
            // were removed because they made the cursor position hard to track.
        }
    }
}

// --- Guide "What's On Now" data + adapter ---

data class GuideNowItem(
    val channel: Channel,
    val programTitle: String,
    val programTime: String,
    val isLive: Boolean
)

class GuideNowAdapter(
    private val items: List<GuideNowItem>,
    private val onChannelClick: (Channel) -> Unit
) : RecyclerView.Adapter<GuideNowAdapter.VH>() {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val logo: ImageView = itemView.findViewById(R.id.guide_channel_logo)
        val channelName: TextView = itemView.findViewById(R.id.guide_channel_name)
        val programTitle: TextView = itemView.findViewById(R.id.guide_program_title)
        val programTime: TextView = itemView.findViewById(R.id.guide_program_time)
        val liveDot: View = itemView.findViewById(R.id.guide_live_dot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_guide_now, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.channelName.text = item.channel.name
        holder.programTitle.text = item.programTitle
        holder.programTime.text = item.programTime
        holder.liveDot.visibility = if (item.isLive) View.VISIBLE else View.GONE

        if (item.channel.logoUrl.isNotBlank()) {
            Glide.with(holder.itemView.context)
                .load(item.channel.logoUrl)
                .placeholder(R.drawable.ic_iptv)
                .into(holder.logo)
        } else {
            holder.logo.setImageResource(R.drawable.ic_iptv)
        }

        holder.itemView.setOnClickListener { onChannelClick(item.channel) }
        holder.itemView.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    override fun getItemCount() = items.size
}

// --- Upcoming Games Adapter ---

class UpcomingGameAdapter(
    private val games: List<UpcomingGame>
) : RecyclerView.Adapter<UpcomingGameAdapter.VH>() {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val league: TextView = itemView.findViewById(R.id.game_league)
        val status: TextView = itemView.findViewById(R.id.game_status)
        val awayLogo: ImageView = itemView.findViewById(R.id.game_away_logo)
        val awayName: TextView = itemView.findViewById(R.id.game_away_name)
        val homeLogo: ImageView = itemView.findViewById(R.id.game_home_logo)
        val homeName: TextView = itemView.findViewById(R.id.game_home_name)
        val score: TextView = itemView.findViewById(R.id.game_score)
        val broadcast: TextView = itemView.findViewById(R.id.game_broadcast)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_upcoming_game, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val game = games[position]
        holder.league.text = game.league
        holder.awayName.text = game.awayTeam.split(" ").lastOrNull() ?: game.awayTeam
        holder.homeName.text = game.homeTeam.split(" ").lastOrNull() ?: game.homeTeam

        // Load team logos
        if (game.awayLogo.isNotBlank()) {
            Glide.with(holder.itemView.context).load(game.awayLogo).into(holder.awayLogo)
        }
        if (game.homeLogo.isNotBlank()) {
            Glide.with(holder.itemView.context).load(game.homeLogo).into(holder.homeLogo)
        }

        // Status & score
        when {
            game.isLive -> {
                holder.status.text = "LIVE"
                holder.status.setTextColor(holder.itemView.context.getColor(R.color.status_offline))
                holder.status.setBackgroundResource(R.drawable.live_badge_background)
                holder.score.text = "${game.awayScore} - ${game.homeScore}"
            }
            game.isUpcoming -> {
                holder.status.text = "${game.displayDate} · ${game.displayTime}"
                holder.status.setTextColor(holder.itemView.context.getColor(R.color.text_secondary))
                holder.status.background = null
                holder.score.text = "vs"
            }
            game.isFinished -> {
                holder.status.text = "Final"
                holder.status.setTextColor(holder.itemView.context.getColor(R.color.text_hint))
                holder.status.background = null
                holder.score.text = "${game.awayScore} - ${game.homeScore}"
            }
        }

        // Broadcast info
        if (game.broadcast.isNotBlank()) {
            holder.broadcast.text = game.broadcast
            holder.broadcast.visibility = View.VISIBLE
        } else {
            holder.broadcast.visibility = View.GONE
        }

        holder.itemView.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }

        holder.itemView.setOnClickListener { view ->
            val context = view.context
            val awayShort = game.awayTeam.split(" ").lastOrNull() ?: game.awayTeam
            val homeShort = game.homeTeam.split(" ").lastOrNull() ?: game.homeTeam
            val searchQuery = "$awayShort $homeShort"

            val options = arrayOf("Search Live TV", "Open ESPN")
            AlertDialog.Builder(context, R.style.Theme_VistaCore_Dialog)
                .setTitle("$awayShort vs $homeShort")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val intent = Intent(context, LiveTVActivity::class.java).apply {
                                putExtra(LiveTVActivity.EXTRA_SEARCH_QUERY, searchQuery)
                            }
                            context.startActivity(intent)
                        }
                        1 -> {
                            AppLauncher.launchApp(context, AppId.ESPN)
                        }
                    }
                }
                .show()
        }
    }

    override fun getItemCount() = games.size
}

// --- Continue Watching Adapter ---

class ContinueWatchingAdapter(
    private val entries: List<com.vistacore.launcher.data.WatchEntry>,
    private val onClick: (com.vistacore.launcher.data.WatchEntry) -> Unit,
    private val onLongClick: (com.vistacore.launcher.data.WatchEntry) -> Unit = {}
) : RecyclerView.Adapter<ContinueWatchingAdapter.VH>() {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val poster: android.widget.ImageView = itemView.findViewById(R.id.cw_poster)
        val title: android.widget.TextView = itemView.findViewById(R.id.cw_title)
        val remaining: android.widget.TextView = itemView.findViewById(R.id.cw_remaining)
        val progress: android.widget.ProgressBar = itemView.findViewById(R.id.cw_progress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_continue_watching, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries[position]
        holder.title.text = entry.name
        holder.remaining.text = entry.displayRemaining
        holder.progress.progress = entry.progressPercent

        if (entry.logoUrl.isNotBlank()) {
            com.bumptech.glide.Glide.with(holder.itemView.context)
                .load(entry.logoUrl)
                .placeholder(R.drawable.ic_movies)
                .into(holder.poster)
        } else {
            holder.poster.setImageResource(R.drawable.ic_movies)
        }

        holder.itemView.setOnClickListener { onClick(entry) }
        holder.itemView.setOnLongClickListener { onLongClick(entry); true }
        holder.itemView.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    override fun getItemCount() = entries.size
}
