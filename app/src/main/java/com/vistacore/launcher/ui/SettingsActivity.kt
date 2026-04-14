package com.vistacore.launcher.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vistacore.launcher.R
import com.vistacore.launcher.data.ContentCache
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.data.UsageTracker
import com.vistacore.launcher.databinding.ActivitySettingsBinding
import com.vistacore.launcher.iptv.CatchUpManager
import com.vistacore.launcher.iptv.XtreamAuth
import com.vistacore.launcher.iptv.XtreamClient
import com.vistacore.launcher.system.AppUpdateManager
import com.vistacore.launcher.system.AppUpdateWorker
import com.vistacore.launcher.system.ChannelUpdateWorker
import kotlinx.coroutines.*

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)

        loadSavedSettings()
        setupCollapsibleSections()
        setupHomeScreenApps()
        setupSourceToggle()
        setupButtons()
        setupScreenSaver()
        setupAutoLaunch()
        setupHomeCapture()
        setupEpgToggle()
        setupLiveTvStylePicker()
        setupContentLoading()
        setupContentFiltering()
        setupHiddenCategories()
        setupAutoUpdate()
        setupCatchUpType()
        setupSportsPreferences()
        setupUiScale()
        setupLanguage()
        setupPinLock()
        setupAppUpdate()
    }

    private fun setupCollapsibleSections() {
        fun toggle(header: android.widget.TextView, body: View) {
            header.setOnClickListener {
                val visible = body.visibility == View.VISIBLE
                body.visibility = if (visible) View.GONE else View.VISIBLE
                val arrow = if (visible) "▸" else "▾"
                val label = header.text.toString().substring(3) // strip old arrow + spaces
                header.text = "$arrow  $label"
                // Auto-focus the first focusable child when expanding
                if (!visible) {
                    body.post {
                        val first = body.findFocus() ?: findFirstFocusable(body)
                        first?.requestFocus()
                    }
                }
            }
            header.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
        }

        toggle(binding.headerApps, binding.bodyApps)
        toggle(binding.headerIptv, binding.bodyIptv)
        toggle(binding.headerEpg, binding.bodyEpg)
        toggle(binding.headerContent, binding.bodyContent)
        toggle(binding.headerFiltering, binding.bodyFiltering)
        toggle(binding.headerDisplay, binding.bodyDisplay)
        toggle(binding.headerAdvanced, binding.bodyAdvanced)
        toggle(binding.headerSports, binding.bodySports)
    }

    private fun loadSavedSettings() {
        when (prefs.sourceType) {
            PrefsManager.SOURCE_M3U -> binding.radioM3u.isChecked = true
            PrefsManager.SOURCE_XTREAM -> binding.radioXtream.isChecked = true
        }

        binding.inputM3uUrl.setText(prefs.m3uUrl)
        binding.inputXtreamServer.setText(prefs.xtreamServer)
        binding.inputXtreamUsername.setText(prefs.xtreamUsername)
        binding.inputXtreamPassword.setText(prefs.xtreamPassword)
        binding.inputDispatcharrApiKey.setText(prefs.dispatcharrApiKey)
        binding.inputJellyfinServer.setText(prefs.jellyfinServer)
        binding.inputJellyfinUsername.setText(prefs.jellyfinUsername)
        binding.inputJellyfinPassword.setText(prefs.jellyfinPassword)
        binding.inputEpgUrl.setText(prefs.epgUrl)

        updateSectionVisibility()
    }

    private fun setupSourceToggle() {
        binding.sourceTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radio_m3u -> prefs.sourceType = PrefsManager.SOURCE_M3U
                R.id.radio_xtream -> prefs.sourceType = PrefsManager.SOURCE_XTREAM
            }
            updateSectionVisibility()
        }
    }

    private fun updateSectionVisibility() {
        val isM3u = prefs.sourceType == PrefsManager.SOURCE_M3U
        binding.m3uSection.visibility = if (isM3u) View.VISIBLE else View.GONE
        binding.xtreamSection.visibility = if (isM3u) View.GONE else View.VISIBLE
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnWallpaper.setOnClickListener {
            startActivity(Intent(this, WallpaperPickerActivity::class.java))
        }
        binding.btnOpenGuide.setOnClickListener {
            startActivity(Intent(this, EpgGuideActivity::class.java))
        }
        binding.btnRemoteHelp.setOnClickListener {
            startActivity(Intent(this, RemoteHelpActivity::class.java))
        }

        listOf(binding.btnSave, binding.btnTest, binding.btnWallpaper, binding.btnOpenGuide, binding.btnRemoteHelp).forEach { btn ->
            btn.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
        }

        // Auto-save EPG URL when focus leaves
        binding.inputEpgUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                prefs.epgUrl = binding.inputEpgUrl.text.toString().trim()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh home capture status when returning from accessibility settings
        if (::binding.isInitialized) updateHomeCaptureUI()
    }

    override fun onPause() {
        super.onPause()
        // Auto-save text fields silently (no toast, no cache clear)
        when (prefs.sourceType) {
            PrefsManager.SOURCE_M3U -> prefs.m3uUrl = binding.inputM3uUrl.text.toString().trim()
            PrefsManager.SOURCE_XTREAM -> {
                prefs.xtreamServer = binding.inputXtreamServer.text.toString().trim()
                prefs.xtreamUsername = binding.inputXtreamUsername.text.toString().trim()
                prefs.xtreamPassword = binding.inputXtreamPassword.text.toString().trim()
                prefs.dispatcharrApiKey = binding.inputDispatcharrApiKey.text.toString().trim()
            }
        }
        prefs.jellyfinServer = binding.inputJellyfinServer.text.toString().trim()
        prefs.jellyfinUsername = binding.inputJellyfinUsername.text.toString().trim()
        prefs.jellyfinPassword = binding.inputJellyfinPassword.text.toString().trim()
        prefs.epgUrl = binding.inputEpgUrl.text.toString().trim()
    }

    private fun setupScreenSaver() {
        val timeout = prefs.screenSaverTimeout
        binding.screensaverSlider.progress = timeout
        updateScreenSaverLabel(timeout)

        binding.screensaverSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateScreenSaverLabel(progress)
                if (fromUser) prefs.screenSaverTimeout = progress
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateScreenSaverLabel(minutes: Int) {
        binding.screensaverLabel.text = if (minutes == 0) "Off" else "$minutes min"
    }

    private fun setupEpgToggle() {
        binding.switchEpgInList.isChecked = prefs.showEpgInChannelList
        binding.switchEpgInList.setOnCheckedChangeListener { _, isChecked ->
            prefs.showEpgInChannelList = isChecked
        }
    }

    private fun setupLiveTvStylePicker() {
        val styles = listOf(
            PrefsManager.LIVE_TV_CLASSIC to "Classic — sidebar + mini player",
            PrefsManager.LIVE_TV_GRID to "Channel Grid — logo tiles",
            PrefsManager.LIVE_TV_EPG to "EPG-First — guide-centric",
            PrefsManager.LIVE_TV_IMMERSIVE to "Immersive — fullscreen preview",
            PrefsManager.LIVE_TV_CAROUSEL to "Now Watching Carousel — Netflix rows",
            PrefsManager.LIVE_TV_SPLIT_HERO to "Split Hero — big preview + ribbon"
        )

        fun labelFor(key: String) = styles.firstOrNull { it.first == key }?.second ?: "Classic"
        binding.btnLiveTvStyle.text = labelFor(prefs.liveTvStyle)

        binding.btnLiveTvStyle.setOnClickListener {
            val names = styles.map { it.second }.toTypedArray()
            val current = styles.indexOfFirst { it.first == prefs.liveTvStyle }.coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Live TV Layout Style")
                .setSingleChoiceItems(names, current) { dialog, which ->
                    val picked = styles[which].first
                    prefs.liveTvStyle = picked
                    binding.btnLiveTvStyle.text = labelFor(picked)
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun setupContentLoading() {
        binding.switchLoadMovies.isChecked = prefs.loadMoviesEnabled
        binding.switchLoadShows.isChecked = prefs.loadShowsEnabled
        binding.switchLoadKids.isChecked = prefs.loadKidsEnabled

        binding.switchLoadMovies.setOnCheckedChangeListener { _, checked -> prefs.loadMoviesEnabled = checked }
        binding.switchLoadShows.setOnCheckedChangeListener { _, checked -> prefs.loadShowsEnabled = checked }
        binding.switchLoadKids.setOnCheckedChangeListener { _, checked -> prefs.loadKidsEnabled = checked }

        // OpenSubtitles API key
        binding.inputOpensubtitlesKey.setText(prefs.openSubtitlesApiKey)
        binding.inputOpensubtitlesKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                prefs.openSubtitlesApiKey = binding.inputOpensubtitlesKey.text.toString().trim()
            }
        }
    }

    private fun setupContentFiltering() {
        val filterManager = com.vistacore.launcher.data.ContentFilterManager(this)

        binding.switchFilterEnabled.isChecked = prefs.contentFilterEnabled
        binding.switchFilterProfanity.isChecked = prefs.filterProfanity
        binding.switchFilterBlasphemy.isChecked = prefs.filterBlasphemy
        binding.switchFilterSlurs.isChecked = prefs.filterSlurs
        binding.switchFilterSexual.isChecked = prefs.filterSexualDialogue

        binding.switchFilterEnabled.setOnCheckedChangeListener { _, checked -> prefs.contentFilterEnabled = checked }
        binding.switchFilterProfanity.setOnCheckedChangeListener { _, checked -> prefs.filterProfanity = checked }
        binding.switchFilterBlasphemy.setOnCheckedChangeListener { _, checked -> prefs.filterBlasphemy = checked }
        binding.switchFilterSlurs.setOnCheckedChangeListener { _, checked -> prefs.filterSlurs = checked }
        binding.switchFilterSexual.setOnCheckedChangeListener { _, checked -> prefs.filterSexualDialogue = checked }

        // Show filter file count
        val filters = filterManager.listFilters()
        binding.filterDataSummary.text = if (filters.isEmpty()) {
            "No filter files loaded"
        } else {
            "${filters.size} filter file(s) available"
        }

        binding.btnManageFilters.setOnClickListener {
            showFilterManageDialog(filterManager)
        }
        binding.btnManageFilters.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }

        // Server settings
        binding.inputFilterServerUrl.setText(prefs.filterServerUrl)
        binding.inputFilterServerKey.setText(prefs.filterServerApiKey)

        binding.inputFilterServerUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) prefs.filterServerUrl = binding.inputFilterServerUrl.text.toString().trim()
        }
        binding.inputFilterServerKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) prefs.filterServerApiKey = binding.inputFilterServerKey.text.toString().trim()
        }

        binding.btnTestFilterServer.setOnClickListener {
            val url = binding.inputFilterServerUrl.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "Enter a server URL first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.filterServerUrl = url
            prefs.filterServerApiKey = binding.inputFilterServerKey.text.toString().trim()

            scope.launch {
                val client = com.vistacore.launcher.data.FilterServerClient(url, prefs.filterServerApiKey)
                val ok = client.isAvailable()
                Toast.makeText(
                    this@SettingsActivity,
                    if (ok) "Connected to VistaFilter server!" else "Could not reach server",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        binding.btnTestFilterServer.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    private fun showFilterManageDialog(filterManager: com.vistacore.launcher.data.ContentFilterManager) {
        val filters = filterManager.listFilters()
        if (filters.isEmpty()) {
            Toast.makeText(this, "No filter files available. Filter files will appear here after processing content.", Toast.LENGTH_LONG).show()
            return
        }

        val names = filters.map { "${it.title}${if (it.year.isNotBlank()) " (${it.year})" else ""} — ${it.segments.size} segments [${it.source}]" }.toTypedArray()
        val checked = BooleanArray(filters.size) // none selected initially

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Filter Files")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Close", null)
            .setNegativeButton("Delete Selected") { _, _ ->
                filters.forEachIndexed { idx, filter ->
                    if (checked[idx]) filterManager.deleteFilter(filter.title, filter.year)
                }
                val remaining = filterManager.listFilters()
                binding.filterDataSummary.text = if (remaining.isEmpty()) {
                    "No filter files loaded"
                } else {
                    "${remaining.size} filter file(s) available"
                }
                Toast.makeText(this, "Deleted selected filter files", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun setupHiddenCategories() {
        val tracker = UsageTracker(this)

        updateHiddenCatsSummary(tracker)

        binding.btnHideMovieCats.setOnClickListener {
            showCategoryHideDialog("Movie Categories", ContentCache.movieItems, tracker)
        }
        binding.btnHideShowCats.setOnClickListener {
            showCategoryHideDialog("Show Categories", ContentCache.showItems, tracker)
        }

        binding.btnHideMovieCats.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
        binding.btnHideShowCats.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    private fun updateHiddenCatsSummary(tracker: UsageTracker) {
        val hidden = tracker.getHiddenCategories()
        binding.hiddenCatsSummary.text = if (hidden.isEmpty()) {
            "No categories hidden"
        } else {
            "${hidden.size} hidden: ${hidden.sorted().joinToString(", ")}"
        }
    }

    private fun showCategoryHideDialog(
        title: String,
        items: List<com.vistacore.launcher.iptv.Channel>?,
        tracker: UsageTracker
    ) {
        if (items.isNullOrEmpty()) {
            Toast.makeText(this, "No content loaded yet. Open the app first to load categories.", Toast.LENGTH_LONG).show()
            return
        }

        // Get unique categories sorted by name, with item counts
        val categories = items.groupBy { it.category }
            .entries
            .sortedBy { it.key.lowercase() }
            .map { it.key to it.value.size }

        val names = categories.map { (cat, count) -> "$cat ($count)" }.toTypedArray()
        val checked = categories.map { (cat, _) -> !tracker.isCategoryHidden(cat) }.toBooleanArray()

        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_VistaCore_Dialog)
            .setTitle(title)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val cat = categories[which].first
                if (isChecked) {
                    tracker.unhideCategory(cat)
                } else {
                    tracker.hideCategory(cat)
                }
            }
            .setPositiveButton("Done") { _, _ ->
                updateHiddenCatsSummary(tracker)
                // Clear preloaded rows so they rebuild on next launch
                ContentCache.movieRows = null
                ContentCache.showRows = null
                ContentCache.kidsRows = null
            }
            .setNeutralButton("Hide All") { dialog, _ ->
                for ((cat, _) in categories) tracker.hideCategory(cat)
                updateHiddenCatsSummary(tracker)
                ContentCache.movieRows = null
                ContentCache.showRows = null
                ContentCache.kidsRows = null
                dialog.dismiss()
            }
            .show()
    }

    private fun setupAutoLaunch() {
        binding.switchAutoLaunch.isChecked = prefs.autoLaunchOnBoot
        binding.switchAutoLaunch.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoLaunchOnBoot = isChecked
        }
    }

    private fun isHomeCaptureEnabled(): Boolean {
        val am = getSystemService(android.view.accessibility.AccessibilityManager::class.java) ?: return false
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun updateHomeCaptureUI() {
        val enabled = isHomeCaptureEnabled()
        binding.btnHomeCapture.text = if (enabled) "Enabled ✓" else "Enable"
        binding.homeCaptureSummary.text = if (enabled)
            "Home button will open VistaCore"
        else
            "Redirect Home button to VistaCore (requires accessibility permission)"
    }

    private fun setupHomeCapture() {
        updateHomeCaptureUI()
        binding.btnHomeCapture.setOnClickListener {
            if (isHomeCaptureEnabled()) {
                // Already enabled — open accessibility settings to let user disable if wanted
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                // Guide user to enable it
                Toast.makeText(this, "Enable \"VistaCore\" in Accessibility settings", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        binding.btnHomeCapture.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    private fun setupAutoUpdate() {
        binding.switchAutoUpdate.isChecked = prefs.autoUpdateEnabled
        binding.switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoUpdateEnabled = isChecked
            if (isChecked) {
                ChannelUpdateWorker.schedule(this)
            } else {
                ChannelUpdateWorker.cancel(this)
            }
        }
    }

    private fun setupCatchUpType() {
        when (prefs.catchUpType) {
            CatchUpManager.CATCHUP_XTREAM -> binding.radioCatchupXtream.isChecked = true
            CatchUpManager.CATCHUP_FLUSSONIC -> binding.radioCatchupFlussonic.isChecked = true
            CatchUpManager.CATCHUP_APPEND -> binding.radioCatchupAppend.isChecked = true
            CatchUpManager.CATCHUP_SHIFT -> binding.radioCatchupShift.isChecked = true
        }

        binding.catchupTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.catchUpType = when (checkedId) {
                R.id.radio_catchup_flussonic -> CatchUpManager.CATCHUP_FLUSSONIC
                R.id.radio_catchup_append -> CatchUpManager.CATCHUP_APPEND
                R.id.radio_catchup_shift -> CatchUpManager.CATCHUP_SHIFT
                else -> CatchUpManager.CATCHUP_XTREAM
            }
        }
    }

    private fun saveSettings() {
        when (prefs.sourceType) {
            PrefsManager.SOURCE_M3U -> prefs.m3uUrl = binding.inputM3uUrl.text.toString().trim()
            PrefsManager.SOURCE_XTREAM -> {
                prefs.xtreamServer = binding.inputXtreamServer.text.toString().trim()
                prefs.xtreamUsername = binding.inputXtreamUsername.text.toString().trim()
                prefs.xtreamPassword = binding.inputXtreamPassword.text.toString().trim()
                prefs.dispatcharrApiKey = binding.inputDispatcharrApiKey.text.toString().trim()
            }
        }
        prefs.jellyfinServer = binding.inputJellyfinServer.text.toString().trim()
        prefs.jellyfinUsername = binding.inputJellyfinUsername.text.toString().trim()
        prefs.jellyfinPassword = binding.inputJellyfinPassword.text.toString().trim()
        prefs.epgUrl = binding.inputEpgUrl.text.toString().trim()
        showStatus(getString(R.string.settings_saved), true)
        Toast.makeText(this, getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show()
    }

    /** Only called from Test Connection — clears caches when URL confirmed changed */
    private fun clearCachesAndRefresh() {
        java.io.File(filesDir, "channels_cache.json").delete()
        java.io.File(filesDir, "channels_cache.json.gz").delete()
        java.io.File(filesDir, "movies_cache.json").delete()
        java.io.File(filesDir, "movies_cache.json.gz").delete()
        java.io.File(filesDir, "series_cache.json").delete()
        java.io.File(filesDir, "series_cache.json.gz").delete()
        java.io.File(filesDir, "show_names.bin").delete()
        com.vistacore.launcher.data.ContentCache.clear()
        ChannelUpdateWorker.refreshNow(this)
    }

    private fun testConnection() {
        saveSettings()
        scope.launch {
            try {
                when (prefs.sourceType) {
                    PrefsManager.SOURCE_M3U -> {
                        val url = prefs.m3uUrl
                        if (url.isBlank()) {
                            showStatus("Please enter a playlist URL", false)
                            Toast.makeText(this@SettingsActivity, "Please enter a playlist URL", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val channels = withContext(Dispatchers.IO) {
                            com.vistacore.launcher.iptv.M3UParser().parse(url)
                        }
                        val msg = "Connected! Found ${channels.size} channel${if (channels.size != 1) "s" else ""}. Refreshing cache…"
                        showStatus(msg, true)
                        Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
                        clearCachesAndRefresh()
                    }
                    PrefsManager.SOURCE_XTREAM -> {
                        val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                        val client = XtreamClient(auth)
                        val response = client.authenticate()
                        if (response.user_info?.status == "Active") {
                            // Also probe VOD to surface any issues
                            val apiKey = binding.inputDispatcharrApiKey.text.toString().trim()
                            val vod = com.vistacore.launcher.iptv.DispatcharrVodClient(prefs.xtreamServer, apiKey)
                            val movieCount = try { withContext(Dispatchers.IO) { vod.probeMovieCount() } } catch (_: Exception) { -1 }
                            val seriesCount = try { withContext(Dispatchers.IO) { vod.probeSeriesCount() } } catch (_: Exception) { -1 }
                            val msg = "Connected! Movies: ${if (movieCount >= 0) movieCount else "error"}, Series: ${if (seriesCount >= 0) seriesCount else "error"}"
                            showStatus(msg, true)
                            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                            clearCachesAndRefresh()
                        } else {
                            val msg = "Account status: ${response.user_info?.status ?: "Unknown"}"
                            showStatus(msg, false)
                            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // If Jellyfin is configured, probe it too and report alongside.
                if (prefs.hasJellyfinConfig()) {
                    try {
                        val jf = com.vistacore.launcher.iptv.JellyfinClient(
                            com.vistacore.launcher.iptv.JellyfinAuth(
                                prefs.jellyfinServer, prefs.jellyfinUsername, prefs.jellyfinPassword
                            )
                        )
                        withContext(Dispatchers.IO) { jf.authenticate() }
                        val msg = "Jellyfin: connected as ${prefs.jellyfinUsername}"
                        showStatus(msg, true)
                        Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        val detail = e.message ?: "auth failed"
                        showStatus("Jellyfin failed: $detail", false)
                        Toast.makeText(this@SettingsActivity, "Jellyfin failed: $detail", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                val detail = e.cause?.message ?: e.message ?: "Unknown error"
                android.util.Log.e("Settings", "Connection test failed", e)
                showStatus("Failed: $detail", false)
                Toast.makeText(this@SettingsActivity, "Failed: $detail", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Built-in apps
    private val builtInApps = linkedMapOf(
        "IPTV" to "Live TV", "ESPN" to "ESPN", "ROKU" to "Roku",
        "DISNEY_PLUS" to "Disney+", "MOVIES" to "Movies", "TV_SHOWS" to "TV Shows",
        "KIDS" to "Kids"
    )

    private val appItems = mutableListOf<AppReorderItem>()

    private fun setupHomeScreenApps() {
        appItems.clear()
        val enabledApps = prefs.enabledApps
        val addedIds = mutableSetOf<String>()

        // Add enabled apps in saved order
        for (id in enabledApps) {
            if (id in builtInApps || id.startsWith("custom:")) {
                val label = builtInApps[id] ?: getCustomAppLabel(id)
                appItems.add(AppReorderItem(id, label, true))
                addedIds.add(id)
            }
        }
        // Add built-in apps that weren't in saved list
        for ((id, label) in builtInApps) {
            if (id !in addedIds) {
                appItems.add(AppReorderItem(id, label, false))
                addedIds.add(id)
            }
        }
        // Add custom apps
        for (id in getCustomAppIds()) {
            if (id !in addedIds) {
                appItems.add(AppReorderItem(id, getCustomAppLabel(id), false))
            }
        }

        rebuildAppList()

        binding.btnAddApp.setOnClickListener { showAddAppDialog() }
        binding.btnAddApp.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    private fun rebuildAppList() {
        val container = binding.appsContainer
        container.removeAllViews()

        for (i in appItems.indices) {
            val item = appItems[i]
            val row = layoutInflater.inflate(R.layout.item_app_reorder, container, false)

            val checkbox = row.findViewById<CheckBox>(R.id.cb_app_toggle)
            val btnUp = row.findViewById<ImageButton>(R.id.btn_move_up)
            val btnDown = row.findViewById<ImageButton>(R.id.btn_move_down)

            checkbox.text = item.label
            checkbox.isChecked = item.enabled
            checkbox.setOnCheckedChangeListener { _, checked ->
                item.enabled = checked
                saveAppOrder()
            }

            btnUp.setOnClickListener {
                val pos = appItems.indexOf(item)
                if (pos > 0) {
                    appItems.removeAt(pos)
                    appItems.add(pos - 1, item)
                    saveAppOrder()
                    rebuildAppList()
                }
            }

            btnDown.setOnClickListener {
                val pos = appItems.indexOf(item)
                if (pos < appItems.size - 1) {
                    appItems.removeAt(pos)
                    appItems.add(pos + 1, item)
                    saveAppOrder()
                    rebuildAppList()
                }
            }

            btnUp.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
            btnDown.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }

            container.addView(row)
        }
    }

    private fun saveAppOrder() {
        val enabled = appItems.filter { it.enabled }.map { it.id }
        prefs.enabledApps = enabled
    }

    private fun getCustomAppIds(): List<String> {
        val raw = getSharedPreferences("vistacore_custom_apps", MODE_PRIVATE)
            .getString("custom_app_ids", null) ?: return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    private fun getCustomAppLabel(id: String): String {
        if (!id.startsWith("custom:")) return id
        val pkg = id.removePrefix("custom:")
        return try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg.split(".").lastOrNull() ?: pkg
        }
    }

    private fun saveCustomApp(packageName: String) {
        val customPrefs = getSharedPreferences("vistacore_custom_apps", MODE_PRIVATE)
        val existing = customPrefs.getString("custom_app_ids", "") ?: ""
        val ids = existing.split(",").filter { it.isNotBlank() }.toMutableList()
        val id = "custom:$packageName"
        if (id !in ids) {
            ids.add(id)
            customPrefs.edit().putString("custom_app_ids", ids.joinToString(",")).apply()
        }
        // Add to enabled apps
        val enabled = prefs.enabledApps.toMutableList()
        if (id !in enabled) {
            enabled.add(id)
            prefs.enabledApps = enabled
        }
        // Refresh the list
        setupHomeScreenApps()
        Toast.makeText(this, "App added!", Toast.LENGTH_SHORT).show()
    }

    private fun removeCustomApp(id: String) {
        if (!id.startsWith("custom:")) return
        val customPrefs = getSharedPreferences("vistacore_custom_apps", MODE_PRIVATE)
        val existing = customPrefs.getString("custom_app_ids", "") ?: ""
        val ids = existing.split(",").filter { it.isNotBlank() && it != id }
        customPrefs.edit().putString("custom_app_ids", ids.joinToString(",")).apply()
        // Remove from enabled apps
        prefs.enabledApps = prefs.enabledApps.filter { it != id }
        setupHomeScreenApps()
    }

    private fun showAddAppDialog() {
        // Get all installed apps with Leanback or regular launcher intents
        val pm = packageManager
        val apps = mutableListOf<Pair<String, String>>() // package name, label

        // Leanback apps
        val leanbackIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        leanbackIntent.addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)
        for (info in pm.queryIntentActivities(leanbackIntent, 0)) {
            val pkg = info.activityInfo.packageName
            if (pkg != packageName) { // Exclude ourselves
                val label = info.loadLabel(pm).toString()
                apps.add(Pair(pkg, label))
            }
        }

        // Regular launcher apps
        val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        launcherIntent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        for (info in pm.queryIntentActivities(launcherIntent, 0)) {
            val pkg = info.activityInfo.packageName
            if (pkg != packageName && apps.none { it.first == pkg }) {
                val label = info.loadLabel(pm).toString()
                apps.add(Pair(pkg, label))
            }
        }

        apps.sortBy { it.second }

        val labels = apps.map { it.second }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add App to Home Screen")
            .setItems(labels) { _, which ->
                val (pkg, _) = apps[which]
                saveCustomApp(pkg)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupSportsPreferences() {
        val current = prefs.sportsTypes

        binding.cbBasketball.isChecked = "basketball" in current
        binding.cbFootball.isChecked = "football" in current
        binding.cbBaseball.isChecked = "baseball" in current
        binding.cbHockey.isChecked = "hockey" in current
        binding.cbSoccer.isChecked = "soccer" in current

        val listener = CompoundButton.OnCheckedChangeListener { _, _ ->
            val selected = mutableSetOf<String>()
            if (binding.cbBasketball.isChecked) selected.add("basketball")
            if (binding.cbFootball.isChecked) selected.add("football")
            if (binding.cbBaseball.isChecked) selected.add("baseball")
            if (binding.cbHockey.isChecked) selected.add("hockey")
            if (binding.cbSoccer.isChecked) selected.add("soccer")
            prefs.sportsTypes = selected
        }

        binding.cbBasketball.setOnCheckedChangeListener(listener)
        binding.cbFootball.setOnCheckedChangeListener(listener)
        binding.cbBaseball.setOnCheckedChangeListener(listener)
        binding.cbHockey.setOnCheckedChangeListener(listener)
        binding.cbSoccer.setOnCheckedChangeListener(listener)
    }

    private fun setupUiScale() {
        when (prefs.uiScale) {
            0 -> binding.radioScaleSmall.isChecked = true
            2 -> binding.radioScaleLarge.isChecked = true
            else -> binding.radioScaleMedium.isChecked = true
        }
        binding.uiScaleGroup.setOnCheckedChangeListener { _, checkedId ->
            val newScale = when (checkedId) {
                R.id.radio_scale_small -> 0
                R.id.radio_scale_large -> 2
                else -> 1
            }
            if (newScale != prefs.uiScale) {
                prefs.uiScale = newScale
                recreate()
            }
        }
    }

    private fun setupLanguage() {
        when (prefs.appLanguage) {
            "es" -> binding.radioLangEs.isChecked = true
            "fr" -> binding.radioLangFr.isChecked = true
            "pt" -> binding.radioLangPt.isChecked = true
            "de" -> binding.radioLangDe.isChecked = true
            else -> binding.radioLangEn.isChecked = true
        }
        binding.languageGroup.setOnCheckedChangeListener { _, checkedId ->
            val newLang = when (checkedId) {
                R.id.radio_lang_es -> "es"
                R.id.radio_lang_fr -> "fr"
                R.id.radio_lang_pt -> "pt"
                R.id.radio_lang_de -> "de"
                else -> "en"
            }
            if (newLang != prefs.appLanguage) {
                prefs.appLanguage = newLang
                // Cascade: audio, subtitles, and content all follow the app language
                prefs.preferredAudioLanguage = if (newLang == "en") "" else newLang
                prefs.preferredSubtitleLanguage = if (newLang == "en") "" else newLang
                recreate()
            }
        }
    }

    private fun setupPinLock() {
        binding.switchPinEnabled.isChecked = prefs.pinEnabled
        updatePinUI()

        binding.switchPinEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && prefs.settingsPin.isBlank()) {
                // Must set a PIN first
                PinDialogHelper.showSetPinDialog(this) { newPin ->
                    prefs.settingsPin = newPin
                    prefs.pinEnabled = true
                    updatePinUI()
                }
                // Revert switch until PIN is actually set
                binding.switchPinEnabled.isChecked = prefs.pinEnabled
            } else {
                prefs.pinEnabled = isChecked
                updatePinUI()
            }
        }

        binding.btnChangePin.setOnClickListener {
            PinDialogHelper.showSetPinDialog(this) { newPin ->
                prefs.settingsPin = newPin
                Toast.makeText(this, "PIN updated", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnChangePin.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    private fun updatePinUI() {
        val enabled = prefs.pinEnabled && prefs.settingsPin.isNotBlank()
        binding.btnChangePin.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.pinStatusText.text = if (enabled) "PIN is set" else "Off"
    }

    private var cachedUpdateInfo: AppUpdateManager.UpdateInfo? = null

    private fun setupAppUpdate() {
        val updateManager = AppUpdateManager(this)

        // Show current version
        binding.appVersionText.text = "Current version: ${updateManager.getCurrentVersionName()}"

        // Auto-update toggle
        binding.switchAppAutoUpdate.isChecked = prefs.appAutoUpdateEnabled
        binding.switchAppAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            prefs.appAutoUpdateEnabled = isChecked
            if (isChecked) {
                AppUpdateWorker.schedule(this)
            } else {
                AppUpdateWorker.cancel(this)
            }
        }

        // GitHub repo field
        binding.inputUpdateUrl.setText(prefs.appUpdateRepo)
        binding.inputUpdateUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                prefs.appUpdateRepo = binding.inputUpdateUrl.text.toString().trim()
            }
        }

        // Check if we already have a cached available update that's actually newer
        val cachedVersion = updateManager.getCachedUpdateVersion()
        val currentVersion = updateManager.getCurrentVersionName()
        if (cachedVersion != null && cachedVersion != currentVersion.replace(Regex("[a-zA-Z]+$"), "")) {
            val changelog = updateManager.getCachedChangelog() ?: ""
            binding.updateStatusText.text = "Update available: v$cachedVersion" +
                if (changelog.isNotBlank()) "\n$changelog" else ""
            binding.updateStatusText.visibility = android.view.View.VISIBLE
            binding.btnInstallUpdate.visibility = android.view.View.VISIBLE
        } else if (cachedVersion != null) {
            // Stale cache — clear it
            updateManager.clearCachedUpdate()
        }

        // Check for updates button
        binding.btnCheckUpdate.setOnClickListener {
            binding.btnCheckUpdate.isEnabled = false
            binding.btnCheckUpdate.text = "Checking…"
            binding.updateStatusText.visibility = android.view.View.GONE
            binding.btnInstallUpdate.visibility = android.view.View.GONE

            scope.launch {
                val repo = binding.inputUpdateUrl.text.toString().trim().ifBlank {
                    prefs.appUpdateRepo
                }
                prefs.appUpdateRepo = repo

                val result = updateManager.checkForUpdate(repo)

                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.text = "Check for Updates"

                if (result.error != null) {
                    binding.updateStatusText.text = "Check failed: ${result.error}"
                    binding.updateStatusText.visibility = android.view.View.VISIBLE
                } else if (result.available && result.info != null) {
                    cachedUpdateInfo = result.info
                    val changelog = result.info.changelog
                    binding.updateStatusText.text = "Update available: v${result.info.versionName}" +
                        if (changelog.isNotBlank()) "\n$changelog" else ""
                    binding.updateStatusText.visibility = android.view.View.VISIBLE
                    binding.btnInstallUpdate.visibility = android.view.View.VISIBLE
                    Toast.makeText(this@SettingsActivity, "Update available!", Toast.LENGTH_SHORT).show()
                } else {
                    binding.updateStatusText.text = "You're up to date (v${result.currentVersionName})"
                    binding.updateStatusText.visibility = android.view.View.VISIBLE
                    Toast.makeText(this@SettingsActivity, "App is up to date", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Install update button
        binding.btnInstallUpdate.setOnClickListener {
            val info = cachedUpdateInfo
            if (info != null) {
                binding.btnInstallUpdate.isEnabled = false
                binding.btnInstallUpdate.text = "Downloading…"
                Toast.makeText(this, "Downloading update…", Toast.LENGTH_SHORT).show()
                updateManager.downloadAndInstall(info.apkUrl)
            } else {
                // Fallback: re-check and download
                binding.btnInstallUpdate.isEnabled = false
                scope.launch {
                    val result = updateManager.checkForUpdate(prefs.appUpdateRepo)
                    if (result.available && result.info != null) {
                        updateManager.downloadAndInstall(result.info.apkUrl)
                    } else {
                        binding.btnInstallUpdate.isEnabled = true
                        binding.btnInstallUpdate.text = "Download & Install Update"
                        Toast.makeText(this@SettingsActivity, "No update available", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnCheckUpdate.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
        binding.btnInstallUpdate.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    private fun showStatus(message: String, success: Boolean) {
        binding.statusMessage.text = message
        binding.statusMessage.setTextColor(getColor(if (success) R.color.status_online else R.color.status_offline))
        binding.statusMessage.visibility = View.VISIBLE
    }

    private fun findFirstFocusable(view: View): View? {
        if (view.isFocusable) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findFirstFocusable(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

// --- App Reorder Item ---

data class AppReorderItem(
    val id: String,
    val label: String,
    var enabled: Boolean
)
