package com.vistacore.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vistacore.launcher.R
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.databinding.ActivityEpgGuideBinding
import com.vistacore.launcher.databinding.ItemEpgRowBinding
import com.vistacore.launcher.iptv.*
import com.vistacore.launcher.system.ChannelUpdateWorker
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class EpgGuideActivity : BaseActivity() {

    private lateinit var binding: ActivityEpgGuideBinding
    private lateinit var prefs: PrefsManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var epgData: EpgData? = null
    private var channels: List<Channel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpgGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)

        buildTimelineHeader()
        loadData()
    }

    private fun buildTimelineHeader() {
        val header = binding.timelineHeader
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val cal = Calendar.getInstance()

        // Round to current half hour
        val minute = cal.get(Calendar.MINUTE)
        cal.set(Calendar.MINUTE, if (minute < 30) 0 else 30)
        cal.set(Calendar.SECOND, 0)

        // Show 6 hours of time slots
        for (i in 0 until 12) {
            val tv = TextView(this).apply {
                text = timeFormat.format(cal.time)
                textSize = 16f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(16, 0, 16, 0)
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(120), LinearLayout.LayoutParams.MATCH_PARENT
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // Highlight current time slot
            if (i == 0) {
                tv.setTextColor(getColor(R.color.accent_gold))
                tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            header.addView(tv)
            cal.add(Calendar.MINUTE, 30)
        }
    }

    private fun loadData() {
        showLoading(true)

        scope.launch {
            try {
                // Load channels from cache first, fall back to network
                val cached = ChannelUpdateWorker.getCachedChannels(this@EpgGuideActivity)
                channels = if (cached != null && cached.isNotEmpty()) {
                    cached
                } else {
                    withContext(Dispatchers.IO) {
                        when (prefs.sourceType) {
                            PrefsManager.SOURCE_M3U -> M3UParser().parse(prefs.m3uUrl)
                            PrefsManager.SOURCE_XTREAM -> {
                                val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                                XtreamClient(auth).getChannels()
                            }
                            else -> emptyList()
                        }
                    }
                }

                // Filter to live channels only
                channels = channels.filter { it.contentType == ContentType.LIVE }

                // Use cached EPG if available and recent (< 30 min old)
                val cachedEpg = com.vistacore.launcher.data.ContentCache.epgData
                val epgAge = System.currentTimeMillis() - com.vistacore.launcher.data.ContentCache.epgLoadTime
                if (cachedEpg != null && cachedEpg.programs.isNotEmpty() && epgAge < 30 * 60 * 1000L) {
                    epgData = cachedEpg
                    android.util.Log.d("EpgGuide", "Using cached EPG: ${cachedEpg.channels.size} channels, ${cachedEpg.programs.size} programs")
                } else {
                    // Try Xtream native EPG first
                    if (prefs.sourceType == PrefsManager.SOURCE_XTREAM && prefs.xtreamServer.isNotBlank()) {
                        try {
                            val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                            val xc = XtreamClient(auth)
                            val epg = withContext(Dispatchers.IO) { xc.getEpg(channels) }
                            if (epg.programs.isNotEmpty()) {
                                epgData = epg
                                com.vistacore.launcher.data.ContentCache.epgData = epg
                                com.vistacore.launcher.data.ContentCache.epgLoadTime = System.currentTimeMillis()
                                android.util.Log.d("EpgGuide", "Xtream EPG: ${epg.programs.size} programs")
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("EpgGuide", "Xtream native EPG failed: ${e.message}")
                        }
                    }

                    // Fallback to XMLTV if Xtream EPG was empty/failed
                    if (epgData == null || epgData?.programs?.isEmpty() == true) {
                        var epgUrl = prefs.epgUrl
                        if (epgUrl.isBlank() && prefs.sourceType == PrefsManager.SOURCE_XTREAM &&
                            prefs.xtreamServer.isNotBlank()) {
                            val s = prefs.xtreamServer.trimEnd('/')
                            epgUrl = "$s/xmltv.php?username=${prefs.xtreamUsername}&password=${prefs.xtreamPassword}"
                        }
                        if (epgUrl.isNotBlank()) {
                            android.util.Log.d("EpgGuide", "Trying XMLTV: $epgUrl")
                            val xmltv = withContext(Dispatchers.IO) { EpgParser().parse(epgUrl) }
                            if (xmltv.programs.isNotEmpty()) {
                                epgData = xmltv
                                com.vistacore.launcher.data.ContentCache.epgData = xmltv
                                com.vistacore.launcher.data.ContentCache.epgLoadTime = System.currentTimeMillis()
                            }
                        }
                    }
                }

                if (channels.isEmpty()) {
                    showEmpty()
                    return@launch
                }

                showGuide()

            } catch (e: Exception) {
                android.util.Log.e("EpgGuide", "Failed to load EPG data", e)
                showEmpty()
            }
        }
    }

    private fun showGuide() {
        showLoading(false)
        binding.epgList.layoutManager = LinearLayoutManager(this)
        binding.epgList.adapter = EpgRowAdapter(channels, epgData) { channel ->
            val intent = Intent(this, IPTVPlayerActivity::class.java).apply {
                putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, channel.logoUrl)
            }
            startActivity(intent)
        }
    }

    private fun showLoading(show: Boolean) {
        binding.epgLoading.visibility = if (show) View.VISIBLE else View.GONE
        binding.epgList.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun showEmpty() {
        showLoading(false)
        binding.epgList.visibility = View.GONE
        binding.epgEmpty.visibility = View.VISIBLE
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

// --- EPG Row Adapter ---

class EpgRowAdapter(
    private val channels: List<Channel>,
    private val epgData: EpgData?,
    private val onChannelClick: (Channel) -> Unit
) : RecyclerView.Adapter<EpgRowAdapter.VH>() {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val pixelsPerMinute = 4 // Width scaling for program blocks

    inner class VH(private val binding: ItemEpgRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel) {
            binding.epgChannelName.text = channel.name

            if (channel.logoUrl.isNotBlank()) {
                Glide.with(binding.root.context)
                    .load(channel.logoUrl)
                    .placeholder(R.drawable.ic_iptv)
                    .into(binding.epgChannelLogo)
            } else {
                binding.epgChannelLogo.setImageResource(R.drawable.ic_iptv)
            }

            // Build program blocks
            binding.epgProgramsRow.removeAllViews()
            val context = binding.root.context
            val density = context.resources.displayMetrics.density

            val programs = epgData?.getTodaySchedule(channel.epgId.ifBlank { channel.id })
                ?: epgData?.getTodaySchedule(channel.name)

            if (programs.isNullOrEmpty()) {
                // No EPG data — show single "No Info" block
                val noInfo = TextView(context).apply {
                    text = "No guide info"
                    textSize = 16f
                    setTextColor(context.getColor(R.color.text_hint))
                    setPadding(16, 0, 16, 0)
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        (300 * density).toInt(),
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                }
                binding.epgProgramsRow.addView(noInfo)
            } else {
                for (program in programs) {
                    val widthDp = (program.durationMinutes * pixelsPerMinute).coerceAtLeast(80)
                    val widthPx = (widthDp * density).toInt()

                    val programView = LayoutInflater.from(context)
                        .inflate(R.layout.item_epg_program, binding.epgProgramsRow, false)

                    programView.layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                        marginEnd = (2 * density).toInt()
                    }

                    programView.findViewById<TextView>(R.id.program_title).text = program.title
                    programView.findViewById<TextView>(R.id.program_time).text =
                        "${timeFormat.format(program.startTime)} - ${timeFormat.format(program.endTime)}"

                    // Highlight live programs
                    if (program.isLive) {
                        programView.setBackgroundResource(R.drawable.epg_program_live)
                        val progressBar = programView.findViewById<View>(R.id.program_progress)
                        progressBar.visibility = View.VISIBLE
                        // Set progress width proportionally
                        progressBar.post {
                            val params = progressBar.layoutParams
                            params.width = (programView.width * program.progress).toInt()
                            progressBar.layoutParams = params
                        }
                    }

                    programView.isFocusable = true
                    programView.setOnClickListener { onChannelClick(channel) }
                    programView.setOnFocusChangeListener { view, hasFocus ->
                        MainActivity.animateFocus(view, hasFocus)
                    }

                    binding.epgProgramsRow.addView(programView)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemEpgRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(channels[position])
    override fun getItemCount() = channels.size
}
