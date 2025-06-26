package com.example.microqr.ui.files

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.microqr.R
import com.example.microqr.utils.DateUtils
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
            val context = itemView.context

            fileNameTextView.text = fileItem.fileName

            // Use localized date formatting
            fileDateTextView.text = DateUtils.formatFileDate(context, fileItem.uploadDate)

            meterCountTextView.text = context.getString(R.string.files_meter_count, fileItem.meterCount)

            // Use the extension function for better readability
            val isProcessable = fileItem.isProcessable()

            // Set status based on validation and destination
            if (fileItem.isValid) {
                when (fileItem.destination) {
                    ProcessingDestination.METER_CHECK -> {
                        statusTextView.text = context.getString(R.string.files_status_meter_check)
                        statusTextView.setTextColor(context.getColor(android.R.color.holo_blue_dark))
                        // Change button text to indicate reprocessing
                        meterCheckButton.text = context.getString(R.string.files_button_reprocess)
                        matchButton.text = context.getString(R.string.files_button_switch_to_match)
                    }
                    ProcessingDestination.METER_MATCH -> {
                        statusTextView.text = context.getString(R.string.files_status_meter_match)
                        statusTextView.setTextColor(context.getColor(android.R.color.holo_orange_dark))
                        // Change button text to indicate reprocessing
                        meterCheckButton.text = context.getString(R.string.files_button_switch_to_check)
                        matchButton.text = context.getString(R.string.files_button_reprocess)
                    }
                    null -> {
                        if (isProcessable) {
                            statusTextView.text = context.getString(R.string.files_status_valid_ready)
                            statusTextView.setTextColor(context.getColor(android.R.color.holo_green_dark))
                        } else {
                            statusTextView.text = context.getString(R.string.files_status_valid_empty)
                            statusTextView.setTextColor(context.getColor(android.R.color.darker_gray))
                        }
                        // Default button text
                        meterCheckButton.text = context.getString(R.string.files_button_meter_check)
                        matchButton.text = context.getString(R.string.files_button_match)
                    }
                }
                meterCheckButton.isEnabled = isProcessable
                matchButton.isEnabled = isProcessable
            } else {
                statusTextView.text = context.getString(R.string.files_status_invalid, fileItem.validationError)
                statusTextView.setTextColor(context.getColor(android.R.color.holo_red_dark))
                meterCheckButton.isEnabled = false
                matchButton.isEnabled = false
                meterCheckButton.text = context.getString(R.string.files_button_meter_check)
                matchButton.text = context.getString(R.string.files_button_match)
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