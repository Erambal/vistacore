package com.vistacore.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vistacore.launcher.R
import com.vistacore.launcher.data.FavoritesManager
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.databinding.ActivityIptvBrowserBinding
import com.vistacore.launcher.databinding.ItemCategoryBinding
import com.vistacore.launcher.databinding.ItemChannelBinding
import com.vistacore.launcher.iptv.*
import com.vistacore.launcher.system.ChannelUpdateWorker
import kotlinx.coroutines.*

class IPTVBrowserActivity : BaseActivity() {

    private lateinit var binding: ActivityIptvBrowserBinding
    private lateinit var prefs: PrefsManager
    private lateinit var favoritesManager: FavoritesManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var allChannels: List<Channel> = emptyList()
    private var displayedChannels: List<Channel> = emptyList()
    private var categories: List<Category> = emptyList()
    private var currentCategory: String? = null
    private var epgData: EpgData? = null
    private var currentSearch: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIptvBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)
        favoritesManager = FavoritesManager(this)

        binding.categoriesList.layoutManager = LinearLayoutManager(this)
        binding.channelsList.layoutManager = LinearLayoutManager(this)

        setupSearch()

        if (!prefs.hasIptvConfig()) {
            showEmptyState()
            return
        }

        loadChannels()
    }

    private fun setupSearch() {
        binding.channelSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSearch = s?.toString()?.trim() ?: ""
                applySearchFilter()
            }
        })
    }

    private fun applySearchFilter() {
        if (currentSearch.isBlank()) {
            // No search — show the current category view
            showChannels(displayedChannels)
            return
        }

        // Try as channel number first
        val asNumber = currentSearch.toIntOrNull()
        val filtered = if (asNumber != null) {
            // Search by channel number — show exact + prefix matches
            allChannels.filter {
                it.number == asNumber || it.number.toString().startsWith(currentSearch)
            }
        } else {
            // Text search by name
            allChannels.filter {
                it.name.contains(currentSearch, ignoreCase = true) ||
                it.category.contains(currentSearch, ignoreCase = true)
            }
        }

        showChannels(filtered)
    }

    private fun loadChannels() {
        showLoading(true)

        scope.launch {
            // Read cache on background thread
            val cached = withContext(Dispatchers.IO) {
                ChannelUpdateWorker.getCachedChannels(this@IPTVBrowserActivity)
            }

            if (cached != null && cached.isNotEmpty()) {
                allChannels = cached
                populateUI()
                showLoading(false)
                // Only refresh if cache is stale (> 1 hour)
                val age = System.currentTimeMillis() - ChannelUpdateWorker.getLastUpdateTime(this@IPTVBrowserActivity)
                if (age > 3600000L) {
                    refreshChannelsInBackground()
                }
                return@launch
            }

            // No cache — fetch from network
            try {
                allChannels = fetchChannelsFromNetwork()
                withContext(Dispatchers.IO) {
                    ChannelUpdateWorker.cacheChannels(this@IPTVBrowserActivity, allChannels)
                }
                populateUI()
                showLoading(false)
            } catch (e: Exception) {
                showLoading(false)
                Toast.makeText(this@IPTVBrowserActivity, getString(R.string.iptv_error), Toast.LENGTH_LONG).show()
                showEmptyState()
            }
        }
    }

    private suspend fun fetchChannelsFromNetwork(): List<Channel> = withContext(Dispatchers.IO) {
        when (prefs.sourceType) {
            PrefsManager.SOURCE_M3U -> M3UParser().parse(prefs.m3uUrl)
            PrefsManager.SOURCE_XTREAM -> {
                val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                XtreamClient(auth).getChannels()
            }
            else -> emptyList()
        }
    }

    private suspend fun loadEpg() {
        val epgUrl = prefs.epgUrl
        if (epgUrl.isNotBlank()) {
            try {
                epgData = withContext(Dispatchers.IO) { EpgParser().parse(epgUrl) }
            } catch (_: Exception) { }
        }
    }

    private fun populateUI() {
        // Filter to live channels only
        allChannels = allChannels.filter { it.contentType == ContentType.LIVE }

        if (allChannels.isEmpty()) {
            showEmptyState()
            return
        }

        val grouped = allChannels.groupBy { it.category }
        val favCount = favoritesManager.filterFavorites(allChannels).size
        val dynamicCategories = mutableListOf<Category>()

        if (favCount > 0) {
            dynamicCategories.add(Category("★ Favorites", favCount))
        }
        dynamicCategories.add(Category("All Channels", allChannels.size))
        dynamicCategories.addAll(
            grouped.map { (name, channels) ->
                Category(name, channels.size)
            }.sortedBy { it.name }
        )

        categories = dynamicCategories
        binding.categoriesList.adapter = CategoryAdapter(categories) { onCategorySelected(it) }

        displayedChannels = allChannels
        showChannels(allChannels)
        showLoading(false)

        // Load EPG in background
        scope.launch { loadEpg() }
    }

    private fun refreshChannelsInBackground() {
        // Trigger a WorkManager refresh instead of downloading 80K+ entries in the activity
        ChannelUpdateWorker.refreshNow(this)
    }

    private fun onCategorySelected(category: Category) {
        currentCategory = category.name
        displayedChannels = when (category.name) {
            "★ Favorites" -> favoritesManager.filterFavorites(allChannels)
            "All Channels" -> allChannels
            else -> allChannels.filter { it.category == category.name }
        }

        // Clear search when switching categories
        if (currentSearch.isNotBlank()) {
            binding.channelSearchInput.setText("")
        }
        showChannels(displayedChannels)
    }

    private fun showChannels(channels: List<Channel>) {
        binding.channelCount.text = "${channels.size} channel${if (channels.size != 1) "s" else ""}"

        binding.channelsList.adapter = ChannelAdapter(
            channels = channels,
            epgData = epgData,
            favoritesManager = favoritesManager,
            onClick = { channel -> launchPlayer(channel) }
        )
        binding.emptyState.visibility = View.GONE
        binding.channelsList.visibility = View.VISIBLE
    }

    private fun launchPlayer(channel: Channel) {
        prefs.lastChannel = channel.id
        val intent = Intent(this, IPTVPlayerActivity::class.java).apply {
            putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, channel.logoUrl)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_ID, channel.id)
        }
        startActivity(intent)
    }

    private fun showLoading(show: Boolean) {
        binding.loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showEmptyState() {
        showLoading(false)
        binding.channelsList.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

// --- Category Adapter ---

class CategoryAdapter(
    private val categories: List<Category>,
    private val onClick: (Category) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private var selectedPos = 0

    inner class VH(private val binding: ItemCategoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(category: Category, position: Int) {
            binding.categoryName.text = "${category.name} (${category.channelCount})"
            binding.root.isSelected = position == selectedPos

            binding.root.setOnClickListener {
                val prev = selectedPos
                selectedPos = position
                notifyItemChanged(prev)
                notifyItemChanged(position)
                onClick(category)
            }

            binding.root.setOnFocusChangeListener { view, hasFocus ->
                MainActivity.animateFocus(view, hasFocus)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCategoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(categories[position], position)
    override fun getItemCount() = categories.size
}

// --- Channel Adapter (with favorites + EPG) ---

class ChannelAdapter(
    private val channels: List<Channel>,
    private val epgData: EpgData? = null,
    private val favoritesManager: FavoritesManager? = null,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    inner class VH(private val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel) {
            binding.channelName.text = channel.name
            binding.channelNumber.text = channel.number.toString()

            // Logo
            if (channel.logoUrl.isNotBlank()) {
                Glide.with(binding.root.context)
                    .load(channel.logoUrl)
                    .placeholder(R.drawable.ic_iptv)
                    .error(R.drawable.ic_iptv)
                    .into(binding.channelLogo)
            } else {
                binding.channelLogo.setImageResource(R.drawable.ic_iptv)
            }

            // EPG now playing
            val nowPlaying = epgData?.getNowPlaying(channel.id)
                ?: epgData?.getNowPlaying(channel.name)
            if (nowPlaying != null) {
                binding.channelNowPlaying.text = "▶ ${nowPlaying.title}"
                binding.channelNowPlaying.visibility = View.VISIBLE
            } else {
                binding.channelNowPlaying.visibility = View.GONE
            }

            // Favorites
            if (favoritesManager != null) {
                val isFav = favoritesManager.isFavoriteChannel(channel.id)
                binding.btnFavorite.setImageResource(
                    if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_outline
                )
                binding.btnFavorite.visibility = View.VISIBLE
                binding.btnFavorite.setOnClickListener {
                    val nowFav = favoritesManager.toggleFavoriteChannel(channel.id)
                    binding.btnFavorite.setImageResource(
                        if (nowFav) R.drawable.ic_favorite else R.drawable.ic_favorite_outline
                    )
                }
            } else {
                binding.btnFavorite.visibility = View.GONE
            }

            binding.root.setOnClickListener { onClick(channel) }
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                MainActivity.animateFocus(view, hasFocus)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemChannelBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(channels[position])
    override fun getItemCount() = channels.size
}
