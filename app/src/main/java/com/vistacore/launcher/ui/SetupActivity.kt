package com.vistacore.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vistacore.launcher.R
import com.vistacore.launcher.data.ContentCache
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.databinding.ActivitySetupBinding
import com.vistacore.launcher.iptv.*
import com.vistacore.launcher.system.ChannelUpdateWorker
import kotlinx.coroutines.*

class SetupActivity : BaseActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: PrefsManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)
        hadConfigBefore = prefs.hasIptvConfig()

        // Source type toggle
        binding.setupSourceGroup.setOnCheckedChangeListener { _, checkedId ->
            val isM3u = checkedId == R.id.setup_radio_m3u
            binding.setupM3uSection.visibility = if (isM3u) View.VISIBLE else View.GONE
            binding.setupXtreamSection.visibility = if (isM3u) View.GONE else View.VISIBLE
        }

        // Pre-fill if credentials already exist
        binding.setupM3uUrl.setText(prefs.m3uUrl)
        binding.setupXtreamServer.setText(prefs.xtreamServer)
        binding.setupXtreamUser.setText(prefs.xtreamUsername)
        binding.setupXtreamPass.setText(prefs.xtreamPassword)
        binding.setupEpgUrl.setText(prefs.epgUrl)

        // Skip button
        binding.setupBtnSkip.setOnClickListener {
            prefs.setSetupComplete()
            goToMain()
        }
        binding.setupBtnSkip.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }

        // Connect button
        binding.setupBtnConnect.setOnClickListener { startSetup() }
        binding.setupBtnConnect.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }

        // Remote Setup button
        binding.setupBtnRemote.setOnClickListener {
            startActivity(Intent(this, RemoteHelpActivity::class.java))
        }
        binding.setupBtnRemote.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    private var hadConfigBefore = false

    override fun onResume() {
        super.onResume()
        // Check if Remote Help pushed credentials while we were away
        val hasConfigNow = prefs.hasIptvConfig()
        if (hasConfigNow && !hadConfigBefore) {
            // Credentials were just added by Remote Help — pre-fill and auto-start
            binding.setupM3uUrl.setText(prefs.m3uUrl)
            binding.setupXtreamServer.setText(prefs.xtreamServer)
            binding.setupXtreamUser.setText(prefs.xtreamUsername)
            binding.setupXtreamPass.setText(prefs.xtreamPassword)
            binding.setupEpgUrl.setText(prefs.epgUrl)

            if (prefs.sourceType == PrefsManager.SOURCE_XTREAM) {
                binding.setupRadioXtream.isChecked = true
            } else {
                binding.setupRadioM3u.isChecked = true
            }

            android.widget.Toast.makeText(this, "Credentials received! Starting download…", android.widget.Toast.LENGTH_SHORT).show()
            startSetup()
        }
        hadConfigBefore = hasConfigNow
    }

    private fun startSetup() {
        // Save credentials
        val isM3u = binding.setupRadioM3u.isChecked
        if (isM3u) {
            val url = binding.setupM3uUrl.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "Please enter a playlist URL", Toast.LENGTH_SHORT).show()
                return
            }
            prefs.sourceType = PrefsManager.SOURCE_M3U
            prefs.m3uUrl = url
        } else {
            val server = binding.setupXtreamServer.text.toString().trim()
            if (server.isBlank()) {
                Toast.makeText(this, "Please enter a server URL", Toast.LENGTH_SHORT).show()
                return
            }
            prefs.sourceType = PrefsManager.SOURCE_XTREAM
            prefs.xtreamServer = server
            prefs.xtreamUsername = binding.setupXtreamUser.text.toString().trim()
            prefs.xtreamPassword = binding.setupXtreamPass.text.toString().trim()
        }
        prefs.epgUrl = binding.setupEpgUrl.text.toString().trim()

        // Disable inputs
        binding.setupBtnConnect.isEnabled = false
        binding.setupBtnSkip.isEnabled = false
        binding.setupProgress.visibility = View.VISIBLE
        binding.setupStatus.visibility = View.VISIBLE

        // Start download
        scope.launch {
            try {
                updateProgress("Connecting to server…", 10)

                val allChannels = when (prefs.sourceType) {
                    PrefsManager.SOURCE_M3U -> withContext(Dispatchers.IO) {
                        M3UParser().parse(prefs.m3uUrl)
                    }
                    PrefsManager.SOURCE_XTREAM -> {
                        val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                        val xc = XtreamClient(auth)

                        updateProgress("Loading live channels…", 20)
                        val live = withContext(Dispatchers.IO) { xc.getChannels() }

                        updateProgress("Loading movies… (this takes a minute)", 35)
                        val movies = try { withContext(Dispatchers.IO) { xc.getMovies() } } catch (_: Exception) { emptyList() }

                        updateProgress("Loading TV shows…", 55)
                        val series = try { withContext(Dispatchers.IO) { xc.getSeries() } } catch (_: Exception) { emptyList() }

                        live + movies + series
                    }
                    else -> emptyList()
                }

                val live = allChannels.count { it.contentType == ContentType.LIVE }
                val movies = allChannels.count { it.contentType == ContentType.MOVIE }
                val series = allChannels.count { it.contentType == ContentType.SERIES }

                if (live == 0 && movies == 0 && series == 0) {
                    updateProgress("No channels found. Check your URL.", 0)
                    binding.setupBtnConnect.isEnabled = true
                    binding.setupBtnSkip.isEnabled = true
                    return@launch
                }

                updateProgress("Found $live channels, $movies movies, $series shows", 70)

                // Cache everything
                updateProgress("Saving content…", 75)
                withContext(Dispatchers.IO) {
                    ChannelUpdateWorker.cacheChannels(this@SetupActivity, allChannels)
                }

                // Schedule auto-update
                if (prefs.autoUpdateEnabled) {
                    ChannelUpdateWorker.schedule(this@SetupActivity)
                }

                updateProgress("Almost ready…", 80)

                // Preload EPG if configured
                val epgUrl = prefs.epgUrl
                if (epgUrl.isNotBlank()) {
                    try {
                        updateProgress("Loading TV guide…", 85)
                        val epg = withContext(Dispatchers.IO) { EpgParser().parse(epgUrl) }
                        ContentCache.epgData = epg
                        ContentCache.epgLoadTime = System.currentTimeMillis()
                    } catch (_: Exception) {
                        // EPG is optional
                    }
                }

                updateProgress("All set! $live channels, $movies movies, $series shows", 100)
                delay(1500)

                prefs.setSetupComplete()
                goToMain()

            } catch (e: Exception) {
                val detail = e.cause?.message ?: e.message ?: "Unknown error"
                updateProgress("Connection failed: $detail", 0)
                binding.setupBtnConnect.isEnabled = true
                binding.setupBtnSkip.isEnabled = true
                // Focus the Connect button so seniors know to press it again
                binding.setupBtnConnect.requestFocus()
                Toast.makeText(this@SetupActivity,
                    "Check your settings and press Connect to try again",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateProgress(text: String, percent: Int) {
        binding.setupStatus.text = text
        android.animation.ObjectAnimator.ofInt(binding.setupProgress, "progress", percent)
            .setDuration(400)
            .start()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
