package com.vistacore.launcher.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.vistacore.launcher.data.ContentCache
import com.vistacore.launcher.data.FavoritesManager
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.data.RecentChannelsManager
import com.vistacore.launcher.iptv.*
import com.vistacore.launcher.system.ChannelUpdateWorker
import kotlinx.coroutines.*

/**
 * Shared logic for all Live TV layout variants: data loading, player setup,
 * EPG, search, favorites, number pad, fullscreen handoff.
 *
 * Subclasses handle:
 * - Layout inflation (onCreate)
 * - Wiring UI state (onChannelsLoaded, onCategoriesChanged, onSelectedChannelChanged, onEpgLoaded)
 * - Custom key handling as needed
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
abstract class BaseLiveTVActivity : BaseActivity() {

    protected lateinit var prefs: PrefsManager
    protected lateinit var recents: RecentChannelsManager
    protected val favoritesManager by lazy { FavoritesManager(this) }
    protected val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    protected var player: ExoPlayer? = null
    protected var allChannels: List<Channel> = emptyList()
    protected var categoryChannels: List<Channel> = emptyList()
    protected var displayedChannels: List<Channel> = emptyList()
    protected var currentChannel: Channel? = null
    protected var epgData: EpgData? = null
    protected var selectedCategory: String = CATEGORY_ALL

    companion object {
        const val EXTRA_SEARCH_QUERY = "extra_search_query"
        const val CATEGORY_ALL = "All"
        const val CATEGORY_RECENT = "Recent"
        const val CATEGORY_FAVORITES = "Favorites"
        private const val TAG = "LiveTV"
    }

    private val httpDataSourceFactory by lazy {
        DefaultHttpDataSource.Factory()
            .setUserAgent("VistaCore/1.0")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsManager(this)
        recents = RecentChannelsManager(this)
    }

    /**
     * Subclasses call this after their layout is inflated and the PlayerView
     * is available. Sets up ExoPlayer with a generous buffer and attaches it
     * to the given PlayerView.
     */
    protected fun setupPlayer(playerView: PlayerView) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(60_000, 180_000, 5_000, 10_000)
            .build()
        player = ExoPlayer.Builder(this).setLoadControl(loadControl).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Player error: ${error.message}")
                }
            })
        }
    }

    /** Load channels (cache first, then network). Triggers onChannelsLoaded and loadEpg. */
    protected fun loadChannels() {
        onLoadingStateChanged(true)

        scope.launch {
            val cached = withContext(Dispatchers.IO) {
                ChannelUpdateWorker.getCachedChannels(this@BaseLiveTVActivity)
            }

            val loadedChannels: List<Channel> = if (cached != null && cached.isNotEmpty()) {
                cached
            } else {
                Log.d(TAG, "No cache — downloading channels directly")
                try {
                    val downloaded = withContext(Dispatchers.IO) {
                        when (prefs.sourceType) {
                            PrefsManager.SOURCE_M3U -> M3UParser().parse(prefs.m3uUrl)
                            PrefsManager.SOURCE_XTREAM -> {
                                val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                                XtreamClient(auth).getChannels()
                            }
                            else -> emptyList()
                        }
                    }
                    if (downloaded.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            ChannelUpdateWorker.cacheChannels(this@BaseLiveTVActivity, downloaded)
                        }
                        downloaded.filter { it.contentType == ContentType.LIVE }
                    } else emptyList()
                } catch (e: Exception) {
                    Log.e(TAG, "Direct download failed", e)
                    emptyList()
                }
            }

            if (loadedChannels.isNotEmpty()) {
                allChannels = loadedChannels.sortedBy { it.number }
                // Default to the Recent category when the user has any
                // watch history so the channels they actually use are the
                // first thing on screen. Falls back to All on first launch.
                val startInRecents = recents.hasRecents() &&
                    recents.getRecentChannels(allChannels).isNotEmpty()
                selectedCategory = if (startInRecents) CATEGORY_RECENT else CATEGORY_ALL
                categoryChannels = if (startInRecents) {
                    recents.getRecentChannels(allChannels)
                } else {
                    allChannels
                }
                // Apply any live search the user has already typed while we
                // were loading, plus any pending query from the launcher.
                val pending = intent.getStringExtra(EXTRA_SEARCH_QUERY)?.trim() ?: ""
                val query = if (pending.isNotBlank()) pending else currentSearchQuery()
                filterChannels(query)
                onLoadingStateChanged(false)
                onChannelsLoaded()
                onCategoriesChanged(buildCategories())

                if (pending.isNotBlank() && displayedChannels.isNotEmpty()) {
                    tuneToChannel(displayedChannels.first())
                } else if (query.isBlank()) {
                    tuneToChannel(categoryChannels.firstOrNull() ?: allChannels.first())
                }

                loadEpg()
            } else {
                onLoadingStateChanged(false)
                android.widget.Toast.makeText(
                    this@BaseLiveTVActivity,
                    "No channels found. Check your IPTV settings.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadEpg() {
        val cachedEpg = ContentCache.epgData
        val epgAge = System.currentTimeMillis() - ContentCache.epgLoadTime
        if (cachedEpg != null && cachedEpg.programs.isNotEmpty() && epgAge < 30 * 60 * 1000L) {
            epgData = cachedEpg
            onEpgLoaded()
            return
        }

        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    if (prefs.sourceType == PrefsManager.SOURCE_XTREAM && prefs.xtreamServer.isNotBlank()) {
                        try {
                            val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                            val xc = XtreamClient(auth)
                            val epg = xc.getEpg(allChannels)
                            if (epg.programs.isNotEmpty()) return@withContext epg
                        } catch (e: Exception) {
                            Log.w(TAG, "Xtream native EPG failed: ${e.message}")
                        }
                    }

                    var epgUrl = prefs.epgUrl
                    if (epgUrl.isBlank() && prefs.sourceType == PrefsManager.SOURCE_XTREAM && prefs.xtreamServer.isNotBlank()) {
                        val s = prefs.xtreamServer.trimEnd('/')
                        epgUrl = "$s/xmltv.php?username=${prefs.xtreamUsername}&password=${prefs.xtreamPassword}"
                    }
                    if (epgUrl.isNotBlank()) EpgParser().parse(epgUrl) else null
                }

                if (loaded != null && loaded.programs.isNotEmpty()) {
                    epgData = loaded
                    ContentCache.epgData = loaded
                    ContentCache.epgLoadTime = System.currentTimeMillis()
                    Log.d(TAG, "EPG loaded: ${loaded.programs.size} programs")
                }
                onEpgLoaded()
            } catch (e: Exception) {
                Log.e(TAG, "EPG load failed", e)
            }
        }
    }

    protected fun filterChannels(query: String) {
        val base = categoryChannels
        displayedChannels = if (query.isBlank()) {
            base
        } else {
            val asNum = query.toIntOrNull()
            if (asNum != null) {
                base.filter { it.number.toString().startsWith(query) }
            } else {
                base.filter { channel ->
                    channel.name.contains(query, ignoreCase = true) ||
                        channel.category.contains(query, ignoreCase = true) ||
                        (epgData?.let { epg ->
                            val epgKey = channel.epgId.ifBlank { channel.id }
                            val now = epg.getNowPlaying(epgKey) ?: epg.getNowPlaying(channel.name)
                            now?.title?.contains(query, ignoreCase = true) == true
                        } ?: false)
                }
            }
        }
        onDisplayedChannelsChanged()
    }

    protected fun selectCategory(name: String) {
        selectedCategory = name
        categoryChannels = when (name) {
            CATEGORY_ALL -> allChannels
            CATEGORY_RECENT -> recents.getRecentChannels(allChannels)
            CATEGORY_FAVORITES -> favoritesManager.filterFavorites(allChannels)
            else -> allChannels.filter { it.category == name }
        }
        // Re-apply any active search query so changing category while the user
        // has typed something doesn't wipe out their filter.
        filterChannels(currentSearchQuery())
        onCategoriesChanged(buildCategories())
    }

    /**
     * Subclasses should return the live contents of their search EditText so
     * the base class can re-apply the filter after category changes / channel
     * reloads. Default is empty (no filter).
     */
    protected open fun currentSearchQuery(): String = ""

    protected fun buildCategories(): List<String> {
        // Order: Recent first (when the user has history), then Favorites,
        // then All, then provider categories. Recent leads so the channels
        // the user actually watches are the default landing spot in the
        // category picker.
        val cats = mutableListOf<String>()
        if (recents.hasRecents()) cats.add(CATEGORY_RECENT)
        if (favoritesManager.getFavoriteChannelIds().isNotEmpty()) cats.add(CATEGORY_FAVORITES)
        cats.add(CATEGORY_ALL)
        allChannels.map { it.category }.distinct().sorted().forEach { cats.add(it) }
        return cats
    }

    protected fun tuneToChannel(channel: Channel) {
        val previous = currentChannel
        currentChannel = channel
        prefs.lastChannel = channel.id
        recents.addRecent(channel.id)

        player?.let { exo ->
            val source = buildMediaSource(channel.streamUrl)
            exo.setMediaSource(source)
            exo.prepare()
            exo.playWhenReady = true
        }

        onSelectedChannelChanged(previous, channel)
    }

    private fun buildMediaSource(url: String): MediaSource {
        val uri = Uri.parse(url)
        val mediaItem = MediaItem.fromUri(uri)
        return if (url.lowercase().contains(".m3u8")) {
            HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(httpDataSourceFactory)
                .createMediaSource(mediaItem)
        }
    }

    protected fun goFullScreen(channel: Channel) {
        val intent = Intent(this, IPTVPlayerActivity::class.java).apply {
            putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, channel.logoUrl)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_ID, channel.id)
        }
        startActivity(intent)
    }

    protected fun showNumberPadOverlay() {
        if (allChannels.isEmpty()) return
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Enter channel number"
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            setTextColor(getColor(com.vistacore.launcher.R.color.text_primary))
            setHintTextColor(getColor(com.vistacore.launcher.R.color.text_hint))
            setPadding(32, 24, 32, 24)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Go to Channel")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .create()

        input.setOnEditorActionListener { _, _, _ ->
            val num = input.text.toString().trim().toIntOrNull()
            if (num != null) {
                val channel = allChannels.find { it.number == num }
                if (channel != null) {
                    tuneToChannel(channel)
                    dialog.dismiss()
                } else {
                    android.widget.Toast.makeText(this, "Channel $num not found", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            true
        }

        dialog.show()
        input.requestFocus()
        input.postDelayed({
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        scope.cancel()
    }

    // --- Subclass hooks (override what you need) ---

    /** Called when channels finish loading. Subclasses wire up their UI here. */
    protected open fun onChannelsLoaded() {}

    /** Called when category list changes (e.g. after favorites/recents added). */
    protected open fun onCategoriesChanged(categories: List<String>) {}

    /**
     * Bind a Button as the category picker. The button's label reflects the
     * current category and tapping it opens a modal list of all categories.
     * Used by every LiveTV layout in place of the old horizontal chip strip —
     * one focusable element, zero scroll, no lost cursor.
     */
    protected fun bindCategoryButton(button: android.widget.Button, categories: List<String>) {
        button.text = "Category: $selectedCategory  ▾"
        button.setOnClickListener {
            val current = categories.indexOf(selectedCategory).coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(this, com.vistacore.launcher.R.style.Theme_VistaCore_Dialog)
                .setTitle("Choose Category")
                .setSingleChoiceItems(categories.toTypedArray(), current) { dialog, which ->
                    selectCategory(categories[which])
                    dialog.dismiss()
                }
                .show()
        }
        button.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    /** Called when the displayed channel list changes (filter/category). */
    protected open fun onDisplayedChannelsChanged() {}

    /** Called when the user picks a new channel (previous is the old selection). */
    protected open fun onSelectedChannelChanged(previous: Channel?, current: Channel) {}

    /** Called when EPG data becomes available. */
    protected open fun onEpgLoaded() {}

    /** Called when the loading spinner should show/hide. */
    protected open fun onLoadingStateChanged(loading: Boolean) {}
}
