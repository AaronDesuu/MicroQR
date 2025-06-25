package com.example.microqr.ui.files

import android.content.Context
import androidx.appcompat.app.AlertDialog

object FileUtils {

    fun getFileSizeInMB(file: java.io.File): Double {
        return file.length().toDouble() / (1024 * 1024)
    }

    fun isFileSizeValid(file: java.io.File): Boolean {
        return file.length() <= FileConstants.MAX_FILE_SIZE_BYTES
    }

    fun formatFileSize(sizeInBytes: Long): String {
        return when {
            sizeInBytes < 1024 -> "$sizeInBytes B"
            sizeInBytes < 1024 * 1024 -> "${sizeInBytes / 1024} KB"
            else -> "${"%.1f".format(sizeInBytes.toDouble() / (1024 * 1024))} MB"
        }
    }
}

object DialogHelper {

    fun showDestinationDialog(
        context: Context,
        onMeterCheck: () -> Unit,
        onMeterMatch: () -> Unit,
        onCancel: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle("Choose Processing Destination")
            .setMessage("Where would you like to process this CSV file?")
            .setPositiveButton("MeterCheck") { _, _ -> onMeterCheck() }
            .setNegativeButton("MeterMatch") { _, _ -> onMeterMatch() }
            .setNeutralButton("Cancel") { _, _ -> onCancel() }
            .setCancelable(false)
            .show()
    }

    fun showDeleteConfirmation(
        context: Context,
        fileName: String,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle("Delete File")
            .setMessage("Are you sure you want to delete '$fileName'?\n\nThis action cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .show()
    }
}