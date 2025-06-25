package com.example.microqr.ui.metermatch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.microqr.R
import com.example.microqr.ui.files.MeterStatus

class MeterMatchAdapter(
    private val onItemClick: (MeterStatus) -> Unit = {},
    private val onCheckChanged: (MeterStatus, Boolean) -> Unit = { _, _ -> }
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
        private val checkboxIsChecked: CheckBox = itemView.findViewById(R.id.checkboxIsChecked)

        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }

            checkboxIsChecked.setOnCheckedChangeListener { _, isChecked ->
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onCheckChanged(getItem(position), isChecked)
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

            // Set checkbox state
            checkboxIsChecked.isChecked = meter.isSelectedForProcessing

            // Determine status based on registered and isChecked
            val (statusText, statusColor, indicatorColor) = when {
                meter.isChecked && meter.registered -> {
                    Triple("Scanned & Registered", R.color.success_green, R.color.success_green)
                }
                meter.isChecked && !meter.registered -> {
                    Triple("Scanned", R.color.purple_700, R.color.purple_700)
                }
                !meter.isChecked && meter.registered -> {
                    Triple("Registered", R.color.warning_orange, R.color.warning_orange)
                }
                else -> {
                    Triple("Pending", R.color.secondary_text_color, R.color.secondary_text_color)
                }
            }

            // Set status text and color
            tvMatchStatus.text = statusText
            tvMatchStatus.setTextColor(ContextCompat.getColor(itemView.context, statusColor))

            // Set status indicator color
            statusIndicator.backgroundTintList = ContextCompat.getColorStateList(itemView.context, indicatorColor)

            // Set QR status icon
            if (meter.isChecked) {
                ivQrStatus.visibility = View.VISIBLE
                ivQrStatus.setImageResource(R.drawable.ic_qr_code_scanner_24)
                ivQrStatus.setColorFilter(ContextCompat.getColor(itemView.context, R.color.success_green))
            } else {
                ivQrStatus.visibility = View.GONE
            }

            // Set card background based on status
            val cardBackgroundTint = when {
                meter.isChecked && meter.registered -> R.color.success_green
                meter.isChecked -> R.color.purple_200
                meter.registered -> R.color.warning_orange
                else -> android.R.color.transparent
            }

            itemView.backgroundTintList = ContextCompat.getColorStateList(itemView.context, cardBackgroundTint)
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