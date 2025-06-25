package com.example.microqr.ui.metermatch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.microqr.R
import com.example.microqr.ui.files.MeterStatus

class MeterMatchAdapter(
    private val onItemClick: (MeterStatus) -> Unit = {}
) : ListAdapter<MeterStatus, MeterMatchAdapter.MeterViewHolder>(MeterDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MeterViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_match_meter, parent, false)
        return MeterViewHolder(view)
    }

    override fun onBindViewHolder(holder: MeterViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MeterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMeterNumber: TextView = itemView.findViewById(R.id.tvMeterNumber)
        private val tvSerialNumber: TextView = itemView.findViewById(R.id.tvSerialNumber)
        private val tvPlace: TextView = itemView.findViewById(R.id.tvPlace)
        private val tvSourceFile: TextView = itemView.findViewById(R.id.tvSourceFile)
        private val statusIndicator: View = itemView.findViewById(R.id.status_indicator)
        private val tvMatchStatus: TextView = itemView.findViewById(R.id.tvMatchStatus)
        private val ivQrStatus: ImageView = itemView.findViewById(R.id.ivQrStatus)

        // Meter number badge background (the circular container)
        private val meterNumberBadge: View = itemView.findViewById(R.id.meter_number_badge)

        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }
        }

        fun bind(meter: MeterStatus) {
            // Set meter number (show just the number part)
            tvMeterNumber.text = meter.number

            // Set serial number
            tvSerialNumber.text = meter.serialNumber

            // Set place
            tvPlace.text = meter.place

            // Set source file
            tvSourceFile.text = "From: ${getShortFileName(meter.fromFile)}"

            // Determine status and colors based on scan status
            val (statusText, statusColor, badgeColor, qrIcon) = when {
                meter.isChecked -> {
                    // Scanned: Green badge, success status
                    Tuple4("Scanned ✓", R.color.success_green, R.color.success_green, R.drawable.ic_check_circle)
                }
                else -> {
                    // Pending: Blue badge, pending status
                    Tuple4("Pending Scan", R.color.primary_color, R.color.primary_color, R.drawable.ic_qr_code_scanner_24)
                }
            }

            // Set status text and color
            tvMatchStatus.text = statusText
            tvMatchStatus.setTextColor(ContextCompat.getColor(itemView.context, statusColor))

            // Set status indicator color (small dot)
            statusIndicator.backgroundTintList = ContextCompat.getColorStateList(itemView.context, statusColor)

            // Set meter number badge background color
            meterNumberBadge.backgroundTintList = ContextCompat.getColorStateList(itemView.context, badgeColor)

            // Set QR status icon
            ivQrStatus.visibility = View.VISIBLE
            ivQrStatus.setImageResource(qrIcon)
            ivQrStatus.setColorFilter(ContextCompat.getColor(itemView.context, statusColor))

            // Set meter number text color for better contrast
            val textColor = when {
                meter.isChecked -> R.color.white // White text on green background
                else -> R.color.white // White text on blue background
            }
            tvMeterNumber.setTextColor(ContextCompat.getColor(itemView.context, textColor))

            // Enhanced visual styling based on scan status
            if (meter.isChecked) {
                // Already scanned - normal styling
                tvSerialNumber.alpha = 1.0f
                itemView.alpha = 1.0f
            } else {
                // Not scanned yet - emphasize for action
                tvSerialNumber.alpha = 1.0f
                itemView.alpha = 1.0f
            }

            // Keep card background white - remove any background tinting
            itemView.backgroundTintList = ContextCompat.getColorStateList(itemView.context, R.color.white)

            // Set card elevation based on status
            if (itemView is com.google.android.material.card.MaterialCardView) {
                val elevation = if (!meter.isChecked) 4f else 2f // Slightly higher elevation for pending items
                (itemView as com.google.android.material.card.MaterialCardView).cardElevation = elevation
            }

            // Add subtle border for pending items
            if (itemView is com.google.android.material.card.MaterialCardView) {
                val strokeColor = if (!meter.isChecked) R.color.primary_color else R.color.stroke_color
                val strokeWidth = if (!meter.isChecked) 2 else 1
                (itemView as com.google.android.material.card.MaterialCardView).strokeColor =
                    ContextCompat.getColor(itemView.context, strokeColor)
                (itemView as com.google.android.material.card.MaterialCardView).strokeWidth =
                    (strokeWidth * itemView.context.resources.displayMetrics.density).toInt()
            }
        }

        private fun getShortFileName(fileName: String): String {
            return if (fileName.length > 20) {
                fileName.take(17) + "..."
            } else {
                fileName
            }
        }
    }

    private class MeterDiffCallback : DiffUtil.ItemCallback<MeterStatus>() {
        override fun areItemsTheSame(oldItem: MeterStatus, newItem: MeterStatus): Boolean {
            return oldItem.serialNumber == newItem.serialNumber && oldItem.fromFile == newItem.fromFile
        }

        override fun areContentsTheSame(oldItem: MeterStatus, newItem: MeterStatus): Boolean {
            return oldItem == newItem
        }
    }
}

// Helper data class for multiple return values
private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)