package com.example.microqr.ui.location

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.microqr.R
import com.example.microqr.databinding.ItemLocationBinding

class LocationAdapter(
    private val onEditClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : ListAdapter<String, LocationAdapter.LocationViewHolder>(LocationDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LocationViewHolder {
        val binding = ItemLocationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LocationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LocationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LocationViewHolder(
        private val binding: ItemLocationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(location: String) {
            binding.locationName.text = location

            // Set text color from colors.xml
            binding.locationName.setTextColor(
                ContextCompat.getColor(binding.root.context, R.color.primary_text_color)
            )

            // Set up edit button with proper styling
            binding.editLocationButton.apply {
                setOnClickListener { onEditClick(location) }
                contentDescription = binding.root.context.getString(R.string.cd_edit_location)
                // Use colors from colors.xml
                setColorFilter(ContextCompat.getColor(context, R.color.primary_color))
            }

            // Set up delete button with proper styling
            binding.deleteLocationButton.apply {
                setOnClickListener { onDeleteClick(location) }
                contentDescription = binding.root.context.getString(R.string.cd_delete_location)
                // Use colors from colors.xml
                setColorFilter(ContextCompat.getColor(context, R.color.error_red))
            }

            // Set card background color
            binding.root.setCardBackgroundColor(
                ContextCompat.getColor(binding.root.context, R.color.card_background)
            )

            // Optional: Make the whole item clickable for editing with visual feedback
            binding.root.setOnClickListener {
                onEditClick(location)
            }

            // Add ripple effect color
            binding.root.foreground = ContextCompat.getDrawable(
                binding.root.context,
                R.drawable.ripple_effect
            )
        }
    }

    private class LocationDiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }
    }
}