package com.example.microqr.ui.files

/**
 * Extension functions for ProcessingDestination
 */
fun ProcessingDestination.toDisplayString(): String = this.displayName

/**
 * Extension functions for FileItem
 */
fun FileItem.isProcessable(): Boolean = this.isValid && this.meterCount > 0

/**
 * Extension functions for List<FileItem>
 */
fun List<FileItem>.getTotalMeterCount(): Int = this.sumOf { it.meterCount }

fun List<FileItem>.getValidFileCount(): Int = this.count { it.isValid }

fun List<FileItem>.getInvalidFileCount(): Int = this.count { !it.isValid }

fun List<FileItem>.hasAnyValidFiles(): Boolean = this.any { it.isValid }

fun List<FileItem>.getProcessableFiles(): List<FileItem> = this.filter { it.isProcessable() }

/**
 * Extension functions for MeterStatus (additional ones not in CsvHelper)
 */
fun List<MeterStatus>.getSelectedForProcessingCount(): Int {
    return this.count { it.isSelectedForProcessing }
}

fun List<MeterStatus>.groupByRegistrationStatus(): Map<Boolean, List<MeterStatus>> {
    return this.groupBy { it.registered }
}

fun List<MeterStatus>.findBySerialNumber(serialNumber: String): MeterStatus? {
    return this.find { it.serialNumber == serialNumber }
}

/**
 * Extension functions for String (file-related)
 */
fun String.isValidCsvFileName(): Boolean {
    val validExtensions = listOf(".csv", ".txt")
    return validExtensions.any { this.lowercase().endsWith(it) }
}

fun String.sanitizeForFileName(): String {
    return this.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
}

/**
 * Extension functions for Uri
 */
fun android.net.Uri.getFileName(contentResolver: android.content.ContentResolver): String? {
    var fileName: String? = null
    try {
        contentResolver.query(this, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    if (fileName == null) {
        fileName = this.path
        val cut = fileName?.lastIndexOf('/')
        if (cut != null && cut != -1) {
            fileName = fileName?.substring(cut + 1)
        }
    }

    return fileName?.takeIf { it.isNotBlank() }?.sanitizeForFileName()
}