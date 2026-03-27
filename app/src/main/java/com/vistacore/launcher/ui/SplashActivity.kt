package com.vistacore.launcher.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.vistacore.launcher.R
import com.vistacore.launcher.data.ContentCache
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.iptv.*
import com.vistacore.launcher.system.AppUpdateManager
import com.vistacore.launcher.system.AppUpdateWorker
import com.vistacore.launcher.system.ChannelUpdateWorker
import kotlinx.coroutines.*

class SplashActivity : BaseActivity() {

    companion object {
        private const val TAG = "Splash"
        private const val MIN_SPLASH_MS = 2000L

        private val splashBackgrounds = intArrayOf(
            R.drawable.splash_bg_1,
            R.drawable.splash_bg_2,
            R.drawable.splash_bg_3,
            R.drawable.splash_bg_4
        )

        private val loadingMessages = listOf(
            "Initial load takes a bit longer…",
            "Warming up the projector…",
            "Putting the movies in order…",
            "Getting actors ready for their close-up…",
            "Alphabetizing the popcorn flavors…",
            "Convincing the remote to cooperate…",
            "Rounding up the channels…",
            "Teaching the TV new tricks…",
            "Bribing the streaming gods…",
            "Untangling the cable spaghetti…",
            "Polishing the screen…",
            "Rolling out the red carpet…",
            "Telling the commercials to wait outside…",
            "Almost there, grab some snacks…",
            "Finding the best seat in the house…",
            "Fluffing the couch cushions…",
            "Dimming the lights…"
        )

        private val preloadMessages = listOf(
            "Organizing your movie collection…",
            "Sorting shows by binge-worthiness…",
            "Asking the actors to hold still…",
            "Cataloging every plot twist…",
            "Counting explosions in the action section…",
            "Interviewing the characters…",
            "Ironing the movie posters…",
            "Shining a spotlight on the good stuff…",
            "Negotiating with the cliffhangers…",
            "Auditioning categories for the top spots…",
            "Giving each genre its moment…",
            "Removing the boring parts…",
            "Making sure the sequels are in order…",
            "Checking for hidden gems…",
            "Polishing the recommendations…",
            "Rating the ratings…",
            "Herding the categories into rows…",
            "Teaching the remote which button does what…",
            "Microwaving the virtual popcorn…",
            "This is totally worth the wait…",
            "Your couch called. It's ready for you…",
            "Almost there, we promise…"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isFinishing = false
    private var maxProgress = 0
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var bgFront: ImageView
    private lateinit var bgBack: ImageView
    private var bgTransitionJob: Job? = null
    private var shuffledBackgrounds = splashBackgrounds.toList().shuffled()
    private var bgIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.background_dark))
        }

        // Two background layers for crossfade transitions
        bgBack = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        root.addView(bgBack)

        bgFront = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(shuffledBackgrounds[0])
            alpha = 0f
        }
        root.addView(bgFront)

        // Dark gradient overlay for readability
        val overlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), 0x80000000.toInt(), 0xCC000000.toInt())
            )
        }
        root.addView(overlay)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Logo with rounded corners
        val logoCard = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(200.dp, 200.dp).apply {
                gravity = Gravity.CENTER
            }
            radius = 100.dp.toFloat()  // Circular: half of 200dp
            cardElevation = 16.dp.toFloat()
            setCardBackgroundColor(0x00000000)
            alpha = 0f
            scaleX = 0.3f
            scaleY = 0.3f
        }
        val logo = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setImageResource(R.drawable.splash_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        logoCard.addView(logo)
        content.addView(logoCard)

        // App name
        val appName = TextView(this).apply {
            text = "VistaCore"
            textSize = 48f
            setTextColor(getColor(R.color.text_primary))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
            alpha = 0f
            translationY = 30f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 32.dp
                gravity = Gravity.CENTER
            }
        }
        content.addView(appName)

        // Tagline
        val tagline = TextView(this).apply {
            text = "Your Theater Launcher"
            textSize = 20f
            setTextColor(getColor(R.color.accent_gold))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
            letterSpacing = 0.2f
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12.dp
                gravity = Gravity.CENTER
            }
        }
        content.addView(tagline)

        // Progress bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(300.dp, 6.dp).apply {
                topMargin = 40.dp
                gravity = Gravity.CENTER
            }
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent_gold))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x33FFFFFF)
            alpha = 0f
        }
        content.addView(progressBar)

        // Status text
        statusText = TextView(this).apply {
            text = "Loading…"
            textSize = 16f
            setTextColor(getColor(R.color.text_secondary))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12.dp
                gravity = Gravity.CENTER
            }
        }
        content.addView(statusText)

        root.addView(content)
        setContentView(root)

        animateSplash(logoCard, appName, tagline)
        startBackgroundSlideshow()

        // Start loading after animation begins
        handler.postDelayed({
            progressBar.animate().alpha(1f).setDuration(300).start()
            statusText.animate().alpha(1f).setDuration(300).start()
            startLoading()
        }, 1800)
    }

    private fun startLoading() {
        val prefs = PrefsManager(this)
        val startTime = System.currentTimeMillis()
        maxProgress = 0

        if (!prefs.isSetupComplete) {
            // First run — go to setup wizard
            updateStatus("Welcome!", 100)
            handler.postDelayed({
                if (!isFinishing) {
                    startActivity(Intent(this, SetupActivity::class.java))
                    finish()
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                }
            }, 1000)
            return
        }

        // Schedule periodic app update checks
        if (prefs.appAutoUpdateEnabled) {
            AppUpdateWorker.schedule(this)
        }

        // Silent background check for app updates (non-blocking)
        if (prefs.appAutoUpdateEnabled && prefs.appUpdateRepo.isNotBlank()) {
            scope.launch {
                try {
                    val updateManager = AppUpdateManager(this@SplashActivity)
                    val result = updateManager.checkForUpdate(prefs.appUpdateRepo)
                    if (result.available && result.info != null) {
                        Log.d(TAG, "App update available: v${result.info.versionName}")
                        // Auto-download and prompt install
                        updateManager.downloadAndInstall(result.info.apkUrl)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Background app update check failed: ${e.message}")
                }
            }
        }

        if (!prefs.hasIptvConfig()) {
            // Setup was skipped, no credentials yet — go to main, user can add later in Settings
            updateStatus("Ready", 100)
            goToMain(startTime)
            return
        }

        scope.launch {
            try {
                // Check if caches already exist
                val hasLiveCache = withContext(Dispatchers.IO) {
                    val gz = java.io.File(filesDir, "channels_cache.json.gz")
                    val plain = java.io.File(filesDir, "channels_cache.json")
                    (gz.exists() && gz.length() > 0) || (plain.exists() && plain.length() > 0)
                }
                val hasMovieCache = withContext(Dispatchers.IO) {
                    val gz = java.io.File(filesDir, "movies_cache.json.gz")
                    val plain = java.io.File(filesDir, "movies_cache.json")
                    (gz.exists() && gz.length() > 0) || (plain.exists() && plain.length() > 0)
                }

                if (hasLiveCache && hasMovieCache) {
                    if (!ContentCache.isReady) {
                        // Start rotating fun messages while preloading
                        val msgs = preloadMessages.shuffled()
                        var msgIdx = 0
                        val tickerJob = launch {
                            while (true) {
                                val msg = msgs[msgIdx % msgs.size]
                                msgIdx++
                                updateStatus(msg, 30 + (msgIdx * 3).coerceAtMost(60))
                                delay(3000)
                            }
                        }

                        preloadContent()
                        tickerJob.cancel()
                    }
                    updateStatus("Showtime!", 100)
                    goToMain(startTime)
                    return@launch
                }

                // No cache — download with rotating fun messages
                val shuffledMessages = loadingMessages.shuffled().toMutableList()
                var msgIndex = 0

                // Rotate messages every 3 seconds during download
                val messageJob = launch {
                    var fakeProgress = 5
                    while (true) {
                        val msg = if (msgIndex < shuffledMessages.size) {
                            shuffledMessages[msgIndex++]
                        } else {
                            msgIndex = 0
                            shuffledMessages.shuffled().also { shuffledMessages.clear(); shuffledMessages.addAll(it) }
                            shuffledMessages[msgIndex++]
                        }
                        // Mix in progress hints
                        val progressHint = when {
                            fakeProgress < 20 -> msg
                            fakeProgress < 40 -> "$msg\nYou're at about ${fakeProgress}%"
                            fakeProgress < 60 -> "$msg\nHalfway there!"
                            fakeProgress < 80 -> "$msg\nAlmost done…"
                            else -> "Hang tight, finishing up…"
                        }
                        updateStatus(progressHint, fakeProgress.coerceAtMost(85))
                        fakeProgress += (5..12).random()
                        delay(3000)
                    }
                }

                // Timeout: if download takes > 45 seconds, go to main anyway
                var timedOut = false
                val timeoutJob = launch {
                    delay(45000)
                    timedOut = true
                    messageJob.cancel()
                    updateStatus("Still loading — grab a snack, we'll finish in the background", 80)
                    ChannelUpdateWorker.refreshNow(this@SplashActivity)
                    if (prefs.autoUpdateEnabled) {
                        ChannelUpdateWorker.schedule(this@SplashActivity)
                    }
                    goToMain(startTime)
                }

                val allChannels = withContext(Dispatchers.IO) {
                    when (prefs.sourceType) {
                        PrefsManager.SOURCE_M3U -> M3UParser().parse(prefs.m3uUrl)
                        PrefsManager.SOURCE_XTREAM -> {
                            val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                            val xc = XtreamClient(auth)
                            val live = xc.getChannels()
                            val movies = try { xc.getMovies() } catch (_: Exception) { emptyList() }
                            val series = try { xc.getSeries() } catch (_: Exception) { emptyList() }
                            live + movies + series
                        }
                        else -> emptyList()
                    }
                }

                timeoutJob.cancel()
                messageJob.cancel()
                if (timedOut) return@launch

                updateStatus("Organizing ${allChannels.size} items…", 70)

                withContext(Dispatchers.IO) {
                    ChannelUpdateWorker.cacheChannels(this@SplashActivity, allChannels)
                }

                val live = allChannels.count { it.contentType == ContentType.LIVE }
                val movies = allChannels.count { it.contentType == ContentType.MOVIE }
                val series = allChannels.count { it.contentType == ContentType.SERIES }

                updateStatus("$live channels, $movies movies, $series shows", 85)
                delay(1000)

                // Preload Netflix rows
                updateStatus("Setting up your theater…", 90)
                preloadContent()

                if (prefs.autoUpdateEnabled) {
                    ChannelUpdateWorker.schedule(this@SplashActivity)
                }

                updateStatus("Showtime!", 100)
                goToMain(startTime)

            } catch (e: Exception) {
                Log.e(TAG, "Loading failed", e)
                // Trigger background download and proceed
                ChannelUpdateWorker.refreshNow(this@SplashActivity)
                updateStatus("Loading in background — continuing", 100)
                goToMain(startTime)
            }
        }
    }

    // Single combined regex — matches the first episode/season marker and strips everything from there
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

    private suspend fun preloadContent() = withContext(Dispatchers.IO) {
        val loadPrefs = PrefsManager(this@SplashActivity)
        val tracker = com.vistacore.launcher.data.UsageTracker(this@SplashActivity)
        try {
            // Movies
            val movies = if (loadPrefs.loadMoviesEnabled) ChannelUpdateWorker.getCachedMovies(this@SplashActivity) else null
            if (movies != null && movies.isNotEmpty()) {
                ContentCache.movieItems = movies
                val rows = mutableListOf<NetflixRow>()
                val samplePool = movies.take(500)
                val withPosters = samplePool.filter { it.logoUrl.isNotBlank() }.shuffled().take(5)
                if (withPosters.isNotEmpty()) rows.add(NetflixRow.Banner(withPosters.first()))
                val grouped = movies.groupBy { it.category }
                    .filter { !tracker.isCategoryHidden(it.key) }
                val sorted = grouped.entries.sortedWith(
                    compareByDescending<Map.Entry<String, List<Channel>>> { tracker.getCategoryUsage(it.key) }
                        .thenByDescending { it.value.size }
                ).take(25)
                for ((cat, items) in sorted) {
                    rows.add(NetflixRow.CategoryRow(cat, items))
                }
                ContentCache.movieRows = rows
            }

            withContext(Dispatchers.Main) { updateStatus("Movies ready, loading shows…", 50) }

            // Shows
            val series = if (loadPrefs.loadShowsEnabled) ChannelUpdateWorker.getCachedSeries(this@SplashActivity) else null
            if (series != null && series.isNotEmpty()) {
                ContentCache.showItems = series

                // Try loading precomputed show names from disk (instant — no regex)
                var showNameMap = ContentCache.loadShowNameMap(this@SplashActivity)

                // If no cached map, or size doesn't match series, recompute
                if (showNameMap == null || showNameMap.size != series.size) {
                    showNameMap = HashMap<String, String>(series.size)
                    for (ch in series) {
                        showNameMap[ch.id] = extractShowName(ch.name)
                    }
                    // Save to disk for next time
                    ContentCache.saveShowNameMap(this@SplashActivity, showNameMap)
                }

                ContentCache.showNameMap = showNameMap

                // Build episodes index using precomputed names
                val episodesIndex = HashMap<String, MutableList<Channel>>()
                for (ch in series) {
                    val name = showNameMap[ch.id] ?: ch.name
                    episodesIndex.getOrPut(name) { mutableListOf() }.add(ch)
                }
                ContentCache.showEpisodesIndex = episodesIndex

                // Deduplicate using precomputed names
                val seen = mutableSetOf<String>()
                val uniqueShows = series.filter { seen.add(showNameMap[it.id] ?: it.name) }

                val rows = mutableListOf<NetflixRow>()
                val samplePool = uniqueShows.take(500)
                val withPosters = samplePool.filter { it.logoUrl.isNotBlank() }.shuffled().take(5)
                if (withPosters.isNotEmpty()) rows.add(NetflixRow.Banner(withPosters.first()))
                val grouped = uniqueShows.groupBy { it.category }
                    .filter { !tracker.isCategoryHidden(it.key) }
                val sortedShows = grouped.entries.sortedWith(
                    compareByDescending<Map.Entry<String, List<Channel>>> { tracker.getCategoryUsage(it.key) }
                        .thenByDescending { it.value.size }
                ).take(25)
                for ((cat, items) in sortedShows) {
                    rows.add(NetflixRow.CategoryRow(cat, items))
                }
                ContentCache.showRows = rows
            }

            withContext(Dispatchers.Main) { updateStatus("Shows ready, loading kids…", 70) }

            // Kids
            if (!loadPrefs.loadKidsEnabled) {
                // Skip kids preload
            } else {
            val allowedKidsCategories = setOf("animation", "family", "kids")
            val blockedKidsCategories = setOf("action & adventure (shows)", "family (shows)")

            fun isKids(ch: Channel): Boolean {
                val cat = ch.category.lowercase().trim()
                if (cat in blockedKidsCategories) return false
                return cat in allowedKidsCategories
            }

            val kidsMovies = (movies ?: emptyList()).filter { isKids(it) }
            val kidsSeries = (series ?: emptyList()).filter { isKids(it) }
            val kidsLive = ChannelUpdateWorker.getCachedChannels(this@SplashActivity)?.filter { isKids(it) } ?: emptyList()
            val allKids = kidsMovies + kidsSeries + kidsLive

            if (allKids.isNotEmpty()) {
                ContentCache.kidsItems = allKids
                ContentCache.kidsShowIndex = kidsSeries.groupBy { extractShowName(it.name) }

                val kidsRows = mutableListOf<NetflixRow>()
                val kidsBannerPool = kidsMovies.take(200)
                val kidsPosters = kidsBannerPool.filter { it.logoUrl.isNotBlank() }.shuffled().take(5)
                if (kidsPosters.isNotEmpty()) kidsRows.add(NetflixRow.Banner(kidsPosters.first()))

                if (kidsLive.isNotEmpty()) kidsRows.add(NetflixRow.CategoryRow("Kids Live TV", kidsLive.take(30)))

                val kidMovieGroups = kidsMovies.groupBy { it.category }
                for ((cat, items) in kidMovieGroups.entries.sortedByDescending { it.value.size }.take(15)) {
                    kidsRows.add(NetflixRow.CategoryRow(cat, items))
                }

                val uniqueKidsShows = run {
                    val seen = mutableSetOf<String>()
                    kidsSeries.filter { seen.add(extractShowName(it.name)) }
                }
                val kidShowGroups = uniqueKidsShows.groupBy { it.category }
                for ((cat, items) in kidShowGroups.entries.sortedByDescending { it.value.size }.take(10)) {
                    kidsRows.add(NetflixRow.CategoryRow("$cat (Shows)", items))
                }

                ContentCache.kidsRows = kidsRows
            }
            } // end kids else

            // Preload EPG so Live TV has it instantly
            if (ContentCache.epgData == null) {
                withContext(Dispatchers.Main) { updateStatus("Loading TV guide…", 90) }

                val liveChannels = ChannelUpdateWorker.getCachedChannels(this@SplashActivity) ?: emptyList()
                val liveOnly = liveChannels.filter { it.contentType == ContentType.LIVE }
                Log.d(TAG, "EPG preload: ${liveOnly.size} live channels (${liveOnly.count { it.epgId.isNotBlank() }} with epgId)")

                // Try Xtream native EPG first (more reliable with Dispatcharr)
                // Retry once after a delay if the first attempt gets 0 programs
                // (Dispatcharr proxy may need time to warm up)
                if (loadPrefs.sourceType == PrefsManager.SOURCE_XTREAM && loadPrefs.xtreamServer.isNotBlank()) {
                    val auth = XtreamAuth(loadPrefs.xtreamServer, loadPrefs.xtreamUsername, loadPrefs.xtreamPassword)
                    val xc = XtreamClient(auth)

                    for (attempt in 1..2) {
                        try {
                            val epg = xc.getEpg(liveOnly)
                            if (epg.programs.isNotEmpty()) {
                                ContentCache.epgData = epg
                                ContentCache.epgLoadTime = System.currentTimeMillis()
                                Log.d(TAG, "Xtream native EPG (attempt $attempt): ${epg.programs.size} programs")
                                break
                            } else {
                                Log.w(TAG, "Xtream native EPG attempt $attempt: 0 programs")
                                if (attempt == 1) {
                                    Log.d(TAG, "Retrying EPG in 3s (proxy may need warmup)…")
                                    delay(3000)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Xtream native EPG attempt $attempt failed: ${e.message}")
                            break
                        }
                    }
                }

                // Fallback to XMLTV if Xtream EPG was empty/failed
                if (ContentCache.epgData == null || ContentCache.epgData?.programs?.isEmpty() == true) {
                    var epgUrl = loadPrefs.epgUrl
                    if (epgUrl.isBlank() && loadPrefs.sourceType == PrefsManager.SOURCE_XTREAM &&
                        loadPrefs.xtreamServer.isNotBlank()) {
                        val s = loadPrefs.xtreamServer.trimEnd('/')
                        epgUrl = "$s/xmltv.php?username=${loadPrefs.xtreamUsername}&password=${loadPrefs.xtreamPassword}"
                    }
                    if (epgUrl.isNotBlank()) {
                        try {
                            val epg = EpgParser().parse(epgUrl)
                            if (epg.programs.isNotEmpty()) {
                                ContentCache.epgData = epg
                                ContentCache.epgLoadTime = System.currentTimeMillis()
                                Log.d(TAG, "XMLTV EPG: ${epg.programs.size} programs")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "XMLTV EPG preload failed — will retry later", e)
                        }
                    }
                }
            }

            Log.d(TAG, "Preloaded: ${ContentCache.movieRows?.size ?: 0} movie rows, ${ContentCache.showRows?.size ?: 0} show rows, ${ContentCache.kidsRows?.size ?: 0} kids rows, epg=${ContentCache.epgData != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Preload failed", e)
        }
    }

    private fun updateStatus(text: String, percent: Int) {
        val clamped = maxOf(percent, maxProgress)
        maxProgress = clamped
        statusText.text = text
        ObjectAnimator.ofInt(progressBar, "progress", clamped).apply {
            duration = 300
            start()
        }
    }

    private fun goToMain(startTime: Long) {
        val elapsed = System.currentTimeMillis() - startTime
        val remaining = (MIN_SPLASH_MS - elapsed).coerceAtLeast(0)

        handler.postDelayed({
            if (!isFinishing) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }, remaining)
    }

    override fun onDestroy() {
        super.onDestroy()
        isFinishing = true
        bgTransitionJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
    }

    // --- Background Slideshow with Cinematic Transitions ---

    private fun startBackgroundSlideshow() {
        // Apply first transition to the front image
        bgFront.alpha = 0f
        resetTransform(bgFront)
        applyTransitionStart(bgFront, 0)
        bgFront.animate().alpha(1f).setDuration(1500).start()
        applyTransitionAnimate(bgFront, 0)

        bgIndex = 0

        // Cycle images every 6 seconds
        bgTransitionJob = scope.launch {
            delay(5000)
            while (isActive) {
                bgIndex = (bgIndex + 1) % shuffledBackgrounds.size
                val transitionType = bgIndex % transitions.size
                transitionToNext(shuffledBackgrounds[bgIndex], transitionType)
                delay(6000)
            }
        }
    }

    private data class TransitionDef(
        val startScaleX: Float, val startScaleY: Float,
        val startTransX: Float, val startTransY: Float,
        val startRotation: Float,
        val endScaleX: Float, val endScaleY: Float,
        val endTransX: Float, val endTransY: Float,
        val endRotation: Float
    )

    private val transitions = listOf(
        // Zoom out + drift left (classic Ken Burns)
        TransitionDef(1.2f, 1.2f, 40f, 0f, 0f, 1.0f, 1.0f, -20f, 0f, 0f),
        // Zoom in from center
        TransitionDef(1.0f, 1.0f, 0f, 0f, 0f, 1.15f, 1.15f, 0f, 0f, 0f),
        // Pan right + slight zoom
        TransitionDef(1.1f, 1.1f, -50f, 0f, 0f, 1.05f, 1.05f, 30f, 0f, 0f),
        // Diagonal drift + subtle rotation
        TransitionDef(1.15f, 1.15f, -20f, -15f, -0.5f, 1.05f, 1.05f, 20f, 15f, 0.5f)
    )

    private fun resetTransform(view: ImageView) {
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.rotation = 0f
    }

    private fun applyTransitionStart(view: ImageView, type: Int) {
        val t = transitions[type % transitions.size]
        view.scaleX = t.startScaleX
        view.scaleY = t.startScaleY
        view.translationX = t.startTransX
        view.translationY = t.startTransY
        view.rotation = t.startRotation
    }

    private fun applyTransitionAnimate(view: ImageView, type: Int) {
        val t = transitions[type % transitions.size]
        val dur = 7000L
        view.animate().scaleX(t.endScaleX).scaleY(t.endScaleY)
            .translationX(t.endTransX).translationY(t.endTransY)
            .rotation(t.endRotation)
            .setDuration(dur).setInterpolator(LinearInterpolator()).start()
    }

    private fun transitionToNext(imageRes: Int, transitionType: Int) {
        // Prepare back layer with next image
        resetTransform(bgBack)
        bgBack.setImageResource(imageRes)
        bgBack.alpha = 0f
        applyTransitionStart(bgBack, transitionType)

        // Crossfade: back fades in, front fades out
        bgBack.animate().alpha(1f).setDuration(1500).setInterpolator(DecelerateInterpolator()).start()
        bgFront.animate().alpha(0f).setDuration(1500).setInterpolator(DecelerateInterpolator()).start()

        // Start Ken Burns motion on the incoming image
        applyTransitionAnimate(bgBack, transitionType)

        // Swap references after crossfade completes
        handler.postDelayed({
            val temp = bgFront
            bgFront = bgBack
            bgBack = temp
        }, 1600)
    }

    // --- Logo & UI Animations ---

    private fun animateSplash(logoCard: View, name: View, tagline: View) {
        // Logo pop with bounce
        val logoScaleX = ObjectAnimator.ofFloat(logoCard, "scaleX", 0.3f, 1f).apply {
            duration = 800
            startDelay = 500
            interpolator = OvershootInterpolator(2f)
        }
        val logoScaleY = ObjectAnimator.ofFloat(logoCard, "scaleY", 0.3f, 1f).apply {
            duration = 800
            startDelay = 500
            interpolator = OvershootInterpolator(2f)
        }
        val logoAlpha = ObjectAnimator.ofFloat(logoCard, "alpha", 0f, 1f).apply {
            duration = 500
            startDelay = 500
        }
        val nameAlpha = ObjectAnimator.ofFloat(name, "alpha", 0f, 1f).apply {
            duration = 600
            startDelay = 900
        }
        val nameSlide = ObjectAnimator.ofFloat(name, "translationY", 30f, 0f).apply {
            duration = 600
            startDelay = 900
            interpolator = AccelerateDecelerateInterpolator()
        }
        val taglineAlpha = ObjectAnimator.ofFloat(tagline, "alpha", 0f, 1f).apply {
            duration = 600
            startDelay = 1300
        }

        AnimatorSet().apply {
            playTogether(logoScaleX, logoScaleY, logoAlpha, nameAlpha, nameSlide, taglineAlpha)
            start()
        }

        // Gentle floating bounce on logo (loops forever)
        startLogoFloat(logoCard)
    }

    private fun startLogoFloat(logo: View) {
        val floatUp = ObjectAnimator.ofFloat(logo, "translationY", 0f, -8f).apply {
            duration = 2000
            interpolator = AccelerateDecelerateInterpolator()
        }
        val floatDown = ObjectAnimator.ofFloat(logo, "translationY", -8f, 0f).apply {
            duration = 2000
            interpolator = AccelerateDecelerateInterpolator()
        }

        val set = AnimatorSet().apply {
            playSequentially(floatUp, floatDown)
        }

        set.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!isFinishing) set.start()
            }
        })

        // Start after initial pop animation finishes
        handler.postDelayed({ set.start() }, 1400)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
