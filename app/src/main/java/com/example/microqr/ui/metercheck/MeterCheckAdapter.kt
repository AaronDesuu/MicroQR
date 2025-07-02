package com.example.microqr.ui.metercheck

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
import com.google.android.material.card.MaterialCardView

class MeterCheckAdapter(
    private val onItemClick: (MeterStatus) -> Unit,
    private val onEditClick: (MeterStatus) -> Unit,
    private val onScanClick: (MeterStatus) -> Unit
) : ListAdapter<MeterStatus, MeterCheckAdapter.MeterViewHolder>(MeterDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MeterViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_meter_check, parent, false)
        return MeterViewHolder(view, onItemClick, onEditClick, onScanClick)
    }

    override fun onBindViewHolder(holder: MeterViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MeterViewHolder(
        itemView: View,
        private val onItemClick: (MeterStatus) -> Unit,
        private val onEditClick: (MeterStatus) -> Unit,
        private val onScanClick: (MeterStatus) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val cardView: MaterialCardView = itemView as MaterialCardView
        private val meterNumberBadge: MaterialCardView = itemView.findViewById(R.id.meter_number_badge)
        private val tvMeterNumber: TextView = itemView.findViewById(R.id.tvMeterNumber)
        private val tvSerialNumber: TextView = itemView.findViewById(R.id.tvSerialNumber)
        private val tvLocation: TextView = itemView.findViewById(R.id.tvLocation)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val statusBadge: MaterialCardView = itemView.findViewById(R.id.status_badge)
        private val tvScanStatus: TextView = itemView.findViewById(R.id.tvScanStatus)
        private val ivIncompleteWarning: ImageView = itemView.findViewById(R.id.ivIncompleteWarning)

        fun bind(meter: MeterStatus) {
            // Set meter data
            tvMeterNumber.text = formatMeterNumber(meter.number)
            tvSerialNumber.text = meter.serialNumber
            tvLocation.text = meter.place
            tvFileName.text = getShortFileName(meter.fromFile)

            // Set scan status and colors
            if (meter.isChecked) {
                tvScanStatus.text = itemView.context.getString(R.string.status_scanned).uppercase()
                statusBadge.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.success_green))
                meterNumberBadge.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.success_green))
            } else {
                tvScanStatus.text = itemView.context.getString(R.string.status_not_scanned).uppercase()
                statusBadge.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.warning_orange))
                meterNumberBadge.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.primary_color))
            }

            // Check information completeness
            val isLocationComplete = isLocationValid(meter.place)
            val isMeterNumberComplete = isMeterNumberValid(meter.number)
            val isInformationComplete = isLocationComplete && isMeterNumberComplete

            // Set card border based on information completeness
            if (isInformationComplete) {
                // White border for complete information
                cardView.strokeColor = ContextCompat.getColor(itemView.context, R.color.stroke_color)
                cardView.strokeWidth = dpToPx(1)
                ivIncompleteWarning.visibility = View.GONE
            } else {
                // Yellow/Orange border for incomplete information
                cardView.strokeColor = ContextCompat.getColor(itemView.context, R.color.warning_orange)
                cardView.strokeWidth = dpToPx(2)
                ivIncompleteWarning.visibility = View.VISIBLE
            }

            // Highlight incomplete fields with warning color
            if (!isLocationComplete) {
                tvLocation.setTextColor(ContextCompat.getColor(itemView.context, R.color.warning_orange))
            } else {
                tvLocation.setTextColor(ContextCompat.getColor(itemView.context, R.color.primary_text_color))
            }

            if (!isMeterNumberComplete) {
                tvMeterNumber.setTextColor(ContextCompat.getColor(itemView.context, R.color.warning_orange))
            } else {
                tvMeterNumber.setTextColor(ContextCompat.getColor(itemView.context, R.color.white))
            }

            // Set card elevation based on scan status (similar to meter match)
            if (meter.isChecked) {
                cardView.cardElevation = dpToPx(1).toFloat()
            } else {
                cardView.cardElevation = dpToPx(3).toFloat()
            }

            // Handle item click - show meter information dialog
            itemView.setOnClickListener {
                onItemClick(meter)
            }
        }

        private fun formatMeterNumber(number: String): String {
            // Format meter number to fit in the badge nicely
            return when {
                number.length <= 3 -> number
                number.startsWith("M-") || number.startsWith("m-") -> number.substring(2).take(3)
                number.length > 3 -> number.take(3)
                else -> number
            }
        }

        private fun isLocationValid(location: String): Boolean {
            return location.isNotEmpty() &&
                    location.lowercase() != "unknown" &&
                    location.lowercase() != "n/a" &&
                    !location.contains("default", ignoreCase = true)
        }

        private fun isMeterNumberValid(number: String): Boolean {
            return number.isNotEmpty() &&
                    number != "0" &&
                    !number.contains("default", ignoreCase = true)
        }

        private fun getShortFileName(fileName: String): String {
            return if (fileName.length > 20) {
                fileName.take(17) + "..."
            } else {
                fileName
            }
        }

        private fun dpToPx(dp: Int): Int {
            val density = itemView.context.resources.displayMetrics.density
            return (dp * density).toInt()
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