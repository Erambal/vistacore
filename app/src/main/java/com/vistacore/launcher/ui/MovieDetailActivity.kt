package com.vistacore.launcher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.vistacore.launcher.data.PrefsManager
import com.vistacore.launcher.databinding.ActivityMovieDetailBinding
import com.vistacore.launcher.iptv.CastMember
import com.vistacore.launcher.iptv.TmdbClient
import com.vistacore.launcher.iptv.TmdbType
import com.vistacore.launcher.iptv.VodDetail
import com.vistacore.launcher.iptv.XtreamAuth
import com.vistacore.launcher.iptv.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rich detail screen for a movie. Shown before the player so the viewer
 * can read the plot, see the rating, browse cast, and optionally preview
 * the trailer. Play button launches [IPTVPlayerActivity] with the stream.
 *
 * If the Channel has no associated Xtream vod id (M3U-only providers), we
 * still render the baseline info we already know (title, poster, category)
 * and the Play button.
 */
class MovieDetailActivity : BaseActivity() {

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_POSTER = "poster"
        private const val EXTRA_STREAM_URL = "stream_url"
        private const val EXTRA_VOD_ID = "vod_id"
        private const val EXTRA_YEAR = "year"

        fun launch(
            activity: Activity,
            title: String,
            category: String,
            posterUrl: String,
            streamUrl: String,
            vodId: Int,
            year: String = ""
        ) {
            val intent = Intent(activity, MovieDetailActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CATEGORY, category)
                putExtra(EXTRA_POSTER, posterUrl)
                putExtra(EXTRA_STREAM_URL, streamUrl)
                putExtra(EXTRA_VOD_ID, vodId)
                putExtra(EXTRA_YEAR, year)
            }
            activity.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityMovieDetailBinding
    private lateinit var title: String
    private lateinit var streamUrl: String
    private var vodId: Int = 0
    private var year: String = ""
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMovieDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        title = intent.getStringExtra(EXTRA_TITLE) ?: "Movie"
        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: ""
        val posterUrl = intent.getStringExtra(EXTRA_POSTER) ?: ""
        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""
        vodId = intent.getIntExtra(EXTRA_VOD_ID, 0)
        year = intent.getStringExtra(EXTRA_YEAR) ?: ""

        binding.movieTitle.text = title
        binding.movieMeta.text = if (category.isNotBlank()) category else ""
        binding.movieMeta.visibility = if (category.isNotBlank()) View.VISIBLE else View.GONE

        if (posterUrl.isNotBlank()) {
            Glide.with(this).load(posterUrl).into(binding.moviePoster)
            Glide.with(this).load(posterUrl).into(binding.detailBackdrop)
        }

        // Baseline badges from year only (richer ones arrive after fetch).
        if (year.isNotBlank()) {
            DetailBinders.renderBadges(binding.movieBadges, "", "", "", year)
        }

        binding.moviePlayBtn.setOnClickListener { playNow() }
        binding.moviePlayBtn.requestFocus()

        binding.castList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        binding.trailerCloseBtn.setOnClickListener { hideFullscreenTrailer() }

        if (vodId > 0) loadMetadata()
    }

    override fun onBackPressed() {
        if (binding.trailerOverlay.visibility == View.VISIBLE) {
            hideFullscreenTrailer()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /** Resolved YouTube trailer id — set by loadMetadata once known. */
    private var resolvedTrailerId: String? = null

    private fun showFullscreenTrailer() {
        val id = resolvedTrailerId ?: return
        binding.trailerOverlay.visibility = View.VISIBLE
        TrailerPlayer.configureFullscreen(binding.trailerFullscreen, id)
        binding.trailerCloseBtn.requestFocus()
    }

    private fun hideFullscreenTrailer() {
        TrailerPlayer.stop(binding.trailerFullscreen)
        binding.trailerOverlay.visibility = View.GONE
        binding.moviePlayBtn.requestFocus()
    }

    /**
     * Fetch rich metadata + TMDB cast. Silent on failure — the screen
     * stays on the baseline data we had from the grid.
     */
    private fun loadMetadata() {
        scope.launch {
            val detail = withContext(Dispatchers.IO) {
                try {
                    val prefs = PrefsManager(this@MovieDetailActivity)
                    val auth = XtreamAuth(prefs.xtreamServer, prefs.xtreamUsername, prefs.xtreamPassword)
                    XtreamClient(auth).getVodInfo(vodId)
                } catch (_: Exception) { null }
            } ?: return@launch

            applyDetail(detail)

            // Resolve a TMDB id once — used for both cast credits and
            // the trailer fallback when Xtream didn't hand us one.
            val tmdbIdResolved = withContext(Dispatchers.IO) {
                try {
                    val tmdb = TmdbClient(this@MovieDetailActivity)
                    detail.tmdbId.toIntOrNull()
                        ?: tmdb.searchId(title, detail.year.ifBlank { year }, TmdbType.MOVIE)
                } catch (_: Exception) { null }
            }

            val cast = if (tmdbIdResolved != null) {
                withContext(Dispatchers.IO) {
                    try {
                        TmdbClient(this@MovieDetailActivity).getCredits(tmdbIdResolved, TmdbType.MOVIE)
                    } catch (_: Exception) { emptyList() }
                }
            } else emptyList()

            val fallback = fallbackCast(detail.cast)
            val render = if (cast.isNotEmpty()) cast else fallback
            if (render.isNotEmpty()) {
                binding.castTitle.visibility = View.VISIBLE
                binding.castList.visibility = View.VISIBLE
                binding.castList.adapter = CastAdapter(render)
            }

            // Resolve a YouTube id for the trailer. Prefer whatever Xtream
            // returned (on `detail.trailer`), fall back to TMDB's /videos.
            val ytId = TrailerPlayer.extractId(detail.trailer) ?: run {
                if (tmdbIdResolved == null) null else withContext(Dispatchers.IO) {
                    try {
                        TmdbClient(this@MovieDetailActivity)
                            .getTrailerYoutubeId(tmdbIdResolved, TmdbType.MOVIE)
                    } catch (_: Exception) { null }
                }
            }
            if (!ytId.isNullOrBlank()) onTrailerResolved(ytId)
        }
    }

    /**
     * Wire up the trailer button for fullscreen playback, and kick off a
     * muted autoplay preview that fades in over the backdrop after a
     * short delay — Netflix-style ambient motion.
     */
    private fun onTrailerResolved(youtubeId: String) {
        resolvedTrailerId = youtubeId
        binding.movieTrailerBtn.visibility = View.VISIBLE
        binding.movieTrailerBtn.setOnClickListener { showFullscreenTrailer() }

        // Ambient autoplay over the backdrop image. Respects the user's
        // existing "Banner trailer autoplay" pref so they can turn it off
        // everywhere in one place.
        if (PrefsManager(this).bannerAutoplayTrailer) {
            binding.detailBackdropTrailer.visibility = View.VISIBLE
            TrailerPlayer.configureBackdropPreview(binding.detailBackdropTrailer, youtubeId)
            binding.detailBackdropTrailer.animate()
                .alpha(1f).setDuration(600).setStartDelay(1500).start()
        }
    }

    private fun applyDetail(d: VodDetail) {
        if (d.tagline.isNotBlank()) {
            binding.movieTagline.text = "\"${d.tagline}\""
            binding.movieTagline.visibility = View.VISIBLE
        }

        // Prominent "Rated R" line — users wanted this more visible than the
        // small pill badge. If the "Hide R-rated" toggle is on and the
        // rating is restricted, we also gate the Play button.
        val ratedLine = DetailBinders.formatRatedLine(d.mpaa)
        if (ratedLine != null) {
            binding.movieRatedLine.text = ratedLine
            binding.movieRatedLine.visibility = View.VISIBLE
        }
        val prefs = PrefsManager(this)
        if (prefs.hideRestrictedRatings && DetailBinders.isRestrictedRating(d.mpaa)) {
            binding.moviePlayBtn.isEnabled = false
            binding.moviePlayBtn.text = "Blocked (${d.mpaa})"
            binding.moviePlayBtn.alpha = 0.6f
        }

        DetailBinders.renderBadges(
            binding.movieBadges,
            rating = d.rating,
            mpaa = d.mpaa,
            runtime = DetailBinders.formatRuntime(d.duration, d.durationSecs, ""),
            year = d.year.ifBlank { year }
        )
        val meta = DetailBinders.buildMetaLine(d.genre, d.country, d.director)
        if (meta.isNotBlank()) {
            binding.movieMeta.text = meta
            binding.movieMeta.visibility = View.VISIBLE
        }
        if (d.plot.isNotBlank()) {
            binding.moviePlot.text = d.plot
            binding.moviePlot.visibility = View.VISIBLE
        } else binding.moviePlot.visibility = View.GONE

        if (d.backdropUrl.isNotBlank()) {
            Glide.with(this).load(d.backdropUrl).into(binding.detailBackdrop)
        }
        if (d.posterUrl.isNotBlank()) {
            Glide.with(this).load(d.posterUrl).into(binding.moviePoster)
        }

        val trailerUrl = resolveTrailerUrl(d.trailer)
        if (trailerUrl != null) {
            binding.movieTrailerBtn.visibility = View.VISIBLE
            binding.movieTrailerBtn.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trailerUrl)))
            }
        }
    }

    private fun fallbackCast(castStr: String): List<CastMember> {
        if (castStr.isBlank()) return emptyList()
        return castStr.split(Regex("""\s*,\s*"""))
            .mapNotNull { it.trim().ifBlank { null } }
            .take(12)
            .map { CastMember(name = it, character = "", profileUrl = "") }
    }

    private fun resolveTrailerUrl(raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return "https://www.youtube.com/watch?v=${Uri.encode(raw)}"
    }

    private fun playNow() {
        val intent = Intent(this, IPTVPlayerActivity::class.java).apply {
            putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, streamUrl)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, title)
            putExtra(IPTVPlayerActivity.EXTRA_IS_VOD, true)
            if (year.isNotBlank()) putExtra(IPTVPlayerActivity.EXTRA_CONTENT_YEAR, year)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }
}
