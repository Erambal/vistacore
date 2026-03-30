package com.vistacore.launcher.ui

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.vistacore.launcher.iptv.OpenSubtitlesClient
import kotlinx.coroutines.*
import com.vistacore.launcher.R
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.data.RecentChannelsManager
import com.vistacore.launcher.databinding.ActivityIptvPlayerBinding
import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.ContentType
import com.vistacore.launcher.system.ChannelUpdateWorker

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class IPTVPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_CHANNEL_LOGO = "channel_logo"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_ALL_CHANNELS_JSON = "all_channels_json"
        const val EXTRA_RESUME_POSITION = "resume_position"
        const val EXTRA_IS_VOD = "is_vod"
        const val EXTRA_CONTENT_YEAR = "content_year"
        private const val OVERLAY_DISPLAY_MS = 4000L
        private const val SCRUB_HIDE_DELAY_MS = 15000L
        private const val SEEK_INCREMENT_MS = 10000L
        private const val TAG = "IPTVPlayer"
    }

    private lateinit var binding: ActivityIptvPlayerBinding
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private lateinit var prefs: PrefsManager
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var recentChannels: RecentChannelsManager
    private lateinit var watchHistory: com.vistacore.launcher.data.WatchHistoryManager
    private var numberPad: ChannelNumberPad? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var backPressedOnce = false
    private var streamUrl: String = ""
    private var channelName: String = ""
    private var channelId: String = ""
    private var contentYear: String = ""
    private var isInPipMode = false
    private var isVodMode = false
    private var allChannels: List<Channel> = emptyList()
    private var currentChannelIndex: Int = -1

    // Scrub bar state
    private var scrubVisible = false
    private val hideScrubRunnable = Runnable { hideScrubBar() }
    private var seekSpeed = 0 // 0=normal, 1=2x, 2=4x, 3=8x
    private var isSeeking = false
    private val seekRunnable = object : Runnable {
        override fun run() {
            if (!isSeeking) return
            val exo = player ?: return
            val multiplier = when (seekSpeed) { 1 -> 2000L; 2 -> 4000L; 3 -> 8000L; else -> 0L }
            if (multiplier > 0) {
                val newPos = (exo.currentPosition + seekDirection * multiplier).coerceIn(0, exo.duration)
                exo.seekTo(newPos)
                updateScrubPosition()
                handler.postDelayed(this, 200)
            }
        }
    }
    private var seekDirection = 1L // 1 = forward, -1 = rewind

    private val httpDataSourceFactory by lazy {
        DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(60000)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIptvPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = PrefsManager(this)
        recentChannels = RecentChannelsManager(this)
        watchHistory = com.vistacore.launcher.data.WatchHistoryManager(this)

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""
        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
        channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: ""
        contentYear = intent.getStringExtra(EXTRA_CONTENT_YEAR) ?: ""
        isVodMode = intent.getBooleanExtra(EXTRA_IS_VOD, false) || channelId.isBlank()

        if (streamUrl.isBlank()) {
            showError()
            return
        }

        // Track this channel as recently watched
        if (channelId.isNotBlank()) {
            recentChannels.addRecent(channelId)
        }

        binding.btnRetry.setOnClickListener { retryPlayback() }
        binding.btnRetry.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }

        loadChannelList()
        setupPlayer()
        if (isVodMode) {
            setupScrubBar()
        } else {
            setupPlayerControls()
        }
        showChannelOverlay()
    }

    private var controlsVisible = false
    private val hideControlsRunnable = Runnable { hideControls() }

    private fun loadChannelList() {
        val cached = ChannelUpdateWorker.getCachedChannels(this)
        if (cached != null) {
            allChannels = cached.filter { it.contentType == ContentType.LIVE }
            currentChannelIndex = allChannels.indexOfFirst { it.id == channelId }
        }
    }

    private fun setupPlayerControls() {
        binding.ctrlChannelName.text = channelName

        binding.btnPlayPause.setOnClickListener {
            player?.let { exo ->
                if (exo.isPlaying) {
                    exo.pause()
                    binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
                } else {
                    exo.play()
                    binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                }
            }
        }

        binding.btnPrevChannel.setOnClickListener { switchToPreviousChannel() }
        binding.btnNextChannel.setOnClickListener { switchToNextChannel() }

        binding.btnAudioTrack.setOnClickListener { showAudioTrackPicker() }
        binding.btnSubtitleTrack.setOnClickListener { showSubtitlePicker() }

        // Focus animations
        listOf(binding.btnPrevChannel, binding.btnPlayPause, binding.btnNextChannel,
               binding.btnAudioTrack, binding.btnSubtitleTrack).forEach { btn ->
            btn.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
        }

        // Number pad for remote number keys
        if (allChannels.isNotEmpty()) {
            numberPad = ChannelNumberPad(this, binding.root as FrameLayout, allChannels) { channel ->
                switchStream(channel.streamUrl, channel.name, channel.id)
                binding.ctrlChannelName.text = channel.name
                currentChannelIndex = allChannels.indexOf(channel)
            }
        }
    }

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        controlsVisible = true
        binding.controlsOverlay.visibility = View.VISIBLE
        binding.btnPlayPause.setImageResource(
            if (player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
        // Give focus to play/pause button so d-pad navigation works
        binding.btnPlayPause.requestFocus()
        // Auto-hide after 5 seconds
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 5000)
    }

    private fun hideControls() {
        controlsVisible = false
        binding.controlsOverlay.visibility = View.GONE
        handler.removeCallbacks(hideControlsRunnable)
        binding.playerView.requestFocus()
    }

    // --- VOD Scrub Bar ---

    private var scrubDragging = false

    private fun setupScrubBar() {
        binding.scrubTitle.text = channelName

        // Play/Pause
        binding.scrubPlayPause.setOnClickListener {
            player?.let { exo ->
                if (exo.isPlaying) {
                    exo.pause()
                    binding.scrubPlayPause.setImageResource(R.drawable.ic_play_arrow)
                } else {
                    exo.play()
                    binding.scrubPlayPause.setImageResource(R.drawable.ic_pause)
                }
            }
            resetScrubTimer()
        }

        // Rewind button: -10s per click
        binding.scrubRewind.setOnClickListener {
            seekBy(-SEEK_INCREMENT_MS)
        }

        // Forward button: +10s per click
        binding.scrubForward.setOnClickListener {
            seekBy(SEEK_INCREMENT_MS)
        }

        // SeekBar — the primary scrub control
        // D-pad left/right moves the dot smoothly via key increment
        binding.scrubSeekbar.keyProgressIncrement = 10 // 1% of 1000
        binding.scrubSeekbar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    scrubDragging = true
                    val exo = player ?: return
                    val dur = exo.duration.coerceAtLeast(1)
                    val pos = (dur * progress / 1000)
                    binding.scrubCurrentTime.text = formatTime(pos)
                    val remaining = dur - pos
                    binding.scrubSpeed.text = "-${formatTime(remaining)}"
                    binding.scrubSpeed.visibility = View.VISIBLE
                    // Seek immediately as the dot moves (smooth scrubbing)
                    exo.seekTo(pos)
                    resetScrubTimer()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                scrubDragging = true
                handler.removeCallbacks(hideScrubRunnable)
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                scrubDragging = false
                binding.scrubSpeed.visibility = View.GONE
                resetScrubTimer()
            }
        })

        binding.scrubAudioTrack.setOnClickListener { showAudioTrackPicker() }
        binding.scrubSubtitleTrack.setOnClickListener { showSubtitlePicker() }

        // Focus animations
        listOf(binding.scrubRewind, binding.scrubPlayPause, binding.scrubForward,
               binding.scrubAudioTrack, binding.scrubSubtitleTrack).forEach { btn ->
            btn.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
        }

        // Give seekbar a focus highlight too
        binding.scrubSeekbar.setOnFocusChangeListener { _, hasFocus ->
            binding.scrubSeekbar.thumbTintList = android.content.res.ColorStateList.valueOf(
                getColor(if (hasFocus) R.color.accent_gold_light else R.color.accent_gold)
            )
        }

        startScrubUpdater()
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val dur = exo.duration.coerceAtLeast(0)
        val newPos = (exo.currentPosition + deltaMs).coerceIn(0, dur)
        exo.seekTo(newPos)
        updateScrubPosition()
        resetScrubTimer()
        // Show brief seek feedback
        val secs = (deltaMs / 1000).toInt()
        val label = if (secs > 0) "+${secs}s" else "${secs}s"
        binding.scrubSpeed.text = label
        binding.scrubSpeed.visibility = View.VISIBLE
        handler.postDelayed({ binding.scrubSpeed.visibility = View.GONE }, 800)
    }

    private fun showScrubBar() {
        scrubVisible = true
        binding.scrubOverlay.visibility = View.VISIBLE
        binding.scrubPlayPause.setImageResource(
            if (player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
        // Focus seekbar so D-pad left/right immediately scrubs
        binding.scrubSeekbar.requestFocus()
        updateScrubPosition()
        resetScrubTimer()
    }

    private fun hideScrubBar() {
        scrubVisible = false
        binding.scrubOverlay.visibility = View.GONE
        handler.removeCallbacks(hideScrubRunnable)
        stopFastSeek()
        binding.playerView.requestFocus()
    }

    private fun toggleScrubBar() {
        if (scrubVisible) hideScrubBar() else showScrubBar()
    }

    private fun resetScrubTimer() {
        handler.removeCallbacks(hideScrubRunnable)
        handler.postDelayed(hideScrubRunnable, SCRUB_HIDE_DELAY_MS)
    }

    private fun updateScrubPosition() {
        if (scrubDragging) return // Don't update while user is dragging
        val exo = player ?: return
        val pos = exo.currentPosition
        val dur = exo.duration
        if (dur > 0) {
            binding.scrubSeekbar.progress = ((pos * 1000) / dur).toInt()
            binding.scrubCurrentTime.text = formatTime(pos)
            binding.scrubTotalTime.text = formatTime(dur)
        }
    }

    private fun startScrubUpdater() {
        val updater = object : Runnable {
            override fun run() {
                if (scrubVisible) updateScrubPosition()
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(updater, 1000)
    }

    private fun stopFastSeek() {
        isSeeking = false
        seekSpeed = 0
        binding.scrubSpeed.visibility = View.GONE
        handler.removeCallbacks(seekRunnable)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val hours = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, mins, secs)
        else String.format("%d:%02d", mins, secs)
    }

    private fun switchToPreviousChannel() {
        if (allChannels.isEmpty()) return
        currentChannelIndex = if (currentChannelIndex > 0) currentChannelIndex - 1 else allChannels.size - 1
        val ch = allChannels[currentChannelIndex]
        switchStream(ch.streamUrl, ch.name, ch.id)
        binding.ctrlChannelName.text = ch.name
    }

    private fun switchToNextChannel() {
        if (allChannels.isEmpty()) return
        currentChannelIndex = if (currentChannelIndex < allChannels.size - 1) currentChannelIndex + 1 else 0
        val ch = allChannels[currentChannelIndex]
        switchStream(ch.streamUrl, ch.name, ch.id)
        binding.ctrlChannelName.text = ch.name
    }

    private fun retryPlayback() {
        currentStrategyIndex = 0
        binding.playerError.visibility = View.GONE
        player?.release()
        player = null
        setupPlayer()
    }

    private fun setupPlayer() {
        showLoading(true)

        val selector = DefaultTrackSelector(this).also { ts ->
            trackSelector = ts
            val params = ts.buildUponParameters()
            val audioLang = prefs.preferredAudioLanguage
            if (audioLang.isNotBlank()) {
                params.setPreferredAudioLanguage(audioLang)
            }
            val subLang = prefs.preferredSubtitleLanguage
            if (subLang.isNotBlank()) {
                params.setPreferredTextLanguage(subLang)
                    .setRendererDisabled(C.TRACK_TYPE_TEXT, false)
            } else {
                params.setRendererDisabled(C.TRACK_TYPE_TEXT, true)
            }
            ts.setParameters(params)
        }

        player = ExoPlayer.Builder(this)
            .setTrackSelector(selector)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build().also { exo ->
            // Allow seeking to any position (not just keyframes)
            exo.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            binding.playerView.player = exo
            binding.playerView.subtitleView?.setApplyEmbeddedStyles(true)

            exo.addListener(object : Player.Listener {
                private var hasResumed = false

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            showLoading(false)
                            // Resume from saved position (only once)
                            if (!hasResumed) {
                                hasResumed = true
                                val resumePos = intent.getLongExtra(EXTRA_RESUME_POSITION, -1)
                                val savedPos = if (resumePos > 0) resumePos else watchHistory.getPosition(streamUrl)
                                if (savedPos > 0) {
                                    exo.seekTo(savedPos)
                                }
                            }
                        }
                        Player.STATE_BUFFERING -> showLoading(true)
                        Player.STATE_ENDED -> {
                            // Mark as finished
                            saveCurrentPosition()
                            finish()
                        }
                        Player.STATE_IDLE -> { /* no-op */ }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Playback error for $streamUrl (strategy=${sourceStrategies.getOrNull(currentStrategyIndex)}): ${error.message}", error)
                    showLoading(false)
                    lastError = error
                    tryNextStrategy(exo)
                }
            })

            playStream(exo, streamUrl)
        }
    }

    private fun playStream(exo: ExoPlayer, url: String) {
        val mediaSource = buildMediaSource(url)
        exo.setMediaSource(mediaSource)
        exo.prepare()
        exo.playWhenReady = true
    }

    // Ordered list of source factories to try
    private val sourceStrategies = listOf("progressive", "hls", "dash")
    private var currentStrategyIndex = 0

    private fun buildMediaSource(url: String): MediaSource {
        val uri = Uri.parse(url)
        val mediaItem = MediaItem.fromUri(uri)
        val urlLower = url.lowercase()

        // Pick the best starting strategy based on URL
        currentStrategyIndex = when {
            urlLower.contains(".m3u8") -> sourceStrategies.indexOf("hls")
            urlLower.contains(".mpd") -> sourceStrategies.indexOf("dash")
            else -> 0 // progressive first
        }

        return buildSourceForStrategy(mediaItem, sourceStrategies[currentStrategyIndex])
    }

    private fun buildSourceForStrategy(mediaItem: MediaItem, strategy: String): MediaSource {
        return when (strategy) {
            "hls" -> HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)
            "dash" -> DashMediaSource.Factory(httpDataSourceFactory)
                .createMediaSource(mediaItem)
            else -> ProgressiveMediaSource.Factory(httpDataSourceFactory)
                .createMediaSource(mediaItem)
        }
    }

    private fun tryNextStrategy(exo: ExoPlayer) {
        currentStrategyIndex++
        if (currentStrategyIndex >= sourceStrategies.size) {
            showError()
            return
        }

        val strategy = sourceStrategies[currentStrategyIndex]
        Log.d(TAG, "Trying $strategy for $streamUrl")
        try {
            val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
            val source = buildSourceForStrategy(mediaItem, strategy)
            exo.setMediaSource(source)
            exo.prepare()
            exo.playWhenReady = true
        } catch (_: Exception) {
            tryNextStrategy(exo)
        }
    }

    private fun showChannelOverlay() {
        // Show channel number if available
        val chNum = allChannels.find { it.id == channelId }?.number
        if (chNum != null && chNum > 0) {
            binding.overlayChannelNumber.text = chNum.toString()
            binding.overlayChannelNumber.visibility = View.VISIBLE
        } else {
            binding.overlayChannelNumber.visibility = View.GONE
        }
        binding.overlayChannelName.text = channelName
        binding.channelInfoOverlay.visibility = View.VISIBLE

        handler.postDelayed({
            binding.channelInfoOverlay.animate()
                .alpha(0f)
                .setDuration(500)
                .withEndAction {
                    binding.channelInfoOverlay.visibility = View.GONE
                    binding.channelInfoOverlay.alpha = 1f
                }
                .start()
        }, OVERLAY_DISPLAY_MS)
    }

    private fun showLoading(show: Boolean) {
        binding.playerLoading.visibility = if (show) View.VISIBLE else View.GONE
        binding.playerError.visibility = View.GONE
    }

    private var lastError: PlaybackException? = null

    private fun showError() {
        binding.playerLoading.visibility = View.GONE
        binding.playerError.visibility = View.VISIBLE

        val err = lastError
        val friendly = if (err != null) {
            val msg = (err.cause?.message ?: err.message ?: "").lowercase()
            when {
                msg.contains("403") || msg.contains("forbidden") ->
                    "Access denied. Your subscription may have expired."
                msg.contains("404") || msg.contains("not found") ->
                    "This stream is no longer available."
                msg.contains("timeout") || msg.contains("timed out") ->
                    "The stream is too slow or not responding."
                msg.contains("unable to connect") || msg.contains("failed to connect") ->
                    "Could not connect to the server. Check your internet."
                msg.contains("source error") || msg.contains("no valid") ->
                    "This stream format is not supported."
                else -> err.cause?.message ?: err.message ?: "The stream could not be loaded."
            }
        } else {
            "The stream could not be loaded."
        }
        binding.errorDetails.text = friendly
        binding.errorUrl.text = streamUrl

        binding.btnRetry.requestFocus()
    }

    // --- Picture-in-Picture ---

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode

        if (isInPipMode) {
            binding.playerView.useController = false
            binding.channelInfoOverlay.visibility = View.GONE
        } else {
            binding.playerView.useController = true
        }
    }

    // --- Audio / Subtitle Track Pickers ---

    private fun showAudioTrackPicker() {
        val exo = player ?: return
        val tracks = exo.currentTracks
        val audioGroups = mutableListOf<Pair<String, TrackSelectionOverride>>()

        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val lang = format.language ?: "und"
                val label = format.label
                    ?: java.util.Locale(lang).displayLanguage.replaceFirstChar { it.uppercase() }
                audioGroups.add(label to TrackSelectionOverride(group.mediaTrackGroup, i))
            }
        }

        if (audioGroups.isEmpty()) {
            android.widget.Toast.makeText(this, "No audio tracks available", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val names = audioGroups.map { it.first }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Audio Language")
            .setItems(names) { _, which ->
                val override = audioGroups[which].second
                trackSelector?.setParameters(
                    trackSelector!!.buildUponParameters()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .addOverride(override)
                )
                // Save preference
                val format = override.mediaTrackGroup.getFormat(override.trackIndices.first())
                prefs.preferredAudioLanguage = format.language ?: ""
            }
            .show()
    }

    private fun showSubtitlePicker() {
        val exo = player ?: return
        val tracks = exo.currentTracks
        val subGroups = mutableListOf<Pair<String, TrackSelectionOverride?>>()
        subGroups.add("Off" to null)

        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val lang = format.language ?: "und"
                val label = format.label
                    ?: java.util.Locale(lang).displayLanguage.replaceFirstChar { it.uppercase() }
                subGroups.add(label to TrackSelectionOverride(group.mediaTrackGroup, i))
            }
        }

        // Build options: embedded tracks + online search
        val names = subGroups.map { it.first }.toMutableList()
        if (isVodMode) names.add("Search Online...")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setItems(names.toTypedArray()) { _, which ->
                if (which < subGroups.size) {
                    val override = subGroups[which].second
                    if (override == null) {
                        trackSelector?.setParameters(
                            trackSelector!!.buildUponParameters()
                                .setRendererDisabled(C.TRACK_TYPE_TEXT, true)
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        )
                        prefs.preferredSubtitleLanguage = ""
                    } else {
                        trackSelector?.setParameters(
                            trackSelector!!.buildUponParameters()
                                .setRendererDisabled(C.TRACK_TYPE_TEXT, false)
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .addOverride(override)
                        )
                        val format = override.mediaTrackGroup.getFormat(override.trackIndices.first())
                        prefs.preferredSubtitleLanguage = format.language ?: ""
                    }
                } else {
                    // "Search Online..."
                    searchOnlineSubtitles()
                }
            }
            .show()
    }

    private fun searchOnlineSubtitles() {
        val apiKey = prefs.openSubtitlesApiKey
        if (apiKey.isBlank()) {
            android.widget.Toast.makeText(this,
                "Set your OpenSubtitles API key in Settings > Content",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }

        val (cleanTitle, extractedYear) = OpenSubtitlesClient.cleanTitle(channelName)
        val year = contentYear.ifBlank { extractedYear }
        val searchLang = prefs.preferredSubtitleLanguage.ifBlank {
            prefs.appLanguage.ifBlank { null }
        }

        val progress = android.app.ProgressDialog(this).apply {
            setMessage("Searching subtitles...")
            setCancelable(true)
            show()
        }

        scope.launch {
            val client = OpenSubtitlesClient(apiKey)
            val results = client.search(cleanTitle, year, searchLang)

            progress.dismiss()

            if (results.isEmpty()) {
                // Retry without language filter
                val allResults = if (searchLang != null) {
                    client.search(cleanTitle, year)
                } else emptyList()

                if (allResults.isEmpty()) {
                    android.widget.Toast.makeText(this@IPTVPlayerActivity,
                        "No subtitles found for \"$cleanTitle\"",
                        android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                showSubtitleResults(allResults, client)
            } else {
                showSubtitleResults(results, client)
            }
        }
    }

    private fun showSubtitleResults(
        results: List<com.vistacore.launcher.iptv.SubtitleResult>,
        client: OpenSubtitlesClient
    ) {
        // Group by language, sort by download count
        val sorted = results.sortedByDescending { it.downloadCount }
        val labels = sorted.map { result ->
            val langName = java.util.Locale(result.language).displayLanguage
                .replaceFirstChar { it.uppercase() }
            "$langName — ${result.title}"
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Online Subtitles")
            .setItems(labels) { _, which ->
                downloadAndApplySubtitle(sorted[which], client)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadAndApplySubtitle(
        result: com.vistacore.launcher.iptv.SubtitleResult,
        client: OpenSubtitlesClient
    ) {
        val progress = android.app.ProgressDialog(this).apply {
            setMessage("Downloading subtitle...")
            setCancelable(false)
            show()
        }

        scope.launch {
            val path = client.download(result.fileId, cacheDir)
            progress.dismiss()

            if (path == null) {
                android.widget.Toast.makeText(this@IPTVPlayerActivity,
                    "Failed to download subtitle",
                    android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }

            loadExternalSubtitle(path, result.language)
        }
    }

    private fun loadExternalSubtitle(srtPath: String, languageCode: String) {
        val exo = player ?: return
        val position = exo.currentPosition

        val subtitleUri = android.net.Uri.fromFile(java.io.File(srtPath))
        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(languageCode)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setSubtitleConfigurations(listOf(subtitleConfig))
            .build()

        exo.setMediaItem(mediaItem)
        exo.prepare()
        exo.seekTo(position)
        exo.playWhenReady = true

        // Enable subtitle rendering
        trackSelector?.setParameters(
            trackSelector!!.buildUponParameters()
                .setRendererDisabled(C.TRACK_TYPE_TEXT, false)
        )

        android.widget.Toast.makeText(this, "Subtitles loaded", android.widget.Toast.LENGTH_SHORT).show()
    }

    // --- Key Handling ---

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isVodMode && numberPad?.handleKeyEvent(keyCode) == true) {
            return true
        }

        // Audio/subtitle shortcuts — work in both modes
        when (keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> { showAudioTrackPicker(); return true }
            KeyEvent.KEYCODE_PROG_GREEN -> { showSubtitlePicker(); return true }
        }

        // VOD mode key handling
        if (isVodMode) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (scrubVisible) {
                        hideScrubBar()
                        true
                    } else {
                        finish()
                        true
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (scrubVisible) {
                        return super.onKeyDown(keyCode, event)
                    }
                    showScrubBar()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (scrubVisible) {
                        // If seekbar is focused, let it handle the key (moves the dot)
                        if (binding.scrubSeekbar.hasFocus()) {
                            resetScrubTimer()
                            return super.onKeyDown(keyCode, event)
                        }
                        return super.onKeyDown(keyCode, event)
                    }
                    // Quick skip -10s and show scrub bar
                    seekBy(-SEEK_INCREMENT_MS)
                    showScrubBar()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (scrubVisible) {
                        if (binding.scrubSeekbar.hasFocus()) {
                            resetScrubTimer()
                            return super.onKeyDown(keyCode, event)
                        }
                        return super.onKeyDown(keyCode, event)
                    }
                    seekBy(SEEK_INCREMENT_MS)
                    showScrubBar()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (scrubVisible) {
                        // Move focus to seekbar for scrubbing
                        binding.scrubSeekbar.requestFocus()
                        resetScrubTimer()
                        true
                    } else {
                        showScrubBar()
                        true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (scrubVisible) {
                        // Move focus from seekbar to buttons
                        binding.scrubPlayPause.requestFocus()
                        resetScrubTimer()
                        true
                    } else {
                        showScrubBar()
                        true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    player?.let {
                        if (it.isPlaying) it.pause() else it.play()
                        if (scrubVisible) {
                            binding.scrubPlayPause.setImageResource(
                                if (it.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                            )
                        }
                    }
                    true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seekBy(-SEEK_INCREMENT_MS)
                    showScrubBar()
                    true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekBy(SEEK_INCREMENT_MS)
                    showScrubBar()
                    true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        // Live TV mode key handling
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (controlsVisible) {
                    hideControls()
                    true
                } else if (!backPressedOnce) {
                    backPressedOnce = true
                    Toast.makeText(this, "Press back again to exit", Toast.LENGTH_LONG).show()
                    handler.postDelayed({ backPressedOnce = false }, 3000)
                    true
                } else {
                    finish()
                    true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (controlsVisible) {
                    return super.onKeyDown(keyCode, event)
                }
                showControls()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (controlsVisible) {
                    return super.onKeyDown(keyCode, event)
                }
                showControls()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (controlsVisible) {
                    hideControls()
                } else {
                    switchToNextChannel()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (controlsVisible) {
                    hideControls()
                } else {
                    switchToPreviousChannel()
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                true
            }
            KeyEvent.KEYCODE_PROG_BLUE, KeyEvent.KEYCODE_MENU -> {
                enterPipMode()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVodMode && isSeeking) {
            stopFastSeek()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    fun switchStream(url: String, name: String, id: String) {
        streamUrl = url
        channelName = name
        channelId = id

        if (id.isNotBlank()) recentChannels.addRecent(id)

        player?.let { exo ->
            playStream(exo, url)
        }

        showChannelOverlay()
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
        if (!isInPipMode) player?.pause()
    }

    private fun saveCurrentPosition() {
        val exo = player ?: return
        val pos = exo.currentPosition
        val dur = exo.duration
        if (pos > 0 && dur > 0 && streamUrl.isNotBlank()) {
            val logoUrl = intent.getStringExtra(EXTRA_CHANNEL_LOGO) ?: ""
            watchHistory.savePosition(streamUrl, channelName, logoUrl, pos, dur)
        }
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        numberPad?.destroy()
        player?.release()
        player = null
    }
}
