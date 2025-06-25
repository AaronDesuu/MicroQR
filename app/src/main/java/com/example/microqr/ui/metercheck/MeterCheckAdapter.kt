package com.example.microqr.ui.metercheck

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.microqr.R
import com.example.microqr.ui.files.MeterStatus

class MeterCheckAdapter(
    // The lambda now receives the full MeterStatus object
    private val onCheckedChange: (meter: MeterStatus, isChecked: Boolean) -> Unit
) : ListAdapter<MeterStatus, MeterCheckAdapter.MeterViewHolder>(MeterDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MeterViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_check_meter, parent, false) // Ensure this layout has textViewSourceFile
        return MeterViewHolder(view)
    }

    override fun onBindViewHolder(holder: MeterViewHolder, position: Int) {
        val meter = getItem(position)
        holder.bind(meter, onCheckedChange)
    }

    class MeterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val serialNumberTextView: TextView = itemView.findViewById(R.id.textViewSerialNumber)
        private val isCheckedCheckBox: CheckBox = itemView.findViewById(R.id.checkboxIsChecked)
        private val statusIndicator: View = itemView.findViewById(R.id.status_indicator)
        private val statusText: TextView = itemView.findViewById(R.id.status_text)
        // New TextView for the source file
        private val sourceFileTextView: TextView = itemView.findViewById(R.id.textViewSourceFile)


        fun bind(meter: MeterStatus, onCheckedChange: (MeterStatus, Boolean) -> Unit) {
            serialNumberTextView.text = meter.serialNumber
            sourceFileTextView.text = "From: ${meter.fromFile}" // Display the source file

            updateStatusVisuals(meter.isChecked)

            // Crucial: Remove previous listener before setting checked state AND setting new listener
            isCheckedCheckBox.setOnCheckedChangeListener(null)
            isCheckedCheckBox.isChecked = meter.isChecked

            isCheckedCheckBox.setOnCheckedChangeListener { _, newCheckedState ->
                updateStatusVisuals(newCheckedState)
                // Pass the full meter object to the callback
                onCheckedChange(meter, newCheckedState)
            }
        }

        private fun updateStatusVisuals(isRegistered: Boolean) {
            val context = itemView.context
            if (isRegistered) {
                statusIndicator.backgroundTintList = ContextCompat.getColorStateList(context, R.color.success_green)
                statusText.text = context.getString(R.string.status_registered) // Use string resource
                statusText.setTextColor(ContextCompat.getColor(context, R.color.success_green))
            } else {
                statusIndicator.backgroundTintList = ContextCompat.getColorStateList(context, R.color.warning_orange)
                statusText.text = context.getString(R.string.status_unregistered) // Use string resource
                statusText.setTextColor(ContextCompat.getColor(context, R.color.warning_orange))
            }
        }
    }

    class MeterDiffCallback : DiffUtil.ItemCallback<MeterStatus>() {
        override fun areItemsTheSame(oldItem: MeterStatus, newItem: MeterStatus): Boolean {
            // Uniqueness is now defined by serialNumber AND fromFile
            return oldItem.serialNumber == newItem.serialNumber && oldItem.fromFile == newItem.fromFile
        }

        override fun areContentsTheSame(oldItem: MeterStatus, newItem: MeterStatus): Boolean {
            // Checks all relevant fields including isChecked and fromFile
            return oldItem == newItem
        }
    }
}