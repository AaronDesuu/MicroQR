package com.example.microqr.ui.files

import android.content.Context
import android.util.Log
import com.example.microqr.R
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

object CsvHelper {

    private const val TAG = "CsvHelper"

    /**
     * Required CSV column for serial number validation
     */
    private const val REQUIRED_SERIAL_COLUMN = "SerialNumber"

    /**
     * Validates if the CSV file has the correct format and required serial number column
     */
    fun validateCsvFormat(inputStream: InputStream?, context: Context): CsvValidationResult {
        if (inputStream == null) {
            return CsvValidationResult(
                false,
                listOf(REQUIRED_SERIAL_COLUMN),
                context.getString(R.string.csv_validation_input_stream_null)
            )
        }

        return try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val headerLine = reader.readLine()
                if (headerLine == null) {
                    return CsvValidationResult(
                        false,
                        listOf(REQUIRED_SERIAL_COLUMN),
                        context.getString(R.string.csv_validation_file_empty)
                    )
                }

                val headers = headerLine.split(",").map { it.trim().removeSurrounding("\"") }
                val hasSerialColumn = headers.any { header ->
                    header.equals(REQUIRED_SERIAL_COLUMN, ignoreCase = true)
                }

                if (hasSerialColumn) {
                    CsvValidationResult(true)
                } else {
                    CsvValidationResult(
                        false,
                        listOf(REQUIRED_SERIAL_COLUMN),
                        context.getString(R.string.csv_validation_missing_serial_column)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, context.getString(R.string.csv_validation_error_reading_file, e.message), e)
            CsvValidationResult(
                false,
                listOf(REQUIRED_SERIAL_COLUMN),
                context.getString(R.string.csv_validation_error_reading_file, e.message)
            )
        }
    }

    /**
     * Backward compatibility method for existing code
     */
    fun validateCsvFormat(inputStream: InputStream?): CsvValidationResult {
        if (inputStream == null) {
            return CsvValidationResult(false, listOf(REQUIRED_SERIAL_COLUMN), "Input stream is null")
        }

        return try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val headerLine = reader.readLine()
                if (headerLine == null) {
                    return CsvValidationResult(false, listOf(REQUIRED_SERIAL_COLUMN), "File is empty")
                }

                val headers = headerLine.split(",").map { it.trim().removeSurrounding("\"") }
                val hasSerialColumn = headers.any { header ->
                    header.equals(REQUIRED_SERIAL_COLUMN, ignoreCase = true)
                }

                if (hasSerialColumn) {
                    CsvValidationResult(true)
                } else {
                    CsvValidationResult(
                        false,
                        listOf(REQUIRED_SERIAL_COLUMN),
                        "Missing required column: $REQUIRED_SERIAL_COLUMN"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating CSV format: ${e.message}", e)
            CsvValidationResult(false, listOf(REQUIRED_SERIAL_COLUMN), "Error reading file: ${e.message}")
        }
    }

    /**
     * Processes CSV file and returns list of MeterStatus objects with serial numbers only
     * Other fields are populated with default values for compatibility
     */
    fun processCsvFile(inputStream: InputStream?, sourceFileName: String, context: Context): List<MeterStatus> {
        if (inputStream == null) {
            Log.e(TAG, context.getString(R.string.csv_processing_input_stream_null, sourceFileName))
            return emptyList()
        }

        val meters = mutableListOf<MeterStatus>()

        try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val headerLine = reader.readLine()
                if (headerLine == null) {
                    Log.w(TAG, context.getString(R.string.csv_processing_file_empty, sourceFileName))
                    return emptyList()
                }

                val headers = headerLine.split(",").map { it.trim().removeSurrounding("\"") }

                // Find serial number column index (case-insensitive)
                val serialIndex = headers.indexOfFirst { it.equals(REQUIRED_SERIAL_COLUMN, ignoreCase = true) }

                // Validate that serial number column was found
                if (serialIndex == -1) {
                    Log.e(TAG, context.getString(R.string.csv_processing_missing_serial_column, sourceFileName))
                    return emptyList()
                }

                var line: String?
                var lineNumber = 1
                var successfullyParsed = 0
                var skippedLines = 0

                while (reader.readLine().also { line = it } != null) {
                    lineNumber++

                    if (line.isNullOrBlank()) {
                        skippedLines++
                        continue
                    }

                    try {
                        val tokens = parseCsvLine(line!!)

                        // Check if we have enough columns
                        if (tokens.size <= serialIndex) {
                            Log.w(TAG, context.getString(R.string.csv_processing_insufficient_columns, lineNumber, tokens.size, serialIndex + 1))
                            skippedLines++
                            continue
                        }

                        // Extract and validate serial number
                        val serialNumber = tokens.getOrNull(serialIndex)?.takeIf { it.isNotBlank() }

                        if (serialNumber == null) {
                            Log.w(TAG, context.getString(R.string.csv_processing_blank_serial, lineNumber))
                            skippedLines++
                            continue
                        }

                        // Create MeterStatus with default values for compatibility
                        meters.add(
                            MeterStatus(
                                number = context.getString(R.string.default_meter_number),
                                serialNumber = serialNumber,
                                place = context.getString(R.string.default_meter_place),
                                registered = false, // Default to unregistered
                                fromFile = sourceFileName,
                                isChecked = false,
                                isSelectedForProcessing = false
                            )
                        )
                        successfullyParsed++

                    } catch (e: Exception) {
                        Log.w(TAG, context.getString(R.string.csv_processing_error_parsing_line, lineNumber, sourceFileName, e.message))
                        skippedLines++
                    }
                }

                Log.d(TAG, context.getString(R.string.csv_processing_summary, sourceFileName, successfullyParsed, skippedLines))
            }
        } catch (e: Exception) {
            Log.e(TAG, context.getString(R.string.csv_processing_error_processing_file, sourceFileName, e.message), e)
        }

        return meters
    }

    /**
     * Backward compatibility method for existing code
     */
    fun processCsvFile(inputStream: InputStream?, sourceFileName: String): List<MeterStatus> {
        if (inputStream == null) {
            Log.e(TAG, "Input stream is null for file: $sourceFileName")
            return emptyList()
        }

        val meters = mutableListOf<MeterStatus>()

        try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val headerLine = reader.readLine()
                if (headerLine == null) {
                    Log.w(TAG, "File is empty: $sourceFileName")
                    return emptyList()
                }

                val headers = headerLine.split(",").map { it.trim().removeSurrounding("\"") }

                // Find serial number column index (case-insensitive)
                val serialIndex = headers.indexOfFirst { it.equals(REQUIRED_SERIAL_COLUMN, ignoreCase = true) }

                // Validate that serial number column was found
                if (serialIndex == -1) {
                    Log.e(TAG, "Missing required SerialNumber column in file: $sourceFileName")
                    return emptyList()
                }

                var line: String?
                var lineNumber = 1
                var successfullyParsed = 0
                var skippedLines = 0

                while (reader.readLine().also { line = it } != null) {
                    lineNumber++

                    if (line.isNullOrBlank()) {
                        skippedLines++
                        continue
                    }

                    try {
                        val tokens = parseCsvLine(line!!)

                        // Check if we have enough columns
                        if (tokens.size <= serialIndex) {
                            Log.w(TAG, "Line $lineNumber has insufficient columns (${tokens.size} vs required ${serialIndex + 1})")
                            skippedLines++
                            continue
                        }

                        // Extract and validate serial number
                        val serialNumber = tokens.getOrNull(serialIndex)?.takeIf { it.isNotBlank() }

                        if (serialNumber == null) {
                            Log.w(TAG, "Line $lineNumber has blank serial number field")
                            skippedLines++
                            continue
                        }

                        // Create MeterStatus with default values for compatibility
                        meters.add(
                            MeterStatus(
                                number = "M${String.format("%03d", successfullyParsed + 1)}",
                                serialNumber = serialNumber,
                                place = "Unknown Location",
                                registered = false, // Default to unregistered
                                fromFile = sourceFileName,
                                isChecked = false,
                                isSelectedForProcessing = false
                            )
                        )
                        successfullyParsed++

                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing line $lineNumber in $sourceFileName: ${e.message}")
                        skippedLines++
                    }
                }

                Log.d(TAG, "Processed $sourceFileName: $successfullyParsed serial numbers parsed, $skippedLines lines skipped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing CSV file $sourceFileName: ${e.message}", e)
        }

        return meters
    }

    /**
     * Parses a CSV line handling quoted fields and commas within quotes
     */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val currentField = StringBuilder()
        var insideQuotes = false
        var i = 0

        while (i < line.length) {
            val char = line[i]

            when {
                char == '"' && (i == 0 || line[i - 1] == ',') -> {
                    // Start of quoted field
                    insideQuotes = true
                }
                char == '"' && insideQuotes && (i == line.length - 1 || line[i + 1] == ',') -> {
                    // End of quoted field
                    insideQuotes = false
                }
                char == '"' && insideQuotes && line.getOrNull(i + 1) == '"' -> {
                    // Escaped quote within quoted field
                    currentField.append('"')
                    i++ // Skip the next quote
                }
                char == ',' && !insideQuotes -> {
                    // Field separator
                    result.add(currentField.toString().trim())
                    currentField.clear()
                }
                else -> {
                    // Regular character
                    currentField.append(char)
                }
            }
            i++
        }

        // Add the last field
        result.add(currentField.toString().trim())

        return result
    }

    /**
     * Validates if a CSV line has the required serial number column
     */
    fun validateCsvLine(line: String, serialColumnIndex: Int): Boolean {
        if (line.isBlank()) return false

        val tokens = parseCsvLine(line)
        return tokens.size > serialColumnIndex && tokens[serialColumnIndex].isNotBlank()
    }

    /**
     * Backward compatibility method for existing code
     */
//    fun validateCsvLine(line: String, expectedColumns: Int = 1): Boolean {
//        if (line.isBlank()) return false
//
//        val tokens = parseCsvLine(line)
//        return tokens.size >= expectedColumns && tokens.any { it.isNotBlank() }
//    }

    /**
     * Gets CSV format requirements as a formatted string for user guidance
     */
    fun getCsvFormatRequirements(context: Context): String {
        return context.getString(R.string.csv_format_requirements)
    }

    /**
     * Backward compatibility method for existing code
     */
    fun getCsvFormatRequirements(): String {
        return """
            📋 CSV Format Requirements:
            
            Required Column:
            • SerialNumber - Unique serial number (e.g., SN123456789, 2203312)
            
            Example CSV Content:
            SerialNumber
            2203312
            SN987654321
            MTR-001-ABC
            
            Notes:
            • Header row is required
            • Comma-separated values
            • Text containing commas should be quoted
            • Empty lines are ignored
            • Invalid lines are skipped with warnings
        """.trimIndent()
    }

    /**
     * Sanitizes filename for safe storage
     */
    fun sanitizeFileName(fileName: String): String {
        return fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            .take(100) // Limit length
            .let { if (it.endsWith(".csv", ignoreCase = true)) it else "$it.csv" }
    }

    /**
     * Checks if file extension is valid for CSV processing
     */
    fun isValidCsvExtension(fileName: String): Boolean {
        val validExtensions = listOf(".csv", ".txt")
        return validExtensions.any { fileName.lowercase().endsWith(it) }
    }

    /**
     * Validates file size (used with external file size checking)
     */
    fun isFileSizeValid(fileSizeBytes: Long, maxSizeMB: Int = 10): Boolean {
        val maxSizeBytes = maxSizeMB * 1024 * 1024
        return fileSizeBytes <= maxSizeBytes
    }

    /**
     * Formats file size for display
     */
    fun formatFileSize(sizeBytes: Long, context: Context): String {
        return when {
            sizeBytes < 1024 -> context.getString(R.string.file_size_bytes, sizeBytes)
            sizeBytes < 1024 * 1024 -> context.getString(R.string.file_size_kb, String.format("%.1f", sizeBytes / 1024.0))
            sizeBytes < 1024 * 1024 * 1024 -> context.getString(R.string.file_size_mb, String.format("%.1f", sizeBytes / (1024.0 * 1024.0)))
            else -> context.getString(R.string.file_size_gb, String.format("%.1f", sizeBytes / (1024.0 * 1024.0 * 1024.0)))
        }
    }

    /**
     * Backward compatibility method for existing code
     */
    fun formatFileSize(sizeBytes: Long): String {
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${String.format("%.1f", sizeBytes / 1024.0)} KB"
            sizeBytes < 1024 * 1024 * 1024 -> "${String.format("%.1f", sizeBytes / (1024.0 * 1024.0))} MB"
            else -> "${String.format("%.1f", sizeBytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    /**
     * Gets the required column name
     */
    fun getRequiredColumn(): String = REQUIRED_SERIAL_COLUMN

    /**
     * Backward compatibility method for existing code
     */
    fun getRequiredColumns(): List<String> = listOf(REQUIRED_SERIAL_COLUMN)

    /**
     * Validates CSV content preview (first few lines)
     */
    fun validateCsvPreview(inputStream: InputStream?, maxLines: Int = 5, context: Context): String {
        if (inputStream == null) return context.getString(R.string.csv_preview_cannot_read_file)

        return try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val lines = mutableListOf<String>()
                var line: String?
                var count = 0

                while (reader.readLine().also { line = it } != null && count < maxLines) {
                    lines.add(line!!)
                    count++
                }

                if (lines.isEmpty()) {
                    context.getString(R.string.csv_preview_file_empty)
                } else {
                    context.getString(R.string.csv_preview_content, lines.joinToString("\n"))
                }
            }
        } catch (e: Exception) {
            context.getString(R.string.csv_preview_error_reading_file, e.message)
        }
    }

    /**
     * Backward compatibility method for existing code
     */
    fun validateCsvPreview(inputStream: InputStream?, maxLines: Int = 5): String {
        if (inputStream == null) return "Cannot read file"

        return try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val lines = mutableListOf<String>()
                var line: String?
                var count = 0

                while (reader.readLine().also { line = it } != null && count < maxLines) {
                    lines.add(line!!)
                    count++
                }

                if (lines.isEmpty()) {
                    "File is empty"
                } else {
                    "Preview:\n${lines.joinToString("\n")}"
                }
            }
        } catch (e: Exception) {
            "Error reading file: ${e.message}"
        }
    }
}