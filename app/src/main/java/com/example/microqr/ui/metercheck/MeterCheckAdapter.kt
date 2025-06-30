package com.example.microqr.ui.metercheck

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.microqr.R
import com.example.microqr.ui.files.MeterStatus

class MeterCheckAdapter(
    private val onItemClick: (MeterStatus) -> Unit
) : ListAdapter<MeterStatus, MeterCheckAdapter.MeterViewHolder>(MeterDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MeterViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_meter_check, parent, false)
        return MeterViewHolder(view)
    }

    override fun onBindViewHolder(holder: MeterViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MeterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvMeterNumber: TextView = itemView.findViewById(R.id.tvMeterNumber)
        private val tvSerialNumber: TextView = itemView.findViewById(R.id.tvSerialNumber)
        private val tvLocation: TextView = itemView.findViewById(R.id.tvLocation)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val tvConditionWarning: TextView = itemView.findViewById(R.id.tvConditionWarning)
        private val tvTapHint: TextView = itemView.findViewById(R.id.tvTapHint)
        private val statusIndicator: View = itemView.findViewById(R.id.statusIndicator)

        fun bind(meter: MeterStatus) {
            // Set basic meter information
            tvMeterNumber.text = meter.number.ifEmpty { String.format("%03d", adapterPosition + 1) }
            tvSerialNumber.text = meter.serialNumber
            tvLocation.text = meter.place
            tvFileName.text = getShortFileName(meter.fromFile)

            // Determine meter conditions
            val hasValidLocation = isValidLocation(meter.place)
            val hasValidNumber = isValidNumber(meter.number)

            // Update status and conditional warnings
            updateStatusAndConditions(meter, hasValidLocation, hasValidNumber)

            // Set click listener (whole card is clickable for scanning)
            itemView.setOnClickListener {
                onItemClick(meter)
            }

            // Update card styling based on conditions and status
            updateCardStyling(meter, hasValidLocation, hasValidNumber)
        }

        private fun isValidLocation(location: String): Boolean {
            // Check if location is properly set (not empty, not default values)
            return location.isNotBlank() &&
                    location.lowercase() !in listOf("unknown", "n/a", "tbd", "pending", "-", "null")
        }

        private fun isValidNumber(number: String): Boolean {
            // Check if meter has been properly numbered
            return number.isNotBlank() &&
                    number != "-" &&
                    number.lowercase() != "unknown" &&
                    number.lowercase() != "pending"
        }

        private fun updateStatusAndConditions(meter: MeterStatus, hasValidLocation: Boolean, hasValidNumber: Boolean) {
            val context = itemView.context

            // Update main status badge
            if (meter.isChecked) {
                // Scanned state
                statusIndicator.background = ContextCompat.getDrawable(
                    context, R.drawable.circle_background_success
                )
                tvStatus.text = context.getString(R.string.scanned_meters)
                tvStatus.background = ContextCompat.getDrawable(
                    context, R.drawable.badge_background_success
                )
            } else {
                // Unscanned state
                statusIndicator.background = ContextCompat.getDrawable(
                    context, R.drawable.circle_background_warning
                )
                tvStatus.text = context.getString(R.string.unscanned_meters)
                tvStatus.background = ContextCompat.getDrawable(
                    context, R.drawable.badge_background_warning
                )
            }

            // Update conditional warning/info text
            val warningText = when {
                !hasValidLocation && !hasValidNumber -> "No Location\n& Number"
                !hasValidLocation -> "No Location\nSet"
                !hasValidNumber -> "No Number\nAssigned"
                meter.isChecked -> "Completed"
                else -> "Ready to\nScan"
            }

            val warningColor = when {
                !hasValidLocation || !hasValidNumber -> ContextCompat.getColor(context, R.color.error_red)
                meter.isChecked -> ContextCompat.getColor(context, R.color.success_green)
                else -> ContextCompat.getColor(context, R.color.primary_color)
            }

            tvConditionWarning.apply {
                text = warningText
                setTextColor(warningColor)
                visibility = View.VISIBLE
            }
        }

        private fun updateCardStyling(meter: MeterStatus, hasValidLocation: Boolean, hasValidNumber: Boolean) {
            if (itemView is com.google.android.material.card.MaterialCardView) {
                val cardView = itemView as com.google.android.material.card.MaterialCardView
                val context = itemView.context

                // Determine card priority/elevation
                val elevation = when {
                    !hasValidLocation || !hasValidNumber -> 6f // Highest priority - needs attention
                    !meter.isChecked -> 4f // Medium priority - ready to scan
                    else -> 2f // Low priority - completed
                }
                cardView.cardElevation = elevation

                // Determine stroke color based on conditions
                val strokeColor = when {
                    !hasValidLocation || !hasValidNumber -> ContextCompat.getColor(context, R.color.error_red)
                    meter.isChecked -> ContextCompat.getColor(context, R.color.success_green)
                    else -> ContextCompat.getColor(context, R.color.stroke_color)
                }

                cardView.strokeColor = strokeColor
                cardView.strokeWidth = if (strokeColor != ContextCompat.getColor(context, R.color.stroke_color)) {
                    (2 * context.resources.displayMetrics.density).toInt() // Thicker stroke for warnings/success
                } else {
                    (1 * context.resources.displayMetrics.density).toInt() // Normal stroke
                }

                // Subtle background tint based on status
                val backgroundColor = when {
                    !hasValidLocation || !hasValidNumber -> ContextCompat.getColor(context, R.color.error_red_alpha)
                    meter.isChecked -> ContextCompat.getColor(context, R.color.success_green_background)
                    else -> ContextCompat.getColor(context, R.color.card_background)
                }
                cardView.setCardBackgroundColor(backgroundColor)
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