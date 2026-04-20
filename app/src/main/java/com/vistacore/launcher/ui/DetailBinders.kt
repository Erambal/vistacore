package com.vistacore.launcher.ui

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.vistacore.launcher.R
import com.vistacore.launcher.iptv.CastMember

/**
 * Shared helpers for the movie + show detail screens: badge row renderer
 * and a cast RecyclerView adapter. Kept separate so both Activities can
 * call the same code without subclassing.
 */
object DetailBinders {

    /**
     * Populate a horizontal LinearLayout with pill-style badges:
     *  ★ rating · MPAA · runtime · year
     * Each is only added if the corresponding value is non-blank.
     */
    fun renderBadges(
        container: LinearLayout,
        rating: String,
        mpaa: String,
        runtime: String,
        year: String
    ) {
        container.removeAllViews()
        val ctx = container.context

        formatRating(rating)?.let {
            container.addView(makeBadge(ctx, "★  $it", R.drawable.detail_badge_rating, R.color.accent_gold))
        }
        if (mpaa.isNotBlank()) {
            container.addView(makeBadge(ctx, mpaa.trim(), R.drawable.detail_badge_background, R.color.text_primary))
        }
        if (runtime.isNotBlank()) {
            container.addView(makeBadge(ctx, runtime, R.drawable.detail_badge_background, R.color.text_primary))
        }
        if (year.isNotBlank()) {
            container.addView(makeBadge(ctx, year, R.drawable.detail_badge_background, R.color.text_primary))
        }
    }

    /**
     * "Drama · USA · Dir. Jane Doe" — skip pieces that are blank.
     */
    fun buildMetaLine(genre: String, country: String, director: String): String {
        val parts = mutableListOf<String>()
        if (genre.isNotBlank()) parts += genre
        if (country.isNotBlank()) parts += country
        if (director.isNotBlank()) parts += "Dir. $director"
        return parts.joinToString("  ·  ")
    }

    /**
     * Xtream serves runtime in several shapes ("01:32:00", "120 min", raw
     * seconds). Normalize to "1h 32m" or "45m" for display.
     */
    fun formatRuntime(duration: String, durationSecs: Long, episodeRunTime: String): String {
        val hhmm = Regex("""^(\d{1,2}):(\d{1,2})(?::\d{1,2})?$""").find(duration)
        if (hhmm != null) {
            val h = hhmm.groupValues[1].toIntOrNull() ?: 0
            val m = hhmm.groupValues[2].toIntOrNull() ?: 0
            if (h > 0) return "${h}h ${m}m"
            if (m > 0) return "${m}m"
        }
        if (durationSecs > 0) {
            val h = (durationSecs / 3600).toInt()
            val m = ((durationSecs % 3600) / 60).toInt()
            return if (h > 0) "${h}h ${m}m" else "${m}m"
        }
        val epMin = episodeRunTime.trim().toIntOrNull()
        if (epMin != null && epMin > 0) return "${epMin}m"
        return ""
    }

    /**
     * Xtream ratings come as strings on a 0–10 scale, sometimes 0–5, and
     * sometimes with trailing noise. Produce a clean one-decimal figure or
     * null if we can't parse anything useful.
     */
    private fun formatRating(raw: String): String? {
        if (raw.isBlank()) return null
        val num = raw.trim().take(4).toDoubleOrNull() ?: return null
        if (num <= 0.0) return null
        val scaled = if (num > 10.0) num / 10.0 else num
        return String.format("%.1f", scaled)
    }

    private fun makeBadge(ctx: Context, text: String, bgRes: Int, colorRes: Int): TextView {
        val tv = TextView(ctx).apply {
            this.text = text
            setTextColor(ctx.getColor(colorRes))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(pxFromDp(ctx, 10), pxFromDp(ctx, 4), pxFromDp(ctx, 10), pxFromDp(ctx, 4))
            background = ctx.getDrawable(bgRes)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = pxFromDp(ctx, 8) }
        tv.layoutParams = lp
        return tv
    }

    private fun pxFromDp(ctx: Context, dp: Int): Int =
        (dp * ctx.resources.displayMetrics.density).toInt()
}

class CastAdapter(
    private val cast: List<CastMember>
) : RecyclerView.Adapter<CastAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val photo: ImageView = view.findViewById(R.id.cast_photo)
        val initials: TextView = view.findViewById(R.id.cast_initials)
        val name: TextView = view.findViewById(R.id.cast_name)
        val role: TextView = view.findViewById(R.id.cast_role)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cast_member, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val member = cast[position]
        holder.name.text = member.name
        holder.role.text = member.character
        holder.role.visibility = if (member.character.isBlank()) View.GONE else View.VISIBLE

        // Initials as the baseline; photo overlays on top if it loads.
        holder.initials.text = initials(member.name)
        holder.photo.visibility = View.GONE

        if (member.profileUrl.isNotBlank()) {
            // Show the photo frame immediately so the layout doesn't jump;
            // Glide will fill it in when the load succeeds and hide the
            // initials overlay. On failure the initials stay visible.
            holder.photo.visibility = View.VISIBLE
            holder.photo.setImageDrawable(null)
            Glide.with(holder.itemView.context)
                .load(member.profileUrl)
                .apply(RequestOptions.bitmapTransform(CircleCrop()))
                .addListener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                    override fun onLoadFailed(
                        e: com.bumptech.glide.load.engine.GlideException?,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        holder.photo.visibility = View.GONE
                        return false
                    }

                    override fun onResourceReady(
                        resource: android.graphics.drawable.Drawable,
                        model: Any,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                        dataSource: com.bumptech.glide.load.DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        holder.initials.visibility = View.GONE
                        return false
                    }
                })
                .into(holder.photo)
        }

        holder.itemView.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
    }

    override fun getItemCount() = cast.size

    private fun initials(name: String): String =
        name.split(Regex("""\s+"""))
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .take(2)
            .joinToString("")
}
