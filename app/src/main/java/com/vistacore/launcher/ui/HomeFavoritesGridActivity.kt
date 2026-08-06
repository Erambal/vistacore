package com.vistacore.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vistacore.launcher.R
import com.vistacore.launcher.data.FavoritesManager
import com.vistacore.launcher.iptv.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Home layout: the user's favourited Live TV channels as big squares, four across, with a
 * row of destination buttons (Search / Movies / TV Shows / Kids) on top.
 *
 * Built for a senior who watches a handful of channels and wants each one click away. The
 * grid shows only favourites — starred from the Live TV long-press menu — so there is no
 * scrolling through a guide to reach them.
 */
class HomeFavoritesGridActivity : BaseActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var grid: RecyclerView
    private lateinit var empty: View
    private lateinit var loading: View

    companion object {
        /** Four across, per the layout request. Two rows are visible; more scroll. */
        private const val COLUMNS = 4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_favorites_grid)

        grid = findViewById(R.id.fav_grid)
        empty = findViewById(R.id.fav_empty)
        loading = findViewById(R.id.fav_loading)

        grid.layoutManager = GridLayoutManager(this, COLUMNS)
        grid.setHasFixedSize(true)

        findViewById<Button>(R.id.fav_btn_search).setOnClickListener {
            startActivity(Intent(this, VoiceSearchActivity::class.java))
        }
        findViewById<Button>(R.id.fav_btn_movies).setOnClickListener {
            startActivity(Intent(this, VODBrowserActivity::class.java).apply {
                putExtra(VODBrowserActivity.EXTRA_CONTENT_TYPE, VODBrowserActivity.TYPE_MOVIES)
            })
        }
        findViewById<Button>(R.id.fav_btn_shows).setOnClickListener {
            startActivity(Intent(this, VODBrowserActivity::class.java).apply {
                putExtra(VODBrowserActivity.EXTRA_CONTENT_TYPE, VODBrowserActivity.TYPE_SHOWS)
            })
        }
        findViewById<Button>(R.id.fav_btn_kids).setOnClickListener {
            startActivity(Intent(this, KidsBrowserActivity::class.java))
        }
        // No Live TV button in the top row by design; the empty-state button is the way
        // in for a user who has no favourites yet.
        findViewById<Button>(R.id.fav_empty_browse).setOnClickListener { openLiveTv() }
    }

    private fun openLiveTv() {
        // LiveTVActivity is the router to whichever live style the user picked — all of
        // them support long-press-to-favourite, which is how channels land in this grid.
        startActivity(Intent(this, LiveTVActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        // Reload on every resume, not just onCreate: the user may have favourited or
        // unfavourited a channel in Live TV and returned here, and this is a launcher
        // home that stays resident, so onCreate won't run again to pick that up.
        loadContent()
    }

    private fun loadContent() {
        loading.visibility = View.VISIBLE
        scope.launch {
            val data = HomeContentLoader.load(this@HomeFavoritesGridActivity)
            // filterFavorites returns them in the user's saved favourite order, already
            // scoped to live channels since data.live is LIVE-only.
            val favorites = FavoritesManager(this@HomeFavoritesGridActivity).filterFavorites(data.live)

            loading.visibility = View.GONE
            if (favorites.isEmpty()) {
                grid.visibility = View.GONE
                empty.visibility = View.VISIBLE
                // Put focus on the one actionable thing so the remote works immediately.
                empty.findViewById<View>(R.id.fav_empty_browse).post {
                    empty.findViewById<View>(R.id.fav_empty_browse).requestFocus()
                }
            } else {
                empty.visibility = View.GONE
                grid.visibility = View.VISIBLE
                grid.adapter = FavoritesAdapter(
                    favorites,
                    onClick = { channel -> openChannel(channel) },
                    onBrowse = { openLiveTv() },
                )
                // Land focus on the first channel so the remote is immediately useful.
                grid.post {
                    grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun openChannel(channel: Channel) {
        startActivity(Intent(this, IPTVPlayerActivity::class.java).apply {
            putExtra(IPTVPlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_LOGO, channel.logoUrl)
            putExtra(IPTVPlayerActivity.EXTRA_CHANNEL_ID, channel.id)
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // We're a home screen — Back must not drop to the system launcher when this
            // app IS the launcher (there's nowhere to go). Otherwise let the default
            // handling finish the activity. Mirrors the other home layouts.
            if (isDefaultLauncher()) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /**
     * Square channel tiles, plus one trailing "＋ Browse Live TV" tile. Self-contained —
     * no EPG, no favourite toggle here; this grid is favourites already, and keeping the
     * tile simple keeps the senior UI legible. The trailing tile means a user who already
     * has favourites can still reach the live list to add more, without a top-row button.
     */
    private class FavoritesAdapter(
        private val channels: List<Channel>,
        private val onClick: (Channel) -> Unit,
        private val onBrowse: () -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val typeChannel = 0
        private val typeAdd = 1

        class ChannelVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val logo: ImageView = itemView.findViewById(R.id.fav_logo)
            val number: TextView = itemView.findViewById(R.id.fav_number)
            val name: TextView = itemView.findViewById(R.id.fav_name)
        }

        class AddVH(itemView: View) : RecyclerView.ViewHolder(itemView)

        // Favourites first, then a single trailing add tile.
        override fun getItemCount() = channels.size + 1
        override fun getItemViewType(position: Int) =
            if (position < channels.size) typeChannel else typeAdd

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == typeAdd) {
                AddVH(inflater.inflate(R.layout.item_favorite_add, parent, false))
            } else {
                ChannelVH(inflater.inflate(R.layout.item_favorite_square, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AddVH) {
                holder.itemView.setOnClickListener { onBrowse() }
                return
            }
            holder as ChannelVH
            val channel = channels[position]
            holder.name.text = channel.name
            if (channel.number > 0) {
                holder.number.visibility = View.VISIBLE
                holder.number.text = holder.itemView.context.getString(
                    R.string.channel_number_prefix, channel.number
                )
            } else {
                holder.number.visibility = View.GONE
            }

            if (channel.logoUrl.isNotBlank()) {
                Glide.with(holder.itemView.context)
                    .load(channel.logoUrl)
                    .placeholder(R.drawable.ic_iptv)
                    .into(holder.logo)
            } else {
                holder.logo.setImageResource(R.drawable.ic_iptv)
            }

            holder.itemView.setOnClickListener { onClick(channel) }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            // Release the Glide request/bitmap for the recycled tile so a large grid of
            // logos doesn't pin decoded bitmaps on the 2 GB box.
            if (holder is ChannelVH) Glide.with(holder.itemView.context).clear(holder.logo)
        }
    }
}
