package com.vistacore.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.View
import com.vistacore.launcher.R
import java.io.File
import java.io.FileOutputStream

/**
 * Manages custom wallpaper backgrounds for the launcher.
 * Supports built-in gradient presets, built-in image presets, and user-uploaded images.
 */
class WallpaperManager(private val context: Context) {

    companion object {
        private const val TAG = "WallpaperManager"
        private const val PREFS_NAME = "vistacore_wallpaper"
        private const val KEY_WALLPAPER_TYPE = "wallpaper_type"
        private const val KEY_WALLPAPER_PRESET = "wallpaper_preset"
        private const val KEY_WALLPAPER_IMAGE_PRESET = "wallpaper_image_preset"
        private const val KEY_WALLPAPER_CUSTOM = "wallpaper_custom_path"
        private const val KEY_WALLPAPER_DIM = "wallpaper_dim"
        private const val CUSTOM_WALLPAPER_FILE = "custom_wallpaper.jpg"

        const val TYPE_PRESET = 0
        const val TYPE_CUSTOM = 1
        const val TYPE_IMAGE = 2

        /**
         * Largest power-of-two subsample that still fully covers the target box.
         * BitmapFactory only honours powers of two, so this returns the biggest one
         * that does not undershoot 1920x1080 — undershooting would upscale and look
         * soft on a TV.
         *
         * On the companion (not the instance) so it is testable without a Context:
         * this arithmetic is what stands between the launcher and a 160 MB
         * allocation, and it should not be verified only by reading it.
         */
        internal fun sampleSizeFor(width: Int, height: Int, targetW: Int, targetH: Int): Int {
            if (width <= 0 || height <= 0 || targetW <= 0 || targetH <= 0) return 1
            var sample = 1
            while (width / (sample * 2) >= targetW && height / (sample * 2) >= targetH) {
                sample *= 2
            }
            return sample
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var wallpaperType: Int
        get() = prefs.getInt(KEY_WALLPAPER_TYPE, TYPE_PRESET)
        set(value) = prefs.edit().putInt(KEY_WALLPAPER_TYPE, value).apply()

    var presetIndex: Int
        get() = prefs.getInt(KEY_WALLPAPER_PRESET, 0)
        set(value) = prefs.edit().putInt(KEY_WALLPAPER_PRESET, value).apply()

    var imagePresetIndex: Int
        get() = prefs.getInt(KEY_WALLPAPER_IMAGE_PRESET, 0)
        set(value) = prefs.edit().putInt(KEY_WALLPAPER_IMAGE_PRESET, value).apply()

    var dimLevel: Float
        get() = prefs.getFloat(KEY_WALLPAPER_DIM, 0.7f)
        set(value) = prefs.edit().putFloat(KEY_WALLPAPER_DIM, value.coerceIn(0f, 0.95f)).apply()

    // Built-in gradient presets
    val presets: List<WallpaperPreset> = listOf(
        WallpaperPreset(
            name = "Midnight",
            colors = intArrayOf(0xFF141D2B.toInt(), 0xFF0D1117.toInt()),
            angle = GradientDrawable.Orientation.TOP_BOTTOM
        ),
        WallpaperPreset(
            name = "Deep Ocean",
            colors = intArrayOf(0xFF0A1628.toInt(), 0xFF0D2137.toInt(), 0xFF0A1628.toInt()),
            angle = GradientDrawable.Orientation.TL_BR
        ),
        WallpaperPreset(
            name = "Warm Ember",
            colors = intArrayOf(0xFF1A0A0A.toInt(), 0xFF2D1414.toInt(), 0xFF1A0A0A.toInt()),
            angle = GradientDrawable.Orientation.TOP_BOTTOM
        ),
        WallpaperPreset(
            name = "Forest",
            colors = intArrayOf(0xFF0A1A0A.toInt(), 0xFF0D2D14.toInt(), 0xFF0A1A0A.toInt()),
            angle = GradientDrawable.Orientation.TL_BR
        ),
        WallpaperPreset(
            name = "Royal Purple",
            colors = intArrayOf(0xFF140A28.toInt(), 0xFF1E1037.toInt(), 0xFF140A28.toInt()),
            angle = GradientDrawable.Orientation.TOP_BOTTOM
        ),
        WallpaperPreset(
            name = "Slate",
            colors = intArrayOf(0xFF1A1A2E.toInt(), 0xFF16213E.toInt(), 0xFF0F3460.toInt()),
            angle = GradientDrawable.Orientation.BL_TR
        ),
        WallpaperPreset(
            name = "Charcoal",
            colors = intArrayOf(0xFF1C1C1C.toInt(), 0xFF2D2D2D.toInt(), 0xFF1C1C1C.toInt()),
            angle = GradientDrawable.Orientation.LEFT_RIGHT
        ),
        WallpaperPreset(
            name = "Sunset Fade",
            colors = intArrayOf(0xFF1A0A1E.toInt(), 0xFF2D1428.toInt(), 0xFF1E0A14.toInt()),
            angle = GradientDrawable.Orientation.TL_BR
        )
    )

    // Built-in image presets (from assets/wallpapers/)
    val imagePresets: List<ImagePreset> = listOf(
        ImagePreset("Aurora", "wallpapers/aurora.jpg"),
        ImagePreset("Spectrum", "wallpapers/spectrum.jpg"),
        ImagePreset("Nebula", "wallpapers/nebula.jpg"),
        ImagePreset("Mountains", "wallpapers/mountains.jpg"),
        ImagePreset("Night Sky", "wallpapers/night_sky.jpg"),
        ImagePreset("Treeline", "wallpapers/treeline.jpg"),
        ImagePreset("Dark Leaves", "wallpapers/dark_leaves.jpg"),
        ImagePreset("Golden Field", "wallpapers/golden_field.jpg"),
        ImagePreset("Lakeside", "wallpapers/lakeside.jpg"),
        ImagePreset("Ocean Shore", "wallpapers/ocean_shore.jpg"),
        ImagePreset("Storm Clouds", "wallpapers/storm_clouds.jpg"),
        ImagePreset("Abstract Waves", "wallpapers/abstract_waves.jpg"),
        ImagePreset("Sunflower", "wallpapers/sunflower.jpg"),
        ImagePreset("Marble", "wallpapers/marble.jpg"),
        ImagePreset("Primate", "wallpapers/primate.jpg")
    )

    /**
     * Apply the current wallpaper to a view.
     */
    fun applyWallpaper(rootView: View) {
        // Kept deliberately: which branch runs decides whether this screen costs a
        // few hundred bytes (gradient) or a multi-megabyte bitmap decode, and that
        // is not knowable from outside the app on a non-debuggable release build.
        android.util.Log.d(TAG, "applyWallpaper type=$wallpaperType imagePreset=$imagePresetIndex")
        when (wallpaperType) {
            TYPE_PRESET -> {
                val preset = presets.getOrElse(presetIndex) { presets[0] }
                val gradient = GradientDrawable(preset.angle, preset.colors)
                rootView.background = gradient
                rootView.foreground = null
            }
            TYPE_IMAGE -> {
                val imagePreset = imagePresets.getOrElse(imagePresetIndex) { imagePresets[0] }
                val bitmap = loadAssetBitmap(imagePreset.assetPath)
                if (bitmap != null) {
                    val drawable = BitmapDrawable(context.resources, bitmap)
                    rootView.background = drawable
                    rootView.foreground = GradientDrawable().apply {
                        setColor(((dimLevel * 255).toInt() shl 24))
                    }
                } else {
                    val preset = presets[0]
                    rootView.background = GradientDrawable(preset.angle, preset.colors)
                    rootView.foreground = null
                }
            }
            TYPE_CUSTOM -> {
                val bitmap = loadCustomWallpaper()
                if (bitmap != null) {
                    val drawable = BitmapDrawable(context.resources, bitmap)
                    rootView.background = drawable
                    rootView.foreground = GradientDrawable().apply {
                        setColor(((dimLevel * 255).toInt() shl 24))
                    }
                } else {
                    val preset = presets[0]
                    rootView.background = GradientDrawable(preset.angle, preset.colors)
                    rootView.foreground = null
                }
            }
        }
    }

    /**
     * Load a bitmap from the assets folder, scaled to TV resolution.
     *
     * Decodes in two passes. The shipped assets are full-resolution stock photos —
     * sunflower.jpg is 8192x5122, which as ARGB_8888 is a *160 MB single native
     * allocation* on a box with 1.4 GB of RAM. The old one-pass decodeStream did
     * exactly that, then handed the result to scaleBitmap, which allocated the
     * 1920x1080 copy while the giant source was still live (~168 MB peak) and left
     * the source for the GC. Because applyWallpaper runs on every MainActivity
     * onResume, that spike recurred for the whole process lifetime and is what grew
     * the native heap arena to ~297 MB — of which 252 MB then sat free-but-mapped
     * and got swapped to zram.
     *
     * RGB_565 because these are opaque photos rendered behind a dim scrim
     * (applyWallpaper sets a foreground overlay), so the alpha channel is unused and
     * the banding is invisible at TV viewing distance. Halves the result again.
     */
    fun loadAssetBitmap(assetPath: String): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

            opts.inSampleSize = sampleSizeFor(opts.outWidth, opts.outHeight, 1920, 1080)
            opts.inPreferredConfig = Bitmap.Config.RGB_565
            opts.inJustDecodeBounds = false

            val decoded = context.assets.open(assetPath).use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            android.util.Log.d(
                TAG,
                "decoded $assetPath ${opts.outWidth}x${opts.outHeight} sample=${opts.inSampleSize}" +
                    " -> ${decoded.width}x${decoded.height} (${decoded.byteCount / 1024}KB)"
            )
            scaleBitmap(decoded, 1920, 1080)
        } catch (_: Throwable) {
            // Throwable, not Exception: a large decode fails with OutOfMemoryError,
            // which is an Error. Catching only Exception let it escape into
            // MainActivity.onResume — which has no handler — and crashed the
            // launcher instead of falling back to the gradient branch.
            null
        }
    }

    private fun sampleSizeFor(width: Int, height: Int, targetW: Int, targetH: Int): Int =
        Companion.sampleSizeFor(width, height, targetW, targetH)

    /**
     * Load a thumbnail from assets (smaller for the picker grid).
     */
    fun loadAssetThumbnail(assetPath: String): Bitmap? {
        return try {
            val inputStream = context.assets.open(assetPath)
            // Decode bounds first
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(inputStream, null, opts)
            inputStream.close()

            // Calculate sample size for ~360px wide thumbnail
            val targetWidth = 360
            opts.inSampleSize = (opts.outWidth / targetWidth).coerceAtLeast(1)
            opts.inJustDecodeBounds = false

            val thumbStream = context.assets.open(assetPath)
            val bitmap = BitmapFactory.decodeStream(thumbStream, null, opts)
            thumbStream.close()
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Save a custom wallpaper image from a content URI.
     */
    fun saveCustomWallpaper(uri: Uri): Boolean {
        return try {
            // Bounds pass first: this URI is whatever the user picked, and a photo
            // straight off a phone camera is routinely 12-50 MP — decoding it whole
            // is a 48-200 MB allocation that can OOM the launcher outright.
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return false

            opts.inSampleSize = sampleSizeFor(opts.outWidth, opts.outHeight, 1920, 1080)
            opts.inJustDecodeBounds = false

            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false

            val scaled = scaleBitmap(bitmap, 1920, 1080)

            val file = File(context.filesDir, CUSTOM_WALLPAPER_FILE)
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            scaled.recycle()

            wallpaperType = TYPE_CUSTOM
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Load the saved custom wallpaper.
     */
    fun loadCustomWallpaper(): Bitmap? {
        val file = File(context.filesDir, CUSTOM_WALLPAPER_FILE)
        if (!file.exists()) return null
        return try {
            // We write this file at 1920x1080, but a file saved by an older build
            // predates that guarantee — so bounds-check rather than trust it. RGB_565
            // for the same reason as the asset path: opaque photo behind a dim scrim.
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

            opts.inSampleSize = sampleSizeFor(opts.outWidth, opts.outHeight, 1920, 1080)
            opts.inPreferredConfig = Bitmap.Config.RGB_565
            opts.inJustDecodeBounds = false
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Throwable) {
            null
        }
    }

    fun hasCustomWallpaper(): Boolean {
        return File(context.filesDir, CUSTOM_WALLPAPER_FILE).exists()
    }

    fun deleteCustomWallpaper() {
        File(context.filesDir, CUSTOM_WALLPAPER_FILE).delete()
        wallpaperType = TYPE_PRESET
    }

    private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) return bitmap

        val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        // Free the source now rather than waiting for a GC pass. Both bitmaps are
        // live across createScaledBitmap, so back-to-back onResume calls could
        // otherwise hold two full-size sources at once. createScaledBitmap returns
        // the same instance when no scaling is needed — recycling that would hand
        // back an unusable bitmap.
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}

data class WallpaperPreset(
    val name: String,
    val colors: IntArray,
    val angle: GradientDrawable.Orientation
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WallpaperPreset) return false
        return name == other.name
    }

    override fun hashCode() = name.hashCode()
}

data class ImagePreset(
    val name: String,
    val assetPath: String
)
