package com.example.microqr.ui.location

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
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

            binding.editLocationButton.setOnClickListener {
                onEditClick(location)
            }

            binding.deleteLocationButton.setOnClickListener {
                onDeleteClick(location)
            }

            // Optional: Make the whole item clickable for editing
            binding.root.setOnClickListener {
                onEditClick(location)
            }
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