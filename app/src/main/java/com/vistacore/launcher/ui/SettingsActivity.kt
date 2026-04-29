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
import android.widget.TextView
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
    // Identity snapshot captured at activity entry (and re-captured after
    // each successful commit). Both Save and onPause compare against this
    // so that a radio-only source flip — which writes prefs.sourceType
    // *immediately* via the OnCheckedChangeListener, before any save — is
    // still recognised as a config change that warrants a cache wipe.
    private var entrySourceIdentity: String = ""

    // Nav rail items and corresponding content sections
    private lateinit var navItems: List<TextView>
    private lateinit var sections: List<View>
    private var selectedNavIndex = 0
    private var pendingNavIndex = 0

    companion object {
        private const val STATE_NAV_INDEX = "selected_nav_index"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)
        entrySourceIdentity = sourceIdentity()

        // Restore the section the user was on. Needed because changing UI
        // scale or app language calls recreate(), which otherwise dumps the
        // user back on the first section ("Connections").
        pendingNavIndex = savedInstanceState?.getInt(STATE_NAV_INDEX, 0) ?: 0

        setupNavigation()
        loadSavedSettings()
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
        setupHomeScreenApps()

        // Show version in nav rail
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            binding.navVersionLabel.text = "VistaCore v$versionName"
        } catch (_: Exception) {}
    }

    // ====================== NAVIGATION ======================

    private fun setupNavigation() {
        navItems = listOf(
            binding.navConnections,
            binding.navLivetv,
            binding.navMovies,
            binding.navFiltering,
            binding.navHomescreen,
            binding.navAppearance,
            binding.navSystem
        )

        sections = listOf(
            binding.sectionConnections,
            binding.sectionLivetv,
            binding.sectionMovies,
            binding.sectionFiltering,
            binding.sectionHomescreen,
            binding.sectionAppearance,
            binding.sectionSystem
        )

        navItems.forEachIndexed { index, nav ->
            nav.setOnClickListener { selectSection(index) }
            nav.setOnFocusChangeListener { v, hasFocus ->
                // Only switch sections when the user is actually navigating
                // through the rail (D-pad up/down from another nav item). If
                // focus was auto-transferred here because a button inside a
                // section got disabled (e.g. "Check for update" → "Checking…"),
                // we leave the current section alone — otherwise the user
                // gets teleported to a random tab every time they click.
                if (hasFocus && lastFocusWasInNav) {
                    selectSection(index)
                }
                MainActivity.animateFocus(v, hasFocus)
            }
        }

        // Track whether focus previously sat on a nav item, so the listener
        // above can tell user navigation apart from spurious auto-transfers.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, _ ->
            lastFocusWasInNav = old != null && navItems.contains(old)
        }

        // Restore the previously-selected section (survives recreate() from
        // scale / language changes). Focus the matching nav item so that
        // Android doesn't auto-focus nav item 0 and flip us back.
        val initial = pendingNavIndex.coerceIn(0, navItems.lastIndex)
        selectSection(initial)
        navItems[initial].post { navItems[initial].requestFocus() }
    }

    // Set by the global focus change listener. True when focus just left
    // a nav rail item — meaning any new focus on a nav item is the user
    // arrow-navigating within the rail, not a spurious Android auto-focus.
    private var lastFocusWasInNav: Boolean = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_NAV_INDEX, selectedNavIndex)
    }

    private fun selectSection(index: Int) {
        // Nav items trigger this on focus, which means it fires again every time
        // focus returns to the rail after interacting with a control. Only do the
        // layout work (and scroll reset) when the section actually changes so the
        // user doesn't lose their scroll position.
        val sectionChanged = index != selectedNavIndex || !navItems[index].isActivated
        if (!sectionChanged) return

        selectedNavIndex = index

        sections.forEachIndexed { i, section ->
            section.visibility = if (i == index) View.VISIBLE else View.GONE
        }

        navItems.forEachIndexed { i, nav ->
            nav.isActivated = (i == index)
        }

        (sections[index] as? android.widget.ScrollView)?.scrollTo(0, 0)
    }

    // ====================== SETTINGS LOGIC ======================

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
        // Auto-save text fields silently. We persist all fields (not just
        // the currently selected source's) so toggling the radio doesn't
        // discard pending edits to the other source's inputs. We *do*
        // wipe caches when the source identity changed since entry —
        // otherwise a user can flip M3U <-> Xtream (which writes
        // prefs.sourceType immediately) and back out without ever hitting
        // Save, and MainActivity would happily reload the previous
        // provider's cache on resume. Transient onPause for a sub-activity
        // (e.g. accessibility settings) is harmless: identity hasn't
        // changed, so the helper is a no-op.
        persistConnectionFields()
        refreshIfSourceIdentityChanged()
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

        // Toggling a preload switch has to invalidate the in-memory
        // ContentCache.isReady flag, otherwise the warm-path gate in
        // Splash skips preloadContent() while the process stays alive
        // and the new toggle state appears to do nothing until something
        // else (worker tick, source change, app restart) clears the flag.
        // Invalidating drops the row caches; the next splash pass
        // rebuilds them honouring the new preferences.
        fun invalidatePreloadOnChange() {
            com.vistacore.launcher.data.ContentCache.invalidatePreload()
        }
        binding.switchLoadMovies.setOnCheckedChangeListener { _, checked ->
            prefs.loadMoviesEnabled = checked
            invalidatePreloadOnChange()
        }
        binding.switchLoadShows.setOnCheckedChangeListener { _, checked ->
            prefs.loadShowsEnabled = checked
            invalidatePreloadOnChange()
        }
        binding.switchLoadKids.setOnCheckedChangeListener { _, checked ->
            prefs.loadKidsEnabled = checked
            invalidatePreloadOnChange()
        }

        // OpenSubtitles API key
        binding.inputOpensubtitlesKey.setText(prefs.openSubtitlesApiKey)
        binding.inputOpensubtitlesKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                prefs.openSubtitlesApiKey = binding.inputOpensubtitlesKey.text.toString().trim()
            }
        }

        // TMDB API key — unlocks cast photos, trailer lookup, and banner
        // autoplay by letting the app talk to api.themoviedb.org directly.
        binding.inputTmdbKey.setText(prefs.tmdbApiKey)
        binding.inputTmdbKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) prefs.tmdbApiKey = binding.inputTmdbKey.text.toString()
        }

        // Playback behavior toggles
        binding.switchAutoplayNext.isChecked = prefs.autoplayNextEpisode
        binding.switchAutoplayNext.setOnCheckedChangeListener { _, checked ->
            prefs.autoplayNextEpisode = checked
        }
        binding.switchBannerTrailer.isChecked = prefs.bannerAutoplayTrailer
        binding.switchBannerTrailer.setOnCheckedChangeListener { _, checked ->
            prefs.bannerAutoplayTrailer = checked
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

        binding.switchHideRestricted.isChecked = prefs.hideRestrictedRatings
        binding.switchHideRestricted.setOnCheckedChangeListener { _, checked ->
            prefs.hideRestrictedRatings = checked
            // Rebuild cached rows so the filter takes effect without a restart.
            com.vistacore.launcher.data.ContentCache.movieRows = null
            com.vistacore.launcher.data.ContentCache.showRows = null
        }

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
            // The Home Capture summary depends on this pref too — refresh
            // so the user sees the status flip in real time when they turn
            // Auto-launch off without leaving Settings.
            updateHomeCaptureUI()
        }
    }

    private fun isHomeCaptureEnabled(): Boolean {
        val am = getSystemService(android.view.accessibility.AccessibilityManager::class.java) ?: return false
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun updateHomeCaptureUI() {
        val accessibilityOn = isHomeCaptureEnabled()
        val autoLaunchOn = prefs.autoLaunchOnBoot
        // Both pieces have to be true for the redirect to fire — the
        // accessibility service short-circuits when autoLaunchOnBoot is
        // off (see HomeCaptureService.onAccessibilityEvent). The button
        // text reflects the accessibility permission only (that's what
        // the click actually toggles); the summary line owns the
        // explanation of any second prerequisite.
        binding.btnHomeCapture.text = if (accessibilityOn) "Enabled ✓" else "Enable"
        binding.homeCaptureSummary.text = when {
            accessibilityOn && autoLaunchOn ->
                "Home button will open VistaCore"
            accessibilityOn ->
                "Accessibility on, but Auto-launch on boot is off — turn it on under Home Screen settings"
            else ->
                "Redirect Home button to VistaCore (requires accessibility permission)"
        }
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

    /**
     * Snapshot the connection identity. Delegates to PrefsManager so the
     * exact same fingerprint logic runs in MainActivity (where it
     * decides whether to reload allChannels on resume). If the two
     * drift, password / Dispatcharr / Jellyfin-only edits can stop
     * propagating to the home screen.
     */
    private fun sourceIdentity(): String = prefs.sourceIdentity()

    private fun persistConnectionFields() {
        // Save fields for *both* source types, not just the currently
        // selected one. Otherwise editing M3U URL → flipping to Xtream
        // → backing out drops the M3U edit, because the radio toggle
        // has already updated prefs.sourceType and onPause/saveSettings
        // would only persist Xtream fields.
        prefs.m3uUrl = binding.inputM3uUrl.text.toString().trim()
        prefs.xtreamServer = binding.inputXtreamServer.text.toString().trim()
        prefs.xtreamUsername = binding.inputXtreamUsername.text.toString().trim()
        prefs.xtreamPassword = binding.inputXtreamPassword.text.toString().trim()
        prefs.dispatcharrApiKey = binding.inputDispatcharrApiKey.text.toString().trim()
        prefs.jellyfinServer = binding.inputJellyfinServer.text.toString().trim()
        prefs.jellyfinUsername = binding.inputJellyfinUsername.text.toString().trim()
        prefs.jellyfinPassword = binding.inputJellyfinPassword.text.toString().trim()
        prefs.epgUrl = binding.inputEpgUrl.text.toString().trim()
    }

    private fun saveSettings() {
        persistConnectionFields()
        refreshIfSourceIdentityChanged()
        showStatus(getString(R.string.settings_saved), true)
        Toast.makeText(this, getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show()
    }

    /**
     * Wipe disk caches and schedule a fresh fetch when the source identity
     * has drifted from what it was when the user opened Settings. Catches
     * three flavours of change in one place:
     *  - radio-only source-type flip (OnCheckedChange writes prefs directly
     *    so an in-call before/after comparison would miss it),
     *  - field edits committed via Save,
     *  - field edits + radio flip committed silently via onPause.
     * Re-snapshots entrySourceIdentity after a wipe so a subsequent onPause
     * (e.g. user opens accessibility settings, returns, leaves) doesn't
     * trigger a redundant second wipe.
     */
    private fun refreshIfSourceIdentityChanged() {
        val current = sourceIdentity()
        if (current != entrySourceIdentity) {
            clearCachesAndRefresh()
            entrySourceIdentity = current
        }
    }

    /** Only called from Test Connection — clears caches when URL confirmed changed */
    private fun clearCachesAndRefresh() {
        ChannelUpdateWorker.clearCachesAndRefresh(this)
    }

    /**
     * Build the VOD-section line for Xtream Test Connection. Mirrors the
     * runtime fallback in ChannelUpdateWorker.doWork: when the Dispatcharr
     * key is set, try Dispatcharr first; on either-side failure, fall back
     * to a cheap Xtream category probe — exactly what the worker does.
     * The previous Test code always probed Dispatcharr and reported
     * "movies=error / series=error" on failure even when the runtime
     * would actually succeed via Xtream.
     */
    private suspend fun probeXtreamVod(
        server: String,
        apiKey: String,
        xtream: XtreamClient,
    ): String {
        if (apiKey.isNotBlank()) {
            val vod = com.vistacore.launcher.iptv.DispatcharrVodClient(server, apiKey)
            val movieCount = try { withContext(Dispatchers.IO) { vod.probeMovieCount() } } catch (_: Exception) { -1 }
            val seriesCount = try { withContext(Dispatchers.IO) { vod.probeSeriesCount() } } catch (_: Exception) { -1 }
            if (movieCount >= 0 && seriesCount >= 0) {
                return "via Dispatcharr — movies=$movieCount, series=$seriesCount"
            }
            // Dispatcharr unreachable — runtime falls back to Xtream, so
            // show what that looks like instead of a flat "error".
        }
        val vodCats = try { xtream.probeVodCategoryCount() } catch (_: Exception) { -1 }
        val seriesCats = try { xtream.probeSeriesCategoryCount() } catch (_: Exception) { -1 }
        if (vodCats < 0 && seriesCats < 0) return "VOD probe failed"
        val prefix = if (apiKey.isNotBlank()) "Dispatcharr unreachable, Xtream fallback" else "via Xtream"
        return "$prefix — vod cats=${if (vodCats >= 0) vodCats else "error"}, series cats=${if (seriesCats >= 0) seriesCats else "error"}"
    }

    private fun testConnection() {
        // Persist edits but don't trigger the cache wipe yet — Test
        // Connection wants to verify first, *then* refresh on success.
        persistConnectionFields()
        scope.launch {
            // Each probe runs independently so a Jellyfin-only setup with a
            // blank M3U URL, or a broken IPTV server with a working
            // Jellyfin, both still surface a coherent result instead of
            // skipping the second probe via the outer catch.
            val results = mutableListOf<String>()
            var anySuccess = false
            var anyFailure = false

            if (prefs.hasIptvConfig()) {
                try {
                    when (prefs.sourceType) {
                        PrefsManager.SOURCE_M3U -> {
                            val url = prefs.m3uUrl
                            if (url.isBlank()) {
                                results += "M3U: no playlist URL configured"
                                anyFailure = true
                            } else {
                                val channels = withContext(Dispatchers.IO) {
                                    com.vistacore.launcher.iptv.M3UParser().parse(url)
                                }
                                results += "M3U: ${channels.size} channel${if (channels.size != 1) "s" else ""}"
                                anySuccess = true
                            }
                        }
                        PrefsManager.SOURCE_XTREAM -> {
                            val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                            val client = XtreamClient(auth)
                            val response = withContext(Dispatchers.IO) { client.authenticate() }
                            if (response.user_info?.status == "Active") {
                                results += "Xtream: connected (${probeXtreamVod(prefs.xtreamServer, prefs.dispatcharrApiKey, client)})"
                                anySuccess = true
                            } else {
                                results += "Xtream: ${response.user_info?.status ?: "unknown status"}"
                                anyFailure = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    val detail = e.cause?.message ?: e.message ?: "error"
                    android.util.Log.e("Settings", "IPTV connection test failed", e)
                    results += "IPTV failed: $detail"
                    anyFailure = true
                }
            }

            if (prefs.hasJellyfinConfig()) {
                try {
                    val jf = com.vistacore.launcher.iptv.JellyfinClient(
                        com.vistacore.launcher.iptv.JellyfinAuth(
                            prefs.jellyfinServer, prefs.jellyfinUsername, prefs.jellyfinPassword
                        )
                    )
                    withContext(Dispatchers.IO) { jf.authenticate() }
                    results += "Jellyfin: connected as ${prefs.jellyfinUsername}"
                    anySuccess = true
                } catch (e: Exception) {
                    val detail = e.message ?: "auth failed"
                    results += "Jellyfin failed: $detail"
                    anyFailure = true
                }
            }

            if (results.isEmpty()) {
                showStatus("Configure a source first", false)
                Toast.makeText(this@SettingsActivity, "Configure a source first", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Single status / toast at the end so multi-source results
            // don't overwrite each other in rapid succession. Status row
            // shows green only when every probe that ran succeeded.
            val combined = results.joinToString("\n")
            showStatus(combined, anySuccess && !anyFailure)
            Toast.makeText(this@SettingsActivity, combined, Toast.LENGTH_LONG).show()

            // Any successful probe means the cached content may be stale —
            // refresh so MainActivity picks up the new provider's data.
            // Also re-snapshot the entry identity. testConnection eagerly
            // refreshes even when identity didn't drift (the user pressed
            // Test specifically to refetch); without this, the follow-up
            // Save or onPause would see drift against the original entry
            // snapshot and enqueue a *second* refresh on top of this one.
            if (anySuccess) {
                clearCachesAndRefresh()
                entrySourceIdentity = sourceIdentity()
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
        // Re-sync the switch with the actual pinEnabled state. Without this,
        // first-time setup leaves the switch visually off: the listener
        // synchronously reverts it (because prefs.pinEnabled is still false
        // when the dialog opens), and the dialog's later callback sets
        // prefs.pinEnabled = true but never updates the switch widget.
        if (binding.switchPinEnabled.isChecked != enabled) {
            binding.switchPinEnabled.isChecked = enabled
        }
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

        // Install update button. Use downloadUpdate + explicit completion
        // callback (rather than downloadAndInstall, which is fire-and-forget)
        // so a failed download can re-enable the button. Without this the
        // button stays in its "Downloading…" disabled state forever and the
        // user has to leave Settings and come back to retry.
        fun startInstall(apkUrl: String) {
            binding.btnInstallUpdate.isEnabled = false
            binding.btnInstallUpdate.text = "Downloading…"
            Toast.makeText(this, "Downloading update…", Toast.LENGTH_SHORT).show()
            updateManager.downloadUpdate(apkUrl) { file ->
                if (file != null) {
                    updateManager.installApk(file)
                    // Leave the button disabled — the system installer is
                    // foregrounded next; if the user backs out, they can
                    // reopen Settings to retry, which re-binds the button.
                } else {
                    binding.btnInstallUpdate.isEnabled = true
                    binding.btnInstallUpdate.text = "Download & Install Update"
                    Toast.makeText(
                        this@SettingsActivity,
                        "Download failed. Try again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        binding.btnInstallUpdate.setOnClickListener {
            val info = cachedUpdateInfo
            if (info != null) {
                startInstall(info.apkUrl)
            } else {
                // Fallback: re-check and download
                binding.btnInstallUpdate.isEnabled = false
                scope.launch {
                    val result = updateManager.checkForUpdate(prefs.appUpdateRepo)
                    if (result.available && result.info != null) {
                        startInstall(result.info.apkUrl)
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
