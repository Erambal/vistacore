package com.vistacore.launcher.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.vistacore.launcher.data.ChannelSearch
import com.vistacore.launcher.data.SportsMode
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

    // Live-TV screens render full-screen video behind their overlays; insetting
    // the content root would letterbox the video. Opt out of overscan padding.
    override fun appliesOverscanInsets(): Boolean = false

    protected lateinit var prefs: PrefsManager
    protected lateinit var recents: RecentChannelsManager
    protected val favoritesManager by lazy { FavoritesManager(this) }
    protected val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    protected var player: ExoPlayer? = null
    /** Last PlayerView the preview was attached to. Stored so the base
     *  class can rebuild the player on its own when it had to be released
     *  for a fullscreen handoff or a pause (see goFullScreen / onPause / onResume). */
    private var attachedPlayerView: PlayerView? = null
    /** The preview player was released (fullscreen handoff or pause) and must
     *  be rebuilt + re-tuned on the next onResume. */
    private var needsRebuild = false

    private val handler = Handler(Looper.getMainLooper())

    /** Self-healing driver for the live preview — reconnects on stalls/drops
     *  instead of leaving the picture frozen until the user re-enters the app. */
    private val recovery by lazy {
        LiveStreamRecovery(
            handler = handler,
            player = { player },
            reconnect = { reprepareCurrentStream() },
            onGiveUp = { onLivePlaybackGaveUp() },
            tag = TAG,
        )
    }

    protected var allChannels: List<Channel> = emptyList()
    protected var categoryChannels: List<Channel> = emptyList()
    protected var displayedChannels: List<Channel> = emptyList()
    protected var currentChannel: Channel? = null
    protected var epgData: EpgData? = null
    protected var selectedCategory: String = CATEGORY_ALL

    companion object {
        const val EXTRA_SEARCH_QUERY = "extra_search_query"

        /** Network carrying a game, e.g. "ROOT SPORTS NW" — from the ESPN scoreboard. */
        const val EXTRA_GAME_BROADCAST = "extra_game_broadcast"

        /** Team names for the game, used to match a program title when the network doesn't resolve. */
        const val EXTRA_GAME_TEAMS = "extra_game_teams"

        /** Open straight into the Sports category. */
        const val EXTRA_SPORTS_MODE = "extra_sports_mode"
        const val CATEGORY_ALL = "All"
        const val CATEGORY_RECENT = "Recent"
        const val CATEGORY_FAVORITES = "Favorites"
        const val CATEGORY_SPORTS = SportsMode.CATEGORY_SPORTS

        /** Idle pause before typed channel digits tune on their own. */
        private const val NUMBER_ENTRY_IDLE_MS = 6000L

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
        // Live TV is watched passively — a senior won't touch the remote for a
        // whole show. Without this the Google TV system screensaver (daydream)
        // takes over after ~10 min of no input and kills playback, even mid-show
        // and even while a stall is recovering. The fullscreen player already
        // holds this; every live layout needs it too.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Subclasses call this after their layout is inflated and the PlayerView
     * is available. Sets up ExoPlayer with a generous buffer and attaches it
     * to the given PlayerView.
     */
    protected fun setupPlayer(playerView: PlayerView) {
        attachedPlayerView = playerView
        // Match IPTVPlayerActivity's player config so the preview and the
        // full-screen player behave identically: same buffer timings, same
        // decoder fallback for flaky hardware HEVC, same extension-renderer
        // mode (OFF — we don't bundle any extension renderers).
        // Keep the buffer bounded by bytes so high-bitrate HD can't overrun the
        // heap on low-RAM sticks. Mirrors IPTVPlayerActivity's config (see the
        // OOM note there) — 48MB cap, byte limit enforced (prioritizeTime=false).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 90_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setTargetBufferBytes(48 * 1024 * 1024)
            .build()
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            )
        player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .build().also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) recovery.onHealthy()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Player error: ${error.message}")
                        // Live feeds stall / drop / splice constantly. Reconnect
                        // instead of freezing until the user leaves the app. When
                        // the failure is permanent this just logs (returns false).
                        recovery.onError(error)
                    }
                })
            }
        player?.let { onPlayerBuilt(it) }
        recovery.start()
    }

    /**
     * Called every time a fresh ExoPlayer is built — the initial setup and every
     * rebuild after a fullscreen handoff or a pause/resume. Subclasses attach
     * per-player listeners here (not in onCreate) so they survive a rebuild.
     */
    protected open fun onPlayerBuilt(player: ExoPlayer) {}

    /** Rebuild the current channel's media source and reconnect from the live edge. */
    private fun reprepareCurrentStream() {
        val exo = player ?: return
        val ch = currentChannel ?: return
        exo.setMediaSource(buildMediaSource(ch.streamUrl))
        exo.prepare()
        exo.playWhenReady = true
    }

    /** The live feed failed to recover after repeated reconnects. */
    protected open fun onLivePlaybackGaveUp() {
        android.widget.Toast.makeText(
            this,
            "This channel keeps dropping. Try another, or check back shortly.",
            android.widget.Toast.LENGTH_SHORT
        ).show()
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
                            // Xtream's getChannels() returns LIVE streams only, so this
                            // call is not authoritative for movies/series — caching it as
                            // a full catalog would overwrite both with empty files. M3U
                            // playlists do carry VOD, so those stay full-scope.
                            ChannelUpdateWorker.cacheChannels(
                                this@BaseLiveTVActivity,
                                downloaded,
                                if (prefs.sourceType == PrefsManager.SOURCE_XTREAM)
                                    ChannelUpdateWorker.LIVE_ONLY
                                else ChannelUpdateWorker.ALL_CONTENT_TYPES
                            )
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

                pendingGameBroadcast = intent.getStringExtra(EXTRA_GAME_BROADCAST)?.trim() ?: ""
                pendingGameTeams = intent.getStringArrayExtra(EXTRA_GAME_TEAMS)?.toList() ?: emptyList()
                val wantsSports = intent.getBooleanExtra(EXTRA_SPORTS_MODE, false) ||
                    pendingGameBroadcast.isNotBlank() || pendingGameTeams.isNotEmpty()

                selectedCategory =
                    if (wantsSports && SportsMode.sportsChannels(allChannels, epgData).isNotEmpty()) {
                        CATEGORY_SPORTS
                    } else {
                        initialCategory()
                    }
                categoryChannels = channelsForCategory(selectedCategory)

                // Apply any live search the user has already typed while we
                // were loading, plus any pending query from the launcher.
                val pending = intent.getStringExtra(EXTRA_SEARCH_QUERY)?.trim() ?: ""
                val query = if (pending.isNotBlank()) pending else currentSearchQuery()
                filterChannels(query)
                onLoadingStateChanged(false)
                onChannelsLoaded()
                onCategoriesChanged(buildCategories())

                // A game launch resolves to a specific channel when we can identify the
                // network. Only fall back to first-match / first-channel behaviour when
                // it doesn't, so we never silently tune to something unrelated.
                val tunedToGame = tryResolvePendingGame()
                if (!tunedToGame) {
                    if (pending.isNotBlank() && displayedChannels.isNotEmpty()) {
                        tuneToChannel(displayedChannels.first())
                    } else if (query.isBlank() && !wantsSports) {
                        tuneToChannel(categoryChannels.firstOrNull() ?: allChannels.first())
                    } else if (query.isBlank() && displayedChannels.isNotEmpty()) {
                        tuneToChannel(displayedChannels.first())
                    }
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

    // --- Game launch (from the home screen's upcoming-games row) ---

    private var pendingGameBroadcast: String = ""
    private var pendingGameTeams: List<String> = emptyList()
    private var pendingGameHandled = false

    /**
     * Try to tune directly to the channel carrying the launched game.
     *
     * Runs twice: once as soon as channels load (network-name matching works without a
     * guide) and again after the EPG arrives (team-name matching needs program titles).
     * Returns true once it has tuned, so callers can skip their default tuning.
     */
    private fun tryResolvePendingGame(): Boolean {
        if (pendingGameHandled) return false
        if (pendingGameBroadcast.isBlank() && pendingGameTeams.isEmpty()) return false
        if (allChannels.isEmpty()) return false

        val match = SportsMode.findChannelForBroadcast(pendingGameBroadcast, allChannels, epgData)
            ?: pendingGameTeams.firstNotNullOfOrNull { team ->
                val nickname = team.split(" ").lastOrNull()?.lowercase()
                if (nickname == null || nickname.length < 3) null
                else allChannels.firstOrNull { channel ->
                    val epg = epgData ?: return@firstOrNull false
                    val key = channel.epgId.ifBlank { channel.id }
                    val title = (epg.getNowPlaying(key) ?: epg.getNowPlaying(channel.name))?.title
                    title?.lowercase()?.contains(nickname) == true
                }
            }

        if (match != null) {
            pendingGameHandled = true
            tuneToChannel(match)
            return true
        }

        // Couldn't identify a channel. Leave the user in the Sports category filtered by
        // the most useful term we have — far better than the "no channels matching" dead
        // end a concatenated "Away Home" query produces.
        if (epgData != null) {
            pendingGameHandled = true
            val fallbackTerm = pendingGameBroadcast.takeIf { it.isNotBlank() }
                ?: pendingGameTeams.firstOrNull()?.split(" ")?.lastOrNull()
            if (!fallbackTerm.isNullOrBlank()) filterChannels(fallbackTerm)
        }
        return false
    }

    private fun loadEpg() {
        val cachedEpg = ContentCache.epgData
        if (cachedEpg != null && cachedEpg.programs.isNotEmpty() && ContentCache.isEpgFresh()) {
            epgData = cachedEpg
            refreshDerivedCategoryViews()
            tryResolvePendingGame()
            onEpgLoaded()
            scheduleEpgTick()
            return
        }

        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    if (prefs.sourceType == PrefsManager.SOURCE_XTREAM && prefs.xtreamServer.isNotBlank()) {
                        try {
                            val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                            val xc = XtreamClient(auth)
                            // Favourites and recents are what this user actually watches,
                            // so they get guide coverage right after sports.
                            val priority = favoritesManager.filterFavorites(allChannels)
                                .map { it.id } + recents.getRecentChannels(allChannels).map { it.id }
                            val epg = xc.getEpg(allChannels, priority)
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
                    // Sports detection leans on program titles, so the Sports category
                    // can appear (or grow) only once the guide lands.
                    refreshDerivedCategoryViews()
                    // Team-name matching needs program titles, so retry now.
                    tryResolvePendingGame()
                }
                onEpgLoaded()
                scheduleEpgTick()
            } catch (e: Exception) {
                Log.e(TAG, "EPG load failed", e)
            }
        }
    }

    /**
     * True when the last [filterChannels] call had to look outside the selected category
     * to find anything. Variants can surface this so the user understands why results
     * from "All" are showing while the category button still says "Recent".
     */
    protected var searchEscapedCategory: Boolean = false
        private set

    protected fun filterChannels(query: String) {
        if (query.isBlank()) {
            searchEscapedCategory = false
            displayedChannels = categoryChannels
            onDisplayedChannelsChanged()
            return
        }

        // Search the selected category first, but fall back to the whole lineup rather
        // than reporting "no matches". The default landing category is Recent (10
        // channels), so a category-scoped search told users a channel they own does not
        // exist — the single most confusing failure in the app.
        val inCategory = ChannelSearch.searchChannels(categoryChannels, query, epgData)
        displayedChannels = if (inCategory.isNotEmpty()) {
            searchEscapedCategory = false
            inCategory
        } else {
            val everywhere = ChannelSearch.searchChannels(allChannels, query, epgData)
            searchEscapedCategory = everywhere.isNotEmpty()
            everywhere
        }
        onDisplayedChannelsChanged()
    }

    protected fun selectCategory(name: String) {
        selectedCategory = name
        categoryChannels = channelsForCategory(name)
        // Re-apply any active search query so changing category while the user
        // has typed something doesn't wipe out their filter.
        filterChannels(currentSearchQuery())
        onCategoriesChanged(buildCategories())
    }

    private fun channelsForCategory(name: String): List<Channel> = when (name) {
        CATEGORY_ALL -> allChannels
        CATEGORY_RECENT -> recents.getRecentChannels(allChannels)
        CATEGORY_FAVORITES -> favoritesManager.filterFavorites(allChannels)
        CATEGORY_SPORTS -> SportsMode.sportsChannels(allChannels, epgData)
        else -> allChannels.filter { it.category == name }
    }

    /**
     * Subclasses should return the live contents of their search EditText so
     * the base class can re-apply the filter after category changes / channel
     * reloads. Default is empty (no filter).
     */
    protected open fun currentSearchQuery(): String = ""

    /**
     * Which category to land on when channels finish loading. Default lands on
     * Recent when the user has resolvable history, otherwise All. Variants
     * without a category picker (Carousel groups by row instead) should pin
     * this to All so the user isn't trapped behind a hidden Recent filter.
     */
    protected open fun initialCategory(): String =
        if (recents.getRecentChannels(allChannels).isNotEmpty()) CATEGORY_RECENT
        else CATEGORY_ALL

    protected fun buildCategories(): List<String> {
        // Order: Recent first (when the user has resolvable history), then
        // Favorites, then All, then provider categories. We resolve against
        // allChannels so a stale stored ID from an old lineup doesn't surface
        // an empty Recent / Favorites category in the picker.
        val cats = mutableListOf<String>()
        if (recents.getRecentChannels(allChannels).isNotEmpty()) cats.add(CATEGORY_RECENT)
        if (favoritesManager.filterFavorites(allChannels).isNotEmpty()) cats.add(CATEGORY_FAVORITES)
        // Sports sits above All and above the provider's own categories: it's the one
        // people hunt for, and provider category names are inconsistent enough that
        // finding sports by browsing them is unreliable.
        if (SportsMode.sportsChannels(allChannels, epgData).isNotEmpty()) cats.add(CATEGORY_SPORTS)
        cats.add(CATEGORY_ALL)
        allChannels.map { it.category }.distinct().sorted().forEach { cats.add(it) }
        return cats
    }

    protected fun tuneToChannel(channel: Channel) {
        val previous = currentChannel
        currentChannel = channel
        prefs.lastChannel = channel.id
        recents.addRecent(channel.id)
        // A new recent may add the Recent category to the picker for the first
        // time, and reorders the Recent list if we're already viewing it.
        refreshDerivedCategoryViews()

        player?.let { exo ->
            // Fresh channel — start the reconnect budget over.
            recovery.reset()
            val source = buildMediaSource(channel.streamUrl)
            exo.setMediaSource(source)
            exo.prepare()
            exo.playWhenReady = true
        }

        onSelectedChannelChanged(previous, channel)
    }

    /**
     * Toggle a channel's favorite state and refresh any derived category UI
     * (the picker entry, and the displayed list when viewing Favorites).
     * Returns the new favorite state.
     */
    protected fun toggleChannelFavorite(channelId: String): Boolean {
        val nowFav = favoritesManager.toggleFavoriteChannel(channelId)
        refreshDerivedCategoryViews()
        return nowFav
    }

    /**
     * Recompute the category list and, when the user is currently viewing a
     * derived category (Recent / Favorites), refresh its channel contents.
     * If the active category just disappeared (e.g. last favorite removed),
     * fall back to All so the user isn't stranded on an empty view.
     */
    private fun refreshDerivedCategoryViews() {
        if (allChannels.isEmpty()) return
        val cats = buildCategories()
        when {
            selectedCategory !in cats -> {
                selectedCategory = CATEGORY_ALL
                categoryChannels = allChannels
                filterChannels(currentSearchQuery())
            }
            selectedCategory == CATEGORY_RECENT || selectedCategory == CATEGORY_FAVORITES ||
                selectedCategory == CATEGORY_SPORTS -> {
                categoryChannels = channelsForCategory(selectedCategory)
                filterChannels(currentSearchQuery())
            }
        }
        onCategoriesChanged(cats)
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
        // Free the preview player's hardware decoder before launching
        // the fullscreen activity. On budget Fire TV / Google TV boxes
        // there's only one performant H.264/HEVC decoder, so leaving
        // the preview alive (even paused) makes fullscreen fall back to
        // a software decoder and stutter. The preview gets rebuilt in
        // onResume when the user returns from fullscreen.
        recovery.stop()
        player?.release()
        player = null
        needsRebuild = true

        val intent = Intent(this, IPTVPlayerActivity::class.java).apply {
            putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, channel.logoUrl)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_ID, channel.id)
        }
        startActivity(intent)
    }

    /**
     * Pop the channel-number dialog when the user types a digit (0–9) on a
     * remote that actually has number keys. Stick remotes (Onn, Fire) don't
     * — they use the visible "Go to Channel #" button. The digit they
     * pressed is pre-filled so they don't have to retype it. Skipped when
     * an EditText already has focus, so typing into the search field works
     * normally.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.keyCode in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 &&
            currentFocus !is android.widget.EditText &&
            allChannels.isNotEmpty()
        ) {
            val digit = event.keyCode - android.view.KeyEvent.KEYCODE_0
            showNumberPadOverlay(prefill = digit.toString())
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Long-press OK / DPAD_CENTER on any channel cell pops this menu.
     * Single gesture, two actions — works on every layout (vertical
     * sidebars and horizontal ribbons alike) without colliding with
     * D-pad navigation. Replaces the prior long-press-RIGHT favorite
     * gesture which only made sense in vertical layouts.
     */
    protected fun showChannelContextMenu(channel: Channel) {
        val isFav = favoritesManager.isFavoriteChannel(channel.id)
        val favLabel = if (isFav) "★  Remove from favorites" else "☆  Add to favorites"
        val items = arrayOf(favLabel, "#  Go to channel…")
        androidx.appcompat.app.AlertDialog.Builder(this, com.vistacore.launcher.R.style.Theme_VistaCore_Dialog)
            .setTitle(channel.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        toggleChannelFavorite(channel.id)
                        // Rebuild the visible list so the heart icon reflects
                        // the new state — refreshDerivedCategoryViews only
                        // rebinds for Recent / Favorites views, not All.
                        onDisplayedChannelsChanged()
                    }
                    1 -> showNumberPadOverlay()
                }
            }
            .show()
    }

    protected fun showNumberPadOverlay(prefill: String = "") {
        if (allChannels.isEmpty()) return
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Enter channel number"
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            setTextColor(getColor(com.vistacore.launcher.R.color.text_primary))
            setHintTextColor(getColor(com.vistacore.launcher.R.color.text_hint))
            setPadding(32, 24, 32, 24)
            if (prefill.isNotEmpty()) {
                setText(prefill)
                setSelection(prefill.length)
            }
        }

        // "Go" is the primary, deliberate commit path. Previously the ONLY way to submit
        // was the idle timer, so a user who typed slowly had no way to say "I'm done".
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Go to Channel")
            .setView(input)
            .setPositiveButton("Go", null) // click wired after show() so it can stay open
            .setNegativeButton("Cancel", null)
            .create()

        // Try to tune. Returns true if a channel was found and tuned.
        fun submit(): Boolean {
            val num = input.text.toString().trim().toIntOrNull() ?: return false
            val channel = allChannels.find { it.number == num }
            return if (channel != null) {
                tuneToChannel(channel)
                dialog.dismiss()
                true
            } else {
                android.widget.Toast.makeText(this, "Channel $num not found", android.widget.Toast.LENGTH_SHORT).show()
                false
            }
        }

        input.setOnEditorActionListener { _, _, _ -> submit(); true }

        // Auto-submit after an idle pause — matches the cable-box pattern where you key in
        // 5-0-2 and the box just tunes a moment later. The window is generous because
        // D-padding across a leanback soft keyboard from '5' to '0' takes an older user
        // well over two seconds, and firing early tuned them to the wrong channel and
        // closed the dialog. "Go" exists for anyone who does not want to wait.
        val autoSubmit = Runnable { submit() }
        fun rearmAutoSubmit() {
            input.removeCallbacks(autoSubmit)
            if (input.text.isNotEmpty()) input.postDelayed(autoSubmit, NUMBER_ENTRY_IDLE_MS)
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = rearmAutoSubmit()
        })
        if (prefill.isNotEmpty()) input.postDelayed(autoSubmit, NUMBER_ENTRY_IDLE_MS)
        dialog.setOnDismissListener { input.removeCallbacks(autoSubmit) }

        dialog.show()
        // Wired after show() so a failed submit leaves the dialog open (the builder's
        // own click listener always dismisses).
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            ?.setOnClickListener {
                input.removeCallbacks(autoSubmit)
                if (!submit()) rearmAutoSubmit()
            }
        input.requestFocus()
        input.postDelayed({
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    // --- Guide roll-over ---
    //
    // A guide bound once at load time keeps showing the previous program forever. These
    // screens routinely sit open for hours, so "now playing" has to advance on its own.
    //
    // Ticks are scheduled at the actual next program boundary rather than on a blind
    // interval — the guide only changes when a program ends, so polling every minute
    // would be ~30 wasted wakeups per useful one on a box that's already RAM-tight.

    /** Never tick faster than this, even if timestamps say a program ends immediately. */
    private val minEpgTickMs = 20_000L

    /** Re-check at least this often so we recover if the schedule shifts underneath us. */
    private val maxEpgTickMs = 10 * 60_000L

    private val epgTickRunnable = Runnable {
        onEpgTick()
        scheduleEpgTick()
    }

    private fun scheduleEpgTick() {
        handler.removeCallbacks(epgTickRunnable)
        val epg = epgData ?: return
        if (isFinishing || isDestroyed) return

        val keys = buildList {
            currentChannel?.let { add(it.epgId.ifBlank { it.id }) }
            displayedChannels.forEach { add(it.epgId.ifBlank { it.id }) }
        }
        val boundary = epg.nextProgramBoundary(keys)?.time
        val delay = if (boundary == null) {
            maxEpgTickMs
        } else {
            // +1s so we land just past the boundary, not exactly on it.
            (boundary - System.currentTimeMillis() + 1000L).coerceIn(minEpgTickMs, maxEpgTickMs)
        }
        handler.postDelayed(epgTickRunnable, delay)
    }

    /**
     * A program boundary passed — refresh anything showing "now playing".
     *
     * Subclasses must not rebuild focusable lists unconditionally here: every layout's
     * list refresh swaps in a fresh adapter, which resets D-pad focus and scroll
     * position. Update the detail/strip views always, and only rebuild the list when it
     * doesn't currently hold focus (i.e. the user isn't browsing it).
     */
    protected open fun onEpgTick() {}

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(epgTickRunnable)
        releasePlaybackWifiLock()
        // Release the decoder + buffer while we're off-screen so a 2 GB box
        // isn't pinning a paused stream's hardware decoder and tens of MB of
        // buffer while the user is in another app/screen — that memory is what
        // gets the box swap-thrashing. Rebuilt + re-tuned in onResume. Skip when
        // finishing (onDestroy handles that) or when goFullScreen already
        // released for the handoff.
        if (!isFinishing && player != null) {
            recovery.stop()
            player?.release()
            player = null
            needsRebuild = true
        } else {
            player?.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        acquirePlaybackWifiLock()
        // Coming back from a fullscreen handoff or a pause: the preview player
        // was released to free the hardware decoder. Rebuild it now and tune
        // back to whatever the user had selected.
        if (needsRebuild && player == null) {
            needsRebuild = false
            attachedPlayerView?.let { setupPlayer(it) }
            currentChannel?.let { tuneToChannel(it) }
        } else {
            player?.play()
        }

        // Time passed while we were away, so the guide is almost certainly behind.
        // Refresh what's on now, and refetch outright if the data itself has expired.
        // Returning to the screen is the safe moment to do this — the user isn't
        // mid-browse, so a full rebind can't yank focus out from under them.
        if (epgData != null) {
            onEpgTick()
            if (!ContentCache.isEpgFresh()) loadEpg() else scheduleEpgTick()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recovery.stop()
        handler.removeCallbacksAndMessages(null)
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
