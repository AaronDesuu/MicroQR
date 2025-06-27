package com.example.microqr.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.microqr.ui.files.MeterStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Comprehensive utility class for CSV export operations with advanced filtering and file management
 */
object CsvExportHelper {

    private const val TAG = "CsvExportHelper"
    private const val EXPORT_FOLDER_NAME = "MeterExports"
    private const val MAX_BACKUP_FILES = 5
    private const val BYTES_PER_RECORD_ESTIMATE = 120 // Rough estimation including headers and formatting

    /**
     * Export configuration data class
     */
    data class ExportConfig(
        val filename: String,
        val includeTimestamp: Boolean = true,
        val includeHeaders: Boolean = true,
        val selectedFiles: Set<String> = emptySet(),
        val selectedPlaces: Set<String> = emptySet(),
        val registrationFilter: RegistrationFilter = RegistrationFilter.ALL,
        val checkFilter: CheckFilter = CheckFilter.ALL,
        val sortBy: SortOption = SortOption.METER_NUMBER,
        val sortAscending: Boolean = true,
        val customHeaders: Map<String, String> = emptyMap(), // Custom header mappings
        val includeExportMetadata: Boolean = true
    )

    /**
     * Filter enums for better type safety
     */
    enum class RegistrationFilter {
        ALL, REGISTERED_ONLY, UNREGISTERED_ONLY
    }

    enum class CheckFilter {
        ALL, CHECKED_ONLY, UNCHECKED_ONLY
    }

    enum class SortOption {
        METER_NUMBER, SERIAL_NUMBER, PLACE, SOURCE_FILE, REGISTRATION_STATUS, CHECK_STATUS
    }

    /**
     * Export result sealed class
     */
    sealed class ExportResult {
        data class Success(
            val filePath: String,
            val recordCount: Int,
            val fileSize: Long
        ) : ExportResult()

        data class Error(val message: String, val exception: Throwable? = null) : ExportResult()
        object NoData : ExportResult()
        object Cancelled : ExportResult()
    }

    /**
     * Progress callback interface
     */
    interface ProgressCallback {
        fun onStarted(totalRecords: Int)
        fun onProgress(current: Int, total: Int, message: String)
        fun onCompleted()
        fun onError(error: String)
    }

    /**
     * Creates and ensures the export directory exists
     */
    fun createExportDirectory(): File {
        val exportDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            EXPORT_FOLDER_NAME
        )

        if (!exportDir.exists()) {
            val created = exportDir.mkdirs()
            if (!created) {
                Log.w(TAG, "Failed to create export directory: ${exportDir.absolutePath}")
            }
        }

        return exportDir
    }

    /**
     * Generates a filename with optional timestamp
     */
    fun generateFilename(baseName: String, includeTimestamp: Boolean = true): String {
        val cleanBaseName = sanitizeFilename(baseName)
        return if (includeTimestamp) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            "${cleanBaseName}_$timestamp.csv"
        } else {
            "$cleanBaseName.csv"
        }
    }

    /**
     * Sanitizes filename by removing invalid characters
     */
    fun sanitizeFilename(filename: String): String {
        val invalidChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        var sanitized = filename.trim()

        invalidChars.forEach { char ->
            sanitized = sanitized.replace(char, '_')
        }

        // Remove multiple consecutive underscores and trim
        sanitized = sanitized.replace(Regex("_{2,}"), "_")
            .removePrefix("_")
            .removeSuffix("_")

        return if (sanitized.isBlank()) "meter_export" else sanitized
    }

    /**
     * Validates filename for invalid characters and length
     */
    fun isValidFilename(filename: String): Boolean {
        if (filename.isBlank() || filename.length > 255) return false

        val invalidChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        return !filename.any { it in invalidChars }
    }

    /**
     * Estimates file size based on record count
     */
    fun estimateFileSize(recordCount: Int): String {
        val estimatedBytes = recordCount * BYTES_PER_RECORD_ESTIMATE + 1024 // Extra 1KB for headers

        return when {
            estimatedBytes < 1024 -> "$estimatedBytes B"
            estimatedBytes < 1024 * 1024 -> "${(estimatedBytes / 1024.0).format(1)} KB"
            estimatedBytes < 1024 * 1024 * 1024 -> "${(estimatedBytes / (1024.0 * 1024.0)).format(1)} MB"
            else -> "${(estimatedBytes / (1024.0 * 1024.0 * 1024.0)).format(1)} GB"
        }
    }

    /**
     * Extension function to format Double to specified decimal places
     */
    private fun Double.format(digits: Int) = "%.${digits}f".format(this)

    /**
     * Main export function with comprehensive error handling and progress reporting
     */
    suspend fun exportMeterDataToCsv(
        data: List<MeterStatus>,
        config: ExportConfig,
        progressCallback: ProgressCallback? = null
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            progressCallback?.onStarted(data.size)

            // Apply filters and sorting
            val filteredData = applyFilters(data, config)
            if (filteredData.isEmpty()) {
                progressCallback?.onError("No data matches the selected filters")
                return@withContext ExportResult.NoData
            }

            val sortedData = applySorting(filteredData, config.sortBy, config.sortAscending)

            // Create export file
            val exportDir = createExportDirectory()
            val filename = generateFilename(config.filename, config.includeTimestamp)
            val file = File(exportDir, filename)

            // Backup existing file if it exists
            if (file.exists()) {
                backupExistingFile(file)
            }

            // Write CSV data
            val success = writeCsvFile(file, sortedData, config, progressCallback)

            if (success) {
                cleanupOldBackups(exportDir)
                progressCallback?.onCompleted()

                ExportResult.Success(
                    filePath = file.absolutePath,
                    recordCount = sortedData.size,
                    fileSize = file.length()
                )
            } else {
                progressCallback?.onError("Failed to write CSV file")
                ExportResult.Error("Failed to write CSV file")
            }

        } catch (e: IOException) {
            Log.e(TAG, "IO error during export", e)
            progressCallback?.onError("File write error: ${e.message}")
            ExportResult.Error("File write error: ${e.message}", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error during export", e)
            progressCallback?.onError("Permission denied: ${e.message}")
            ExportResult.Error("Permission denied: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during export", e)
            progressCallback?.onError("Export failed: ${e.message}")
            ExportResult.Error("Export failed: ${e.message}", e)
        }
    }

    /**
     * Writes meter data to CSV file with progress reporting
     */
    private suspend fun writeCsvFile(
        file: File,
        data: List<MeterStatus>,
        config: ExportConfig,
        progressCallback: ProgressCallback?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            progressCallback?.onProgress(0, data.size, "Starting export...")

            FileWriter(file, Charsets.UTF_8).use { writer ->
                // Write BOM for UTF-8 (helps with Excel compatibility)
                writer.write("\uFEFF")

                // Write headers
                if (config.includeHeaders) {
                    writer.write(buildCsvHeaders(config.customHeaders))
                    writer.write("\n")
                }

                // Write metadata if enabled
                if (config.includeExportMetadata) {
                    writeMetadataComments(writer, data.size, config)
                }

                val exportTimestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                // Write data rows
                data.forEachIndexed { index, meter ->
                    writer.write(buildCsvRow(meter, exportTimestamp))
                    writer.write("\n")

                    // Report progress every 25 records or at the end
                    if (index % 25 == 0 || index == data.size - 1) {
                        val percentage = ((index + 1) * 100) / data.size
                        progressCallback?.onProgress(
                            index + 1,
                            data.size,
                            "Exported ${index + 1} of ${data.size} records ($percentage%)"
                        )
                    }
                }

                writer.flush()
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write CSV file", e)
            false
        }
    }

    /**
     * Builds CSV header row with custom mappings
     */
    private fun buildCsvHeaders(customHeaders: Map<String, String> = emptyMap()): String {
        val defaultHeaders = mapOf(
            "Number" to "Number",
            "SerialNumber" to "SerialNumber",
            "Place" to "Place",
            "Registered" to "Registered",
            "Checked" to "Checked",
            "SourceFile" to "SourceFile",
            "ExportDate" to "ExportDate"
        )

        val headers = defaultHeaders.toMutableMap()
        headers.putAll(customHeaders)

        return headers.values.joinToString(",") { escapeForCsv(it) }
    }

    /**
     * Writes metadata comments to CSV file
     */
    private fun writeMetadataComments(writer: FileWriter, recordCount: Int, config: ExportConfig) {
        writer.write("# CSV Export Metadata\n")
        writer.write("# Export Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        writer.write("# Total Records: $recordCount\n")
        writer.write("# Filters Applied:\n")

        if (config.selectedFiles.isNotEmpty()) {
            writer.write("# - Files: ${config.selectedFiles.joinToString(", ")}\n")
        }

        if (config.selectedPlaces.isNotEmpty()) {
            writer.write("# - Places: ${config.selectedPlaces.take(5).joinToString(", ")}${if (config.selectedPlaces.size > 5) "..." else ""}\n")
        }

        if (config.registrationFilter != RegistrationFilter.ALL) {
            writer.write("# - Registration: ${config.registrationFilter}\n")
        }

        if (config.checkFilter != CheckFilter.ALL) {
            writer.write("# - Check Status: ${config.checkFilter}\n")
        }

        writer.write("# Sorted By: ${config.sortBy} (${if (config.sortAscending) "Ascending" else "Descending"})\n")
        writer.write("#\n")
    }

    /**
     * Builds a CSV row for a meter record
     */
    private fun buildCsvRow(meter: MeterStatus, exportTimestamp: String): String {
        return buildString {
            append(escapeForCsv(meter.number))
            append(",")
            append(escapeForCsv(meter.serialNumber))
            append(",")
            append(escapeForCsv(meter.place))
            append(",")
            append(meter.registered)
            append(",")
            append(meter.isChecked)
            append(",")
            append(escapeForCsv(meter.fromFile))
            append(",")
            append(exportTimestamp)
        }
    }

    /**
     * Escapes special characters for CSV format
     */
    private fun escapeForCsv(value: String): String {
        val trimmedValue = value.trim()
        return if (trimmedValue.contains(",") ||
            trimmedValue.contains("\"") ||
            trimmedValue.contains("\n") ||
            trimmedValue.contains("\r")) {
            "\"${trimmedValue.replace("\"", "\"\"")}\""
        } else {
            trimmedValue
        }
    }

    /**
     * Applies filters to meter data based on export configuration
     */
    fun applyFilters(data: List<MeterStatus>, config: ExportConfig): List<MeterStatus> {
        var filteredData = data

        // Filter by selected files
        if (config.selectedFiles.isNotEmpty()) {
            filteredData = filteredData.filter { it.fromFile in config.selectedFiles }
        }

        // Filter by selected places
        if (config.selectedPlaces.isNotEmpty()) {
            filteredData = filteredData.filter { it.place in config.selectedPlaces }
        }

        // Filter by registration status
        filteredData = when (config.registrationFilter) {
            RegistrationFilter.REGISTERED_ONLY -> filteredData.filter { it.registered }
            RegistrationFilter.UNREGISTERED_ONLY -> filteredData.filter { !it.registered }
            RegistrationFilter.ALL -> filteredData
        }

        // Filter by check status
        filteredData = when (config.checkFilter) {
            CheckFilter.CHECKED_ONLY -> filteredData.filter { it.isChecked }
            CheckFilter.UNCHECKED_ONLY -> filteredData.filter { !it.isChecked }
            CheckFilter.ALL -> filteredData
        }

        return filteredData
    }

    /**
     * Applies sorting to filtered data
     */
    private fun applySorting(
        data: List<MeterStatus>,
        sortBy: SortOption,
        ascending: Boolean
    ): List<MeterStatus> {
        val comparator = when (sortBy) {
            SortOption.METER_NUMBER -> compareBy<MeterStatus> { it.number }
            SortOption.SERIAL_NUMBER -> compareBy { it.serialNumber }
            SortOption.PLACE -> compareBy { it.place }
            SortOption.SOURCE_FILE -> compareBy { it.fromFile }
            SortOption.REGISTRATION_STATUS -> compareBy { it.registered }
            SortOption.CHECK_STATUS -> compareBy { it.isChecked }
        }

        return if (ascending) {
            data.sortedWith(comparator)
        } else {
            data.sortedWith(comparator.reversed())
        }
    }

    /**
     * Validates export configuration
     */
    fun validateExportConfig(config: ExportConfig): List<String> {
        val errors = mutableListOf<String>()

        if (config.filename.isBlank()) {
            errors.add("Filename cannot be empty")
        }

        if (!isValidFilename(config.filename)) {
            errors.add("Filename contains invalid characters")
        }

        if (config.filename.length > 200) { // Leave room for timestamp
            errors.add("Filename is too long")
        }

        return errors
    }

    /**
     * Creates a summary of what will be exported
     */
    fun createExportSummary(
        originalDataSize: Int,
        filteredDataSize: Int,
        config: ExportConfig
    ): String {
        return buildString {
            appendLine("Export Summary:")
            appendLine("• Total available records: $originalDataSize")
            appendLine("• Records to export: $filteredDataSize")
            appendLine("• Estimated file size: ${estimateFileSize(filteredDataSize)}")

            if (config.selectedFiles.isNotEmpty()) {
                appendLine("• Filtered by files: ${config.selectedFiles.size} file(s)")
            }

            if (config.selectedPlaces.isNotEmpty()) {
                appendLine("• Filtered by places: ${config.selectedPlaces.size} location(s)")
            }

            if (config.registrationFilter != RegistrationFilter.ALL) {
                appendLine("• Registration filter: ${config.registrationFilter}")
            }

            if (config.checkFilter != CheckFilter.ALL) {
                appendLine("• Check status filter: ${config.checkFilter}")
            }

            appendLine("• Sort order: ${config.sortBy} (${if (config.sortAscending) "Ascending" else "Descending"})")
        }
    }

    /**
     * Backup existing file if it exists
     */
    private fun backupExistingFile(file: File): Boolean {
        if (!file.exists()) return true

        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val backupFile = File(file.parent, "${file.nameWithoutExtension}_backup_$timestamp.csv")
            file.copyTo(backupFile, overwrite = false)
            Log.d(TAG, "Backup created: ${backupFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create backup", e)
            false
        }
    }

    /**
     * Cleans up old backup files (keeps only the most recent MAX_BACKUP_FILES)
     */
    private fun cleanupOldBackups(directory: File) {
        try {
            val backupFiles = directory.listFiles { _, name ->
                name.contains("_backup_") && name.endsWith(".csv")
            }?.sortedByDescending { it.lastModified() }

            backupFiles?.drop(MAX_BACKUP_FILES)?.forEach { file ->
                if (file.delete()) {
                    Log.d(TAG, "Deleted old backup: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old backups", e)
        }
    }

    /**
     * Gets available export formats (for future extensibility)
     */
    fun getSupportedFormats(): List<String> {
        return listOf("CSV")
    }

    /**
     * Checks available storage space
     */
    fun checkAvailableSpace(recordCount: Int): Boolean {
        return try {
            val exportDir = createExportDirectory()
            val estimatedSize = recordCount * BYTES_PER_RECORD_ESTIMATE
            val availableSpace = exportDir.freeSpace

            availableSpace > estimatedSize * 2 // Require 2x the estimated size as buffer
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check available space", e)
            false
        }
    }

    /**
     * Quick export with minimal configuration
     */
    suspend fun quickExport(
        data: List<MeterStatus>,
        filename: String = "quick_export",
        progressCallback: ProgressCallback? = null
    ): ExportResult {
        val config = ExportConfig(filename = filename)
        return exportMeterDataToCsv(data, config, progressCallback)
    }
}