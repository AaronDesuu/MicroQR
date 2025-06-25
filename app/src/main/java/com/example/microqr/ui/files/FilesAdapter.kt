package com.example.microqr.ui.files

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.microqr.R
import com.google.android.material.button.MaterialButton

class FilesAdapter(
    private val onDeleteClicked: (String) -> Unit,
    private val onProcessForMeterCheck: (String) -> Unit,
    private val onProcessForMatch: (String) -> Unit
) : ListAdapter<FileItem, FilesAdapter.FileViewHolder>(FileDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view, onDeleteClicked, onProcessForMeterCheck, onProcessForMatch)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val fileItem = getItem(position)
        holder.bind(fileItem)
    }

    class FileViewHolder(
        itemView: View,
        private val onDeleteClicked: (String) -> Unit,
        private val onProcessForMeterCheck: (String) -> Unit,
        private val onProcessForMatch: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val fileNameTextView: TextView = itemView.findViewById(R.id.textView_file_name)
        private val fileDateTextView: TextView = itemView.findViewById(R.id.textView_file_date)
        private val meterCountTextView: TextView = itemView.findViewById(R.id.textView_meter_count)
        private val statusTextView: TextView = itemView.findViewById(R.id.textView_status)

        private val deleteButton: MaterialButton = itemView.findViewById(R.id.button_delete_file)
        private val meterCheckButton: MaterialButton = itemView.findViewById(R.id.button_meter_check)
        private val matchButton: MaterialButton = itemView.findViewById(R.id.button_match)

        fun bind(fileItem: FileItem) {
            fileNameTextView.text = fileItem.fileName
            fileDateTextView.text = fileItem.uploadDate
            meterCountTextView.text = "${fileItem.meterCount} meters"

            // Use the extension function for better readability
            val isProcessable = fileItem.isProcessable()

            // Set status based on validation and destination
            if (fileItem.isValid) {
                when (fileItem.destination) {
                    ProcessingDestination.METER_CHECK -> {
                        statusTextView.text = "✅ MeterCheck"
                        statusTextView.setTextColor(itemView.context.getColor(android.R.color.holo_blue_dark))
                        // Change button text to indicate reprocessing
                        meterCheckButton.text = "Reprocess"
                        matchButton.text = "Switch to Match"
                    }
                    ProcessingDestination.METER_MATCH -> {
                        statusTextView.text = "✅ MeterMatch"
                        statusTextView.setTextColor(itemView.context.getColor(android.R.color.holo_orange_dark))
                        // Change button text to indicate reprocessing
                        meterCheckButton.text = "Switch to Check"
                        matchButton.text = "Reprocess"
                    }
                    null -> {
                        if (isProcessable) {
                            statusTextView.text = "Valid • Ready to process"
                            statusTextView.setTextColor(itemView.context.getColor(android.R.color.holo_green_dark))
                        } else {
                            statusTextView.text = "Valid but empty"
                            statusTextView.setTextColor(itemView.context.getColor(android.R.color.darker_gray))
                        }
                        // Default button text
                        meterCheckButton.text = "MeterCheck"
                        matchButton.text = "Match"
                    }
                }
                meterCheckButton.isEnabled = isProcessable
                matchButton.isEnabled = isProcessable
            } else {
                statusTextView.text = "❌ Invalid: ${fileItem.validationError}"
                statusTextView.setTextColor(itemView.context.getColor(android.R.color.holo_red_dark))
                meterCheckButton.isEnabled = false
                matchButton.isEnabled = false
                meterCheckButton.text = "MeterCheck"
                matchButton.text = "Match"
            }

            deleteButton.setOnClickListener {
                onDeleteClicked(fileItem.fileName)
            }

            meterCheckButton.setOnClickListener {
                if (isProcessable) {
                    onProcessForMeterCheck(fileItem.fileName)
                }
            }

            matchButton.setOnClickListener {
                if (isProcessable) {
                    onProcessForMatch(fileItem.fileName)
                }
            }
        }
    }
}

class FileDiffCallback : DiffUtil.ItemCallback<FileItem>() {
    override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
        return oldItem.fileName == newItem.fileName
    }

    override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
        return oldItem == newItem
    }
}