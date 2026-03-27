package com.vistacore.launcher.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vistacore.launcher.R
import com.vistacore.launcher.data.ImagePreset
import com.vistacore.launcher.data.WallpaperManager
import com.vistacore.launcher.data.WallpaperPreset
import com.vistacore.launcher.databinding.ActivityWallpaperPickerBinding
import com.vistacore.launcher.databinding.ItemWallpaperPresetBinding
import com.vistacore.launcher.databinding.ItemWallpaperImageBinding

class WallpaperPickerActivity : BaseActivity() {

    companion object {
        private const val REQUEST_PICK_IMAGE = 2001
    }

    private lateinit var binding: ActivityWallpaperPickerBinding
    private lateinit var wallpaperManager: WallpaperManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWallpaperPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wallpaperManager = WallpaperManager(this)

        setupPresetsGrid()
        setupImagePresetsGrid()
        setupCustomImageButtons()
        setupDimSlider()
    }

    private fun setupPresetsGrid() {
        val isGradientSelected = wallpaperManager.wallpaperType == WallpaperManager.TYPE_PRESET
        binding.presetsGrid.layoutManager = LinearLayoutManager(
            this, LinearLayoutManager.HORIZONTAL, false
        )
        binding.presetsGrid.adapter = PresetAdapter(
            wallpaperManager.presets,
            if (isGradientSelected) wallpaperManager.presetIndex else -1
        ) { index ->
            wallpaperManager.wallpaperType = WallpaperManager.TYPE_PRESET
            wallpaperManager.presetIndex = index
            binding.dimSection.visibility = View.GONE
            binding.btnRemoveCustom.visibility = View.GONE
            (binding.presetsGrid.adapter as PresetAdapter).selectedIndex = index
            // Deselect image presets
            (binding.imagePresetsGrid.adapter as ImagePresetAdapter).selectedIndex = -1
            Toast.makeText(this, "Background set!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupImagePresetsGrid() {
        val isImageSelected = wallpaperManager.wallpaperType == WallpaperManager.TYPE_IMAGE
        binding.imagePresetsGrid.layoutManager = LinearLayoutManager(
            this, LinearLayoutManager.HORIZONTAL, false
        )
        binding.imagePresetsGrid.adapter = ImagePresetAdapter(
            wallpaperManager,
            if (isImageSelected) wallpaperManager.imagePresetIndex else -1
        ) { index ->
            wallpaperManager.wallpaperType = WallpaperManager.TYPE_IMAGE
            wallpaperManager.imagePresetIndex = index
            binding.dimSection.visibility = View.VISIBLE
            binding.btnRemoveCustom.visibility = View.GONE
            (binding.imagePresetsGrid.adapter as ImagePresetAdapter).selectedIndex = index
            // Deselect gradient presets
            (binding.presetsGrid.adapter as PresetAdapter).selectedIndex = -1
            Toast.makeText(this, "Background set!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupCustomImageButtons() {
        binding.btnPickImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(intent, REQUEST_PICK_IMAGE)
        }

        binding.btnRemoveCustom.setOnClickListener {
            wallpaperManager.deleteCustomWallpaper()
            binding.btnRemoveCustom.visibility = View.GONE
            binding.dimSection.visibility = View.GONE
            Toast.makeText(this, "Custom wallpaper removed", Toast.LENGTH_SHORT).show()
        }

        // Show remove button if custom wallpaper exists
        if (wallpaperManager.hasCustomWallpaper()) {
            binding.btnRemoveCustom.visibility = View.VISIBLE
        }

        // Show dim section if currently using custom or image preset
        if (wallpaperManager.wallpaperType == WallpaperManager.TYPE_CUSTOM ||
            wallpaperManager.wallpaperType == WallpaperManager.TYPE_IMAGE) {
            binding.dimSection.visibility = View.VISIBLE
        }

        // Focus animations
        listOf(binding.btnPickImage, binding.btnRemoveCustom).forEach { btn ->
            btn.setOnFocusChangeListener { v, f -> MainActivity.animateFocus(v, f) }
        }
    }

    private fun setupDimSlider() {
        val currentDim = (wallpaperManager.dimLevel * 100).toInt()
        binding.dimSlider.progress = currentDim
        binding.dimLabel.text = "$currentDim%"

        binding.dimSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.dimLabel.text = "$progress%"
                if (fromUser) {
                    wallpaperManager.dimLevel = progress / 100f
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_PICK_IMAGE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return

            val success = wallpaperManager.saveCustomWallpaper(uri)
            if (success) {
                binding.btnRemoveCustom.visibility = View.VISIBLE
                binding.dimSection.visibility = View.VISIBLE
                // Deselect all presets
                (binding.presetsGrid.adapter as PresetAdapter).selectedIndex = -1
                (binding.imagePresetsGrid.adapter as ImagePresetAdapter).selectedIndex = -1
                Toast.makeText(this, "Custom wallpaper set!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Could not load image", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// --- Gradient Preset Adapter ---

class PresetAdapter(
    private val presets: List<WallpaperPreset>,
    initialSelected: Int,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<PresetAdapter.VH>() {

    var selectedIndex: Int = initialSelected
        set(value) {
            val old = field
            field = value
            notifyItemChanged(old)
            notifyItemChanged(value)
        }

    inner class VH(private val binding: ItemWallpaperPresetBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(preset: WallpaperPreset, position: Int) {
            val gradient = GradientDrawable(preset.angle, preset.colors)
            gradient.cornerRadius = 16f
            binding.presetPreview.background = gradient
            binding.presetName.text = preset.name
            binding.presetCheck.visibility = if (position == selectedIndex) View.VISIBLE else View.GONE

            binding.root.setOnClickListener {
                onClick(position)
            }

            binding.root.setOnFocusChangeListener { view, hasFocus ->
                MainActivity.animateFocus(view, hasFocus)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemWallpaperPresetBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(presets[position], position)
    override fun getItemCount() = presets.size
}

// --- Image Preset Adapter ---

class ImagePresetAdapter(
    private val wallpaperManager: WallpaperManager,
    initialSelected: Int,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<ImagePresetAdapter.VH>() {

    private val imagePresets = wallpaperManager.imagePresets

    var selectedIndex: Int = initialSelected
        set(value) {
            val old = field
            field = value
            if (old >= 0) notifyItemChanged(old)
            if (value >= 0) notifyItemChanged(value)
        }

    inner class VH(private val binding: ItemWallpaperImageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(preset: ImagePreset, position: Int) {
            binding.imageName.text = preset.name
            binding.imageCheck.visibility = if (position == selectedIndex) View.VISIBLE else View.GONE

            // Load thumbnail from assets
            val thumbnail = wallpaperManager.loadAssetThumbnail(preset.assetPath)
            if (thumbnail != null) {
                binding.imagePreview.setImageBitmap(thumbnail)
            }

            binding.root.setOnClickListener {
                onClick(position)
            }

            binding.root.setOnFocusChangeListener { view, hasFocus ->
                MainActivity.animateFocus(view, hasFocus)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemWallpaperImageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(imagePresets[position], position)
    override fun getItemCount() = imagePresets.size
}
