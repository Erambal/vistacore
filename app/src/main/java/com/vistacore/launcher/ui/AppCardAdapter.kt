package com.vistacore.launcher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.vistacore.launcher.apps.AppItem
import com.vistacore.launcher.databinding.ItemAppCardBinding

class AppCardAdapter(
    private val apps: List<AppItem>,
    private val onAppClick: (AppItem) -> Unit
) : RecyclerView.Adapter<AppCardAdapter.AppCardViewHolder>() {

    inner class AppCardViewHolder(
        private val binding: ItemAppCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppItem) {
            binding.appIcon.setImageResource(app.iconRes)
            binding.appLabel.text = app.label

            // Click handler
            binding.root.setOnClickListener {
                onAppClick(app)
            }

            // Focus animation — scale up with golden glow on D-pad focus
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                MainActivity.animateFocus(view, hasFocus)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppCardViewHolder {
        val binding = ItemAppCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return AppCardViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppCardViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount() = apps.size
}
