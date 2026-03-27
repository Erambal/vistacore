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
 * Supports built-in presets and user-uploaded images.
 */
class WallpaperManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "vistacore_wallpaper"
        private const val KEY_WALLPAPER_TYPE = "wallpaper_type"
        private const val KEY_WALLPAPER_PRESET = "wallpaper_preset"
        private const val KEY_WALLPAPER_CUSTOM = "wallpaper_custom_path"
        private const val KEY_WALLPAPER_DIM = "wallpaper_dim"
        private const val CUSTOM_WALLPAPER_FILE = "custom_wallpaper.jpg"

        const val TYPE_PRESET = 0
        const val TYPE_CUSTOM = 1
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var wallpaperType: Int
        get() = prefs.getInt(KEY_WALLPAPER_TYPE, TYPE_PRESET)
        set(value) = prefs.edit().putInt(KEY_WALLPAPER_TYPE, value).apply()

    var presetIndex: Int
        get() = prefs.getInt(KEY_WALLPAPER_PRESET, 0)
        set(value) = prefs.edit().putInt(KEY_WALLPAPER_PRESET, value).apply()

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

    /**
     * Apply the current wallpaper to a view.
     */
    fun applyWallpaper(rootView: View) {
        when (wallpaperType) {
            TYPE_PRESET -> {
                val preset = presets.getOrElse(presetIndex) { presets[0] }
                val gradient = GradientDrawable(preset.angle, preset.colors)
                rootView.background = gradient
            }
            TYPE_CUSTOM -> {
                val bitmap = loadCustomWallpaper()
                if (bitmap != null) {
                    val drawable = BitmapDrawable(context.resources, bitmap)
                    rootView.background = drawable
                    // Apply dim overlay
                    rootView.foreground = GradientDrawable().apply {
                        setColor(((dimLevel * 255).toInt() shl 24))
                    }
                } else {
                    // Fallback to default preset
                    val preset = presets[0]
                    rootView.background = GradientDrawable(preset.angle, preset.colors)
                }
            }
        }
    }

    /**
     * Save a custom wallpaper image from a content URI.
     */
    fun saveCustomWallpaper(uri: Uri): Boolean {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return false
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (bitmap == null) return false

            // Scale down if too large (TV resolution max)
            val scaled = scaleBitmap(bitmap, 1920, 1080)

            val file = File(context.filesDir, CUSTOM_WALLPAPER_FILE)
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            wallpaperType = TYPE_CUSTOM
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Load the saved custom wallpaper.
     */
    fun loadCustomWallpaper(): Bitmap? {
        val file = File(context.filesDir, CUSTOM_WALLPAPER_FILE)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
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

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
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
