package com.vistacore.launcher.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vistacore.launcher.R
import com.vistacore.launcher.data.ContentCache
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.data.RecentChannelsManager
import com.vistacore.launcher.databinding.ActivityLivetvBinding
import com.vistacore.launcher.iptv.*
import com.vistacore.launcher.system.ChannelUpdateWorker
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveTVActivity : BaseActivity() {

    private lateinit var binding: ActivityLivetvBinding
    private lateinit var prefs: PrefsManager
    private lateinit var recents: RecentChannelsManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var player: ExoPlayer? = null
    private var allChannels: List<Channel> = emptyList()
    private var categoryChannels: List<Channel> = emptyList() // channels filtered by category
    private var displayedChannels: List<Channel> = emptyList()
    private var currentChannel: Channel? = null
    private var epgData: EpgData? = null
    private var selectedCategory: String = CATEGORY_ALL

    companion object {
        const val EXTRA_SEARCH_QUERY = "extra_search_query"
        private const val CATEGORY_ALL = "All"
        private const val CATEGORY_RECENT = "Recent"
        private const val CATEGORY_FAVORITES = "Favorites"
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
        binding = ActivityLivetvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)
        recents = RecentChannelsManager(this)

        binding.channelList.layoutManager = LinearLayoutManager(this)

        setupSearch()
        setupPlayer()
        loadChannels()

        // If launched with a search query (e.g. from Upcoming Games), pre-fill it
        intent.getStringExtra(EXTRA_SEARCH_QUERY)?.let { query ->
            if (query.isNotBlank()) {
                binding.channelSearch.setText(query)
                // Don't show keyboard — just filter and focus the channel list
                binding.channelSearch.clearFocus()
                binding.channelSidebar.requestFocus()
                window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            }
        }
    }

    private fun setupSearch() {
        // Prevent keyboard from popping up on launch
        binding.channelSearch.clearFocus()
        binding.channelSidebar.requestFocus()

        binding.channelSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                filterChannels(query)
            }
        })

        // Number pad button — only reachable via channel list items (they set nextFocusRightId)
        binding.btnNumberPad.setOnClickListener { showNumberPadOverlay() }
        binding.btnNumberPad.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
        binding.btnNumberPad.nextFocusLeftId = R.id.channel_list

        // EPG button — opens full TV Guide
        binding.btnToggleEpg.setOnClickListener {
            startActivity(Intent(this, EpgGuideActivity::class.java))
        }
        binding.btnToggleEpg.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }

        // Category chips — horizontal scrollable filter.
        // FocusTrappedRecyclerView handles D-pad boundary blocking via dispatchKeyEvent.
        binding.categoryChips.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
    }

    private fun showNumberPadOverlay() {
        if (allChannels.isEmpty()) return

        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Enter channel number"
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_hint))
            setPadding(32, 24, 32, 24)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Go to Channel")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .create()

        // Accept on Enter/Done key press — no need to press "Go"
        input.setOnEditorActionListener { _, actionId, _ ->
            val num = input.text.toString().trim().toIntOrNull()
            if (num != null) {
                val channel = allChannels.find { it.number == num }
                if (channel != null) {
                    tuneToChannel(channel)
                    val idx = displayedChannels.indexOf(channel)
                    if (idx >= 0) binding.channelList.scrollToPosition(idx)
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

    private fun filterChannels(query: String) {
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
                    // Also search EPG now-playing program titles
                    (epgData?.let { epg ->
                        val epgKey = channel.epgId.ifBlank { channel.id }
                        val now = epg.getNowPlaying(epgKey) ?: epg.getNowPlaying(channel.name)
                        now?.title?.contains(query, ignoreCase = true) == true
                    } ?: false)
                }
            }
        }
        updateChannelList()
    }

    private fun selectCategory(name: String) {
        selectedCategory = name
        categoryChannels = when (name) {
            CATEGORY_ALL -> allChannels
            CATEGORY_RECENT -> recents.getRecentChannels(allChannels)
            CATEGORY_FAVORITES -> favoritesManager.filterFavorites(allChannels)
            else -> allChannels.filter { it.category == name }
        }
        binding.channelSearch.setText("")
        displayedChannels = categoryChannels
        updateChannelList()
        updateCategoryChips()
    }

    private fun buildCategories(): List<String> {
        val cats = mutableListOf(CATEGORY_ALL)
        if (recents.hasRecents()) cats.add(CATEGORY_RECENT)
        if (favoritesManager.getFavoriteChannelIds().isNotEmpty()) cats.add(CATEGORY_FAVORITES)
        allChannels.map { it.category }.distinct().sorted().forEach { cats.add(it) }
        return cats
    }

    private fun updateCategoryChips() {
        val cats = buildCategories()
        binding.categoryChips.adapter = CategoryChipAdapter(cats, selectedCategory) { selectCategory(it) }
    }

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.miniPlayer.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e("LiveTV", "Player error: ${error.message}")
                }
            })
        }
    }

    private fun loadChannels() {
        showLoading(true)

        scope.launch {
            // Try cache first
            val cached = withContext(Dispatchers.IO) {
                ChannelUpdateWorker.getCachedChannels(this@LiveTVActivity)
            }

            val loadedChannels: List<Channel> = if (cached != null && cached.isNotEmpty()) {
                cached
            } else {
                // Cache empty — try downloading directly
                Log.d("LiveTV", "No cache — downloading channels directly")
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
                            ChannelUpdateWorker.cacheChannels(this@LiveTVActivity, downloaded)
                        }
                        downloaded.filter { it.contentType == ContentType.LIVE }
                    } else emptyList()
                } catch (e: Exception) {
                    Log.e("LiveTV", "Direct download failed", e)
                    emptyList()
                }
            }

            if (loadedChannels.isNotEmpty()) {
                allChannels = loadedChannels.sortedBy { it.number }
                categoryChannels = allChannels
                showLoading(false)

                // Build category chips
                updateCategoryChips()

                // Apply any pre-filled search query (e.g. from Upcoming Games)
                val pendingQuery = binding.channelSearch.text?.toString()?.trim() ?: ""
                if (pendingQuery.isNotBlank()) {
                    filterChannels(pendingQuery)
                    // Auto-play first matching channel if available
                    if (displayedChannels.isNotEmpty()) {
                        tuneToChannel(displayedChannels.first())
                    }
                } else {
                    displayedChannels = allChannels
                    updateChannelList()
                    tuneToChannel(allChannels.first())
                }

                // Focus the first channel in the list
                binding.channelList.post {
                    binding.channelList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }

                // Load EPG in background
                loadEpg()
            } else {
                showLoading(false)
                android.widget.Toast.makeText(
                    this@LiveTVActivity,
                    "No channels found. Check your IPTV settings.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadEpg() {
        // Use shared EPG cache if available and fresh (< 30 min)
        val cachedEpg = ContentCache.epgData
        val epgAge = System.currentTimeMillis() - ContentCache.epgLoadTime
        if (cachedEpg != null && cachedEpg.programs.isNotEmpty() && epgAge < 30 * 60 * 1000L) {
            epgData = cachedEpg
            updateChannelList()
            currentChannel?.let { updateEpgStrip(it) }
            return
        }

        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    // Try Xtream native EPG API first (more reliable)
                    if (prefs.sourceType == PrefsManager.SOURCE_XTREAM && prefs.xtreamServer.isNotBlank()) {
                        try {
                            val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                            val xc = XtreamClient(auth)
                            val epg = xc.getEpg(allChannels)
                            if (epg.programs.isNotEmpty()) {
                                Log.d("LiveTV", "Xtream native EPG: ${epg.programs.size} programs")
                                return@withContext epg
                            }
                        } catch (e: Exception) {
                            Log.w("LiveTV", "Xtream native EPG failed: ${e.message}")
                        }
                    }

                    // Fallback to XMLTV
                    var epgUrl = prefs.epgUrl
                    if (epgUrl.isBlank() && prefs.sourceType == PrefsManager.SOURCE_XTREAM &&
                        prefs.xtreamServer.isNotBlank()) {
                        val s = prefs.xtreamServer.trimEnd('/')
                        epgUrl = "$s/xmltv.php?username=${prefs.xtreamUsername}&password=${prefs.xtreamPassword}"
                    }
                    if (epgUrl.isNotBlank()) {
                        Log.d("LiveTV", "Trying XMLTV EPG: $epgUrl")
                        EpgParser().parse(epgUrl)
                    } else null
                }

                if (loaded != null && loaded.programs.isNotEmpty()) {
                    epgData = loaded
                    ContentCache.epgData = loaded
                    ContentCache.epgLoadTime = System.currentTimeMillis()
                    Log.d("LiveTV", "EPG loaded: ${loaded.programs.size} programs")
                } else {
                    Log.w("LiveTV", "EPG loaded but empty")
                }
                // Re-apply search filter in case EPG data now matches
                val currentQuery = binding.channelSearch.text?.toString()?.trim() ?: ""
                if (currentQuery.isNotBlank()) {
                    filterChannels(currentQuery)
                } else {
                    updateChannelList()
                }
                currentChannel?.let { updateEpgStrip(it) }
            } catch (e: Exception) {
                Log.e("LiveTV", "EPG load failed", e)
            }
        }
    }

    private var channelAdapter: LiveChannelAdapter? = null

    private val favoritesManager by lazy { com.vistacore.launcher.data.FavoritesManager(this) }

    private fun updateChannelList() {
        binding.channelCountLabel.text = "${displayedChannels.size} ch"
        channelAdapter = LiveChannelAdapter(displayedChannels, epgData, true, currentChannel, favoritesManager) { ch ->
            if (ch.id == currentChannel?.id) {
                goFullScreen(ch)
            } else {
                tuneToChannel(ch)
            }
        }
        binding.channelList.adapter = channelAdapter

        // Show/hide "no results" message
        val searchQuery = binding.channelSearch.text?.toString()?.trim() ?: ""
        if (displayedChannels.isEmpty() && searchQuery.isNotEmpty()) {
            val hint = if (epgData == null || epgData?.programs?.isEmpty() == true) {
                "No channels matching \"$searchQuery\"\nGuide data still loading…"
            } else {
                "No channels matching \"$searchQuery\""
            }
            binding.noResultsText.text = hint
            binding.noResultsText.visibility = View.VISIBLE
            binding.channelList.visibility = View.GONE
        } else {
            binding.noResultsText.visibility = View.GONE
            binding.channelList.visibility = View.VISIBLE
        }
    }

    private fun tuneToChannel(channel: Channel) {
        val previousChannel = currentChannel
        currentChannel = channel
        prefs.lastChannel = channel.id
        recents.addRecent(channel.id)

        // Update strip
        binding.stripChannelName.text = channel.name
        updateEpgStrip(channel)

        // Play stream
        player?.let { exo ->
            val source = buildMediaSource(channel.streamUrl)
            exo.setMediaSource(source)
            exo.prepare()
            exo.playWhenReady = true
        }

        // Update highlight without rebuilding the adapter
        channelAdapter?.let { adapter ->
            adapter.currentChannel = channel
            // Only notify the two changed items
            val oldIdx = displayedChannels.indexOf(previousChannel)
            val newIdx = displayedChannels.indexOf(channel)
            if (oldIdx >= 0) adapter.notifyItemChanged(oldIdx)
            if (newIdx >= 0) adapter.notifyItemChanged(newIdx)
        }
    }

    private fun updateEpgStrip(channel: Channel) {
        val epg = epgData ?: return
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())

        // Find now playing
        val now = epg.getNowPlaying(channel.epgId.ifBlank { channel.id })
            ?: epg.getNowPlaying(channel.name)

        if (now != null) {
            binding.stripNowTitle.text = now.title
            binding.stripNowTime.text = "${fmt.format(now.startTime)} - ${fmt.format(now.endTime)}"
            binding.stripProgress.progress = (now.progress * 100).toInt()
        } else {
            binding.stripNowTitle.text = "No guide info"
            binding.stripNowTime.text = ""
            binding.stripProgress.progress = 0
        }

        // Find up next
        val upcoming = epg.getUpcoming(channel.epgId.ifBlank { channel.id }, 3)
            .let { if (it.isEmpty()) epg.getUpcoming(channel.name, 3) else it }

        if (upcoming.isNotEmpty()) {
            val next = upcoming.first()
            binding.stripNextTitle.text = next.title
            binding.stripNextTime.text = fmt.format(next.startTime)
        } else {
            binding.stripNextTitle.text = ""
            binding.stripNextTime.text = ""
        }
    }

    private fun buildMediaSource(url: String): MediaSource {
        val uri = Uri.parse(url)
        val mediaItem = MediaItem.fromUri(uri)
        val urlLower = url.lowercase()

        return when {
            urlLower.contains(".m3u8") -> {
                HlsMediaSource.Factory(httpDataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(mediaItem)
            }
            else -> {
                ProgressiveMediaSource.Factory(httpDataSourceFactory)
                    .createMediaSource(mediaItem)
            }
        }
    }

    // --- Full screen on second click ---
    private fun goFullScreen(channel: Channel) {
        val intent = Intent(this, IPTVPlayerActivity::class.java).apply {
            putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, channel.logoUrl)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_ID, channel.id)
        }
        startActivity(intent)
    }

    // --- Key handling for remote ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // If a channel in the list is focused, it's handled by the adapter click
                // If the player area is focused, go fullscreen
                currentChannel?.let { goFullScreen(it) }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                // If focus is on the player, tune to previous channel within current view
                if (binding.miniPlayer.hasFocus()) {
                    val idx = displayedChannels.indexOf(currentChannel)
                    if (idx > 0) tuneToChannel(displayedChannels[idx - 1])
                    true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (binding.miniPlayer.hasFocus()) {
                    val idx = displayedChannels.indexOf(currentChannel)
                    if (idx < displayedChannels.size - 1) tuneToChannel(displayedChannels[idx + 1])
                    true
                } else super.onKeyDown(keyCode, event)
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun showLoading(show: Boolean) {
        binding.livetvLoading.visibility = if (show) View.VISIBLE else View.GONE
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
}

// --- Channel List Adapter ---

class LiveChannelAdapter(
    private val channels: List<Channel>,
    private val epgData: EpgData?,
    private val showEpg: Boolean,
    var currentChannel: Channel?,
    private val favoritesManager: com.vistacore.launcher.data.FavoritesManager,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<LiveChannelAdapter.VH>() {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val number: TextView = itemView.findViewById(R.id.ch_number)
        val logo: ImageView = itemView.findViewById(R.id.ch_logo)
        val name: TextView = itemView.findViewById(R.id.ch_name)
        val nowPlaying: TextView = itemView.findViewById(R.id.ch_now_playing)
        val favIcon: ImageView = itemView.findViewById(R.id.ch_fav_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_livetv_channel, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val channel = channels[position]
        holder.number.text = channel.number.toString()
        holder.name.text = channel.name

        // Highlight current channel
        val isCurrent = channel.id == currentChannel?.id
        holder.number.setTextColor(holder.itemView.context.getColor(
            if (isCurrent) R.color.accent_gold else R.color.text_hint
        ))
        holder.name.setTextColor(holder.itemView.context.getColor(
            if (isCurrent) R.color.accent_gold else R.color.text_primary
        ))

        // Logo
        if (channel.logoUrl.isNotBlank()) {
            Glide.with(holder.itemView.context)
                .load(channel.logoUrl)
                .placeholder(R.drawable.ic_iptv)
                .into(holder.logo)
        } else {
            holder.logo.setImageResource(R.drawable.ic_iptv)
        }

        // EPG now playing
        if (showEpg && epgData != null) {
            val now = epgData.getNowPlaying(channel.epgId.ifBlank { channel.id })
                ?: epgData.getNowPlaying(channel.name)
            if (now != null) {
                holder.nowPlaying.text = now.title
                holder.nowPlaying.visibility = View.VISIBLE
            } else {
                holder.nowPlaying.visibility = View.GONE
            }
        } else {
            holder.nowPlaying.visibility = View.GONE
        }

        // Show favorite indicator
        val isFav = favoritesManager.isFavoriteChannel(channel.id)
        if (isFav) {
            holder.favIcon.setImageResource(R.drawable.ic_favorite)
            holder.favIcon.visibility = View.VISIBLE
        } else {
            holder.favIcon.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(channel) }

        // Long press to toggle favorite
        holder.itemView.setOnLongClickListener {
            val nowFav = favoritesManager.toggleFavoriteChannel(channel.id)
            if (nowFav) {
                holder.favIcon.setImageResource(R.drawable.ic_favorite)
                holder.favIcon.visibility = View.VISIBLE
                android.widget.Toast.makeText(holder.itemView.context, "${channel.name} added to favorites", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                holder.favIcon.visibility = View.GONE
                android.widget.Toast.makeText(holder.itemView.context, "${channel.name} removed from favorites", android.widget.Toast.LENGTH_SHORT).show()
            }
            true
        }

        holder.itemView.setOnFocusChangeListener { v, f ->
            MainActivity.animateFocus(v, f)
        }
        // Allow D-pad right from channel items to reach the Go to Channel button
        holder.itemView.nextFocusRightId = R.id.btn_number_pad
    }

    override fun getItemCount() = channels.size
}

// --- Category Chip Adapter ---

class CategoryChipAdapter(
    private val categories: List<String>,
    private val selected: String,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<CategoryChipAdapter.VH>() {

    inner class VH(val label: TextView) : RecyclerView.ViewHolder(label)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_chip, parent, false) as TextView
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cat = categories[position]
        holder.label.text = cat
        holder.label.isSelected = cat == selected
        holder.label.setTextColor(holder.label.context.getColor(
            if (cat == selected) R.color.accent_gold else R.color.text_primary
        ))
        holder.label.setOnClickListener { onClick(cat) }
        holder.label.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    override fun getItemCount() = categories.size
}
