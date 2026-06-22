package com.vistacore.launcher.ui

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.vistacore.launcher.R
import com.vistacore.launcher.data.ContentCache
import com.vistacore.launcher.data.WatchHistoryManager
import com.vistacore.launcher.iptv.Channel
import com.vistacore.launcher.iptv.ContentType
import com.vistacore.launcher.iptv.ProviderText
import com.vistacore.launcher.system.ChannelUpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Live / Movies / Shows pulled from cache, ready for any home layout. */
data class HomeData(
    val live: List<Channel>,
    val movies: List<Channel>,
    val shows: List<Channel>,
)

object HomeContentLoader {
    suspend fun load(ctx: Context): HomeData = withContext(Dispatchers.IO) {
        val live = (ChannelUpdateWorker.getCachedChannels(ctx) ?: emptyList())
            .filter { it.contentType == ContentType.LIVE }
            .sortedBy { it.number }
        val movies = ContentCache.movieItems
            ?: ChannelUpdateWorker.getCachedMovies(ctx) ?: emptyList()
        val rawShows = ContentCache.showItems
            ?: ChannelUpdateWorker.getCachedSeries(ctx) ?: emptyList()
        val seen = HashSet<String>()
        val shows = rawShows.filter { seen.add(ContentOpener.showNameFor(it)) }
        HomeData(live, movies, shows)
    }
}

/**
 * One tile for the new home layouts. Carries its own "open" action and its
 * preview info so a single adapter serves Live / Movies / Shows / Apps.
 */
data class HomeTile(
    val title: String,
    val subtitle: String,
    val category: String,
    val imageUrl: String?,
    val iconRes: Int,
    val iconOnly: Boolean,    // true for app icons (center-inside, no crop)
    val cropToFill: Boolean,  // true for posters (fill+crop); false for logos
    val accentColorRes: Int,  // per-row accent (monogram block, no-art tiles)
    val open: () -> Unit,
)

/** Build the tile groups shared by the home layouts. */
object HomeTiles {

    /** Resume row — what the user was last watching. Empty when no history. */
    fun continueWatching(activity: Activity, max: Int = 30): List<HomeTile> {
        val cat = activity.getString(R.string.home_continue)
        return WatchHistoryManager(activity).getContinueWatching().take(max).map { e ->
            HomeTile(
                title = ProviderText.cleanName(e.name),
                subtitle = e.displayRemaining,
                category = cat,
                imageUrl = e.logoUrl.ifBlank { null },
                iconRes = R.drawable.ic_movies,
                iconOnly = false,
                cropToFill = true,
                accentColorRes = R.color.nf_accent_continue,
                open = { ContentOpener.resumeWatch(activity, e) }
            )
        }
    }

    fun live(activity: Activity, channels: List<Channel>, max: Int = 150): List<HomeTile> {
        val cat = activity.getString(R.string.home_lane_live)
        return channels.take(max).map { ch ->
            val epg = ContentCache.epgData
            val now = epg?.getNowPlaying(ch.epgId.ifBlank { ch.id }) ?: epg?.getNowPlaying(ch.name)
            val nowTitle = now?.title?.takeIf { it.isNotBlank() }?.let { ProviderText.cleanDisplay(it) }
            HomeTile(
                title = ProviderText.cleanName(ch.name),
                subtitle = nowTitle ?: activity.getString(R.string.home_live_now),
                category = cat,
                imageUrl = ch.logoUrl.ifBlank { null },
                iconRes = R.drawable.ic_iptv,
                iconOnly = false,
                cropToFill = false,   // channel logos look best centered, not cropped
                accentColorRes = R.color.nf_accent_live,
                open = { ContentOpener.openLiveChannel(activity, ch) }
            )
        }
    }

    fun movies(activity: Activity, channels: List<Channel>, max: Int = 150): List<HomeTile> {
        val cat = activity.getString(R.string.home_lane_movies)
        return channels.take(max).map { ch ->
            val yr = if (ch.year > 0) ch.year.toString()
            else Regex("""\((\d{4})\)""").find(ch.name)?.groupValues?.get(1) ?: ""
            val category = ProviderText.cleanCategory(ch.category)
            HomeTile(
                title = ProviderText.cleanName(ch.name),
                subtitle = if (yr.isNotBlank()) "$yr · $category" else category,
                category = cat,
                imageUrl = ch.logoUrl.ifBlank { null }, iconRes = R.drawable.ic_movies,
                iconOnly = false, cropToFill = true,
                accentColorRes = R.color.nf_accent_movies,
                open = { ContentOpener.openMovie(activity, ch) }
            )
        }
    }

    fun shows(
        activity: Activity,
        scope: kotlinx.coroutines.CoroutineScope,
        channels: List<Channel>,
        onLoading: (Boolean) -> Unit,
        onError: () -> Unit,
        max: Int = 150,
    ): List<HomeTile> {
        val cat = activity.getString(R.string.home_lane_shows)
        return channels.take(max).map { ch ->
            HomeTile(
                title = ProviderText.cleanName(ContentOpener.showNameFor(ch)),
                subtitle = ProviderText.cleanCategory(ch.category), category = cat,
                imageUrl = ch.logoUrl.ifBlank { null }, iconRes = R.drawable.ic_tv_shows,
                iconOnly = false, cropToFill = true,
                accentColorRes = R.color.nf_accent_shows,
                open = { ContentOpener.openShow(activity, scope, ch, onLoading, onError) }
            )
        }
    }

    fun apps(activity: Activity): List<HomeTile> {
        val cat = activity.getString(R.string.section_all_apps)
        return AppShortcuts.extraApps(activity).map { app ->
            HomeTile(
                title = app.label, subtitle = "", category = cat,
                imageUrl = null, iconRes = app.iconRes, iconOnly = true, cropToFill = false,
                accentColorRes = R.color.nf_accent_apps,
                open = { AppShortcuts.launch(activity, app) }
            )
        }
    }
}

/** Single adapter for all tile rows. [onFocus] fires when a tile gains focus. */
class HomeTileAdapter(
    private val tiles: List<HomeTile>,
    private val onFocus: (HomeTile) -> Unit = {},
) : RecyclerView.Adapter<HomeTileAdapter.VH>() {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: View = itemView.findViewById(R.id.nf_card)
        val art: FrameLayout = itemView.findViewById(R.id.nf_art)
        val image: ImageView = itemView.findViewById(R.id.nf_image)
        val monogram: TextView = itemView.findViewById(R.id.nf_monogram)
        val label: TextView = itemView.findViewById(R.id.nf_label)

        init {
            // Round the poster/logo art together with the title bar.
            card.clipToOutline = true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nf_card, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tile = tiles[position]
        val ctx = holder.itemView.context
        val accent = ContextCompat.getColor(ctx, tile.accentColorRes)
        holder.label.text = tile.title
        // Stash the position so the home activity's D-pad containment can tell a
        // true row edge from a partially-scrolled one without relying on
        // getChildAdapterPosition, which reads NO_POSITION mid-scroll. The list
        // never mutates, so the bind-time position stays correct.
        holder.itemView.setTag(R.id.tile_position_tag, position)

        when {
            // App shortcut: brand icon centered on a neutral surface.
            tile.iconOnly -> {
                showImage(holder, ImageView.ScaleType.CENTER_INSIDE, R.color.nf_surface)
                holder.image.setPadding(28, 28, 28, 28)
                holder.image.setImageResource(tile.iconRes)
            }
            // Has artwork: poster fills; logo sits on a cream chip. On load
            // failure we fall back to a readable monogram, never an empty box.
            tile.imageUrl != null -> {
                holder.image.setPadding(0, 0, 0, 0)
                if (tile.cropToFill) {
                    showImage(holder, ImageView.ScaleType.CENTER_CROP, R.color.nf_surface)
                } else {
                    showImage(holder, ImageView.ScaleType.CENTER_INSIDE, R.color.nf_chip)
                    holder.image.setPadding(16, 16, 16, 16)
                }
                Glide.with(ctx).load(tile.imageUrl)
                    .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?, model: Any?,
                            target: Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean
                        ): Boolean {
                            showMonogram(holder, tile, accent); return true
                        }
                        override fun onResourceReady(
                            resource: android.graphics.drawable.Drawable, model: Any,
                            target: Target<android.graphics.drawable.Drawable>?,
                            dataSource: DataSource, isFirstResource: Boolean
                        ): Boolean = false
                    })
                    .into(holder.image)
            }
            // No artwork at all: big colored monogram text-card.
            else -> showMonogram(holder, tile, accent)
        }

        holder.itemView.setOnClickListener { tile.open() }
        holder.itemView.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onFocus(tile) }
        // Row-boundary focus containment (don't let LEFT/RIGHT escape the row) is
        // handled centrally in HomeSimpleRowsActivity.dispatchKeyEvent — doing it
        // there beats a per-tile OnKeyListener, which had a timing hole at the
        // boundary during fast key-repeat and let focus jump to the top bar.
    }

    private fun showImage(holder: VH, scale: ImageView.ScaleType, bgColorRes: Int) {
        holder.monogram.visibility = View.GONE
        holder.image.visibility = View.VISIBLE
        holder.image.scaleType = scale
        holder.art.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, bgColorRes))
    }

    private fun showMonogram(holder: VH, tile: HomeTile, accent: Int) {
        holder.image.visibility = View.GONE
        holder.monogram.visibility = View.VISIBLE
        holder.monogram.text = monogramOf(tile.title)
        holder.art.setBackgroundColor(accent)
    }

    private fun monogramOf(t: String): String {
        val words = t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            words.size >= 2 -> (words[0].take(1) + words[1].take(1)).uppercase()
            words.size == 1 -> words[0].filter { it.isLetterOrDigit() }.take(4).uppercase().ifBlank { "★" }
            else -> "★"
        }
    }

    override fun getItemCount() = tiles.size
}
