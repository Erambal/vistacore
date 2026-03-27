package com.vistacore.launcher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vistacore.launcher.R
import com.vistacore.launcher.databinding.ItemAppCardBinding
import com.vistacore.launcher.iptv.Channel

/**
 * Displays favorite channels as large cards on the home screen.
 * Reuses the app card layout for consistency.
 */
class FavoriteChannelAdapter(
    private val channels: List<Channel>,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<FavoriteChannelAdapter.VH>() {

    inner class VH(private val binding: ItemAppCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel) {
            binding.appLabel.text = channel.name

            if (channel.logoUrl.isNotBlank()) {
                Glide.with(binding.root.context)
                    .load(channel.logoUrl)
                    .placeholder(R.drawable.ic_iptv)
                    .error(R.drawable.ic_iptv)
                    .into(binding.appIcon)
            } else {
                binding.appIcon.setImageResource(R.drawable.ic_iptv)
            }

            binding.root.setOnClickListener { onClick(channel) }
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                MainActivity.animateFocus(view, hasFocus)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(channels[position])
    override fun getItemCount() = channels.size
}
