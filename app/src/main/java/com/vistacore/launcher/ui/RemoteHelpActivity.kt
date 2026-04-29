package com.vistacore.launcher.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.vistacore.launcher.R
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.databinding.ActivityRemoteHelpBinding
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Remote Help Mode — allows a Technical Provider to remotely configure
 * IPTV settings by generating/entering a pairing code.
 *
 * How to set up:
 *   1. Deploy the relay server (server/ folder) to any Node.js host
 *   2. Enter the relay server URL here
 *   3. Share the code with your Technical Provider
 *   4. They open the relay URL in a browser, enter the code + config
 *   5. Press "Wait for Config" and it auto-applies
 *
 * Without a relay server, supports manual JSON paste.
 */
class RemoteHelpActivity : BaseActivity() {

    private lateinit var binding: ActivityRemoteHelpBinding
    private lateinit var prefs: PrefsManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var pairingCode: String = ""
    private var listeningJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)

        generatePairingCode()
        setupButtons()
        loadRelayUrl()

        // Auto-start listening if a relay URL is configured
        if (prefs.relayServerUrl.isNotBlank()) {
            startListeningForConfig()
        }
    }

    private fun generatePairingCode() {
        pairingCode = (100000..999999).random().toString()
        binding.pairingCode.text = pairingCode
        binding.pairingCodeFormatted.text = "${pairingCode.substring(0, 3)} ${pairingCode.substring(3)}"
    }

    private fun loadRelayUrl() {
        val url = prefs.relayServerUrl
        binding.inputRelayUrl.setText(url)
        updateRelayDisplay(url)
    }

    private fun updateRelayDisplay(url: String) {
        if (url.isNotBlank()) {
            binding.relayUrlDisplay.text = "Technical Provider opens: $url"
            binding.relayUrlDisplay.visibility = View.VISIBLE
        } else {
            binding.relayUrlDisplay.text = "No relay server set. Set one below or use manual paste."
            binding.relayUrlDisplay.visibility = View.VISIBLE
        }
    }

    private fun setupButtons() {
        binding.btnRefreshCode.setOnClickListener {
            generatePairingCode()
            Toast.makeText(this, "New code generated", Toast.LENGTH_SHORT).show()
        }

        binding.btnApplyManual.setOnClickListener {
            applyManualConfig()
        }

        binding.btnStartListening.setOnClickListener {
            startListeningForConfig()
        }

        binding.btnSaveRelay.setOnClickListener {
            val url = binding.inputRelayUrl.text.toString().trim()
            prefs.relayServerUrl = url
            updateRelayDisplay(url)
            Toast.makeText(this, "Relay URL saved", Toast.LENGTH_SHORT).show()
        }

        // Focus animations
        listOf(
            binding.btnRefreshCode, binding.btnApplyManual,
            binding.btnStartListening, binding.btnSaveRelay
        ).forEach { btn ->
            btn.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
        }
    }

    private fun applyManualConfig() {
        val json = binding.inputManualConfig.text.toString().trim()
        if (json.isBlank()) {
            Toast.makeText(this, "Please paste the config JSON", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val config = Gson().fromJson(json, RemoteConfig::class.java)
            applyConfig(config)
            Toast.makeText(this, "Configuration applied!", Toast.LENGTH_LONG).show()
            binding.statusText.text = "Configuration applied!"
            binding.statusText.setTextColor(getColor(R.color.status_online))
            binding.statusText.visibility = View.VISIBLE
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid config format", Toast.LENGTH_SHORT).show()
            binding.statusText.text = "Invalid config format. Check the JSON."
            binding.statusText.setTextColor(getColor(R.color.status_offline))
            binding.statusText.visibility = View.VISIBLE
        }
    }

    private fun startListeningForConfig() {
        val relayUrl = prefs.relayServerUrl
        if (relayUrl.isBlank()) {
            Toast.makeText(this, "Enter a relay server URL first, or use manual paste.", Toast.LENGTH_LONG).show()
            return
        }

        // Cancel any existing listener before starting a new one
        listeningJob?.cancel()

        binding.statusText.text = "Waiting for your Technical Provider to send config…"
        binding.statusText.setTextColor(getColor(R.color.accent_gold))
        binding.statusText.visibility = View.VISIBLE
        binding.listeningProgress.visibility = View.VISIBLE
        binding.btnStartListening.isEnabled = false

        listeningJob = scope.launch {
            val client = com.vistacore.launcher.iptv.TlsCompat.apply(OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS))
                .build()

            var attempts = 0
            val maxAttempts = 60 // 5 minutes at 5-second intervals

            while (attempts < maxAttempts) {
                try {
                    val config = withContext(Dispatchers.IO) {
                        val url = "${relayUrl.trimEnd('/')}/pair/$pairingCode"
                        val request = Request.Builder().url(url).build()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val body = response.body?.string()
                                if (!body.isNullOrBlank() && body != "null") {
                                    Gson().fromJson(body, RemoteConfig::class.java)
                                } else null
                            } else null
                        }
                    }

                    if (config != null) {
                        applyConfig(config)
                        binding.listeningProgress.visibility = View.GONE
                        binding.btnStartListening.isEnabled = true
                        binding.statusText.text = "Configuration received and applied!"
                        binding.statusText.setTextColor(getColor(R.color.status_online))
                        Toast.makeText(this@RemoteHelpActivity, "Configuration applied!", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                } catch (e: Exception) {
                    android.util.Log.e("RemoteHelp", "Poll failed: ${e.javaClass.simpleName}: ${e.message}")
                    binding.statusText.text = "Connecting… (${e.javaClass.simpleName}: ${e.message})"
                }

                delay(5000)
                attempts++
            }

            binding.listeningProgress.visibility = View.GONE
            binding.btnStartListening.isEnabled = true
            binding.statusText.text = "Timed out after 5 minutes. Try again."
            binding.statusText.setTextColor(getColor(R.color.status_offline))
        }
    }

    private fun applyConfig(config: RemoteConfig) {
        // Use the canonical source identity from PrefsManager so this path
        // sees the same set of fields the rest of the app uses (sourceType,
        // M3U URL, Xtream creds, Dispatcharr key, Jellyfin creds). The
        // local helper used to omit dispatcharr_api_key and Jellyfin —
        // remote-pushed key changes wouldn't trigger the cache wipe and
        // the user kept seeing the old VOD catalog.
        val beforeFingerprint = prefs.sourceIdentity()

        if (config.m3u_url?.isNotBlank() == true) {
            prefs.sourceType = PrefsManager.SOURCE_M3U
            prefs.m3uUrl = config.m3u_url
        }
        if (config.xtream_server?.isNotBlank() == true) {
            prefs.sourceType = PrefsManager.SOURCE_XTREAM
            prefs.xtreamServer = config.xtream_server
            prefs.xtreamUsername = config.xtream_username ?: ""
            prefs.xtreamPassword = config.xtream_password ?: ""
        }
        if (config.dispatcharr_api_key?.isNotBlank() == true) {
            prefs.dispatcharrApiKey = config.dispatcharr_api_key
        }
        if (config.epg_url?.isNotBlank() == true) {
            prefs.epgUrl = config.epg_url
        }

        // If anything that determines what we fetch changed, wipe cached
        // content and re-fetch. Otherwise the app keeps showing the old
        // provider's catalog.
        if (prefs.sourceIdentity() != beforeFingerprint) {
            com.vistacore.launcher.system.ChannelUpdateWorker.clearCachesAndRefresh(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

data class RemoteConfig(
    val m3u_url: String? = null,
    val epg_url: String? = null,
    val xtream_server: String? = null,
    val xtream_username: String? = null,
    val xtream_password: String? = null,
    val dispatcharr_api_key: String? = null
)
