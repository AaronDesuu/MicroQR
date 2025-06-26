package com.example.microqr.ui.files

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

object CsvHelper {

    private const val TAG = "CsvHelper"

    /**
     * Required CSV columns for meter data
     */
    private val REQUIRED_COLUMNS = listOf("Number", "SerialNumber", "Place", "Registered")

    /**
     * Validates if the CSV file has the correct format and required columns
     */
    fun validateCsvFormat(inputStream: InputStream?): CsvValidationResult {
        if (inputStream == null) {
            return CsvValidationResult(false, emptyList(), "Input stream is null")
        }

        return try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val headerLine = reader.readLine()
                if (headerLine == null) {
                    return CsvValidationResult(false, emptyList(), "File is empty")
                }

                val headers = headerLine.split(",").map { it.trim().removeSurrounding("\"") }
                val missingColumns = REQUIRED_COLUMNS.filter { required ->
                    !headers.any { header -> header.equals(required, ignoreCase = true) }
                }

                if (missingColumns.isEmpty()) {
                    CsvValidationResult(true)
                } else {
                    CsvValidationResult(
                        false,
                        missingColumns,
                        "Missing required columns: ${missingColumns.joinToString(", ")}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating CSV format: ${e.message}", e)
            CsvValidationResult(false, emptyList(), "Error reading file: ${e.message}")
        }
    }

    /**
     * Processes CSV file and returns list of MeterStatus objects
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

                // Find column indices (case-insensitive)
                val numberIndex = headers.indexOfFirst { it.equals("Number", ignoreCase = true) }
                val serialIndex = headers.indexOfFirst { it.equals("SerialNumber", ignoreCase = true) }
                val placeIndex = headers.indexOfFirst { it.equals("Place", ignoreCase = true) }
                val registeredIndex = headers.indexOfFirst { it.equals("Registered", ignoreCase = true) }

                // Validate that all required columns were found
                if (numberIndex == -1 || serialIndex == -1 || placeIndex == -1 || registeredIndex == -1) {
                    Log.e(TAG, "Missing required columns in file: $sourceFileName")
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
                        val maxIndex = maxOf(numberIndex, serialIndex, placeIndex, registeredIndex)
                        if (tokens.size <= maxIndex) {
                            Log.w(TAG, "Line $lineNumber has insufficient columns (${tokens.size} vs required ${maxIndex + 1})")
                            skippedLines++
                            continue
                        }

                        // Extract and validate data
                        val number = tokens.getOrNull(numberIndex)?.takeIf { it.isNotBlank() }
                        val serial = tokens.getOrNull(serialIndex)?.takeIf { it.isNotBlank() }
                        val place = tokens.getOrNull(placeIndex)?.takeIf { it.isNotBlank() }
                        val registeredStr = tokens.getOrNull(registeredIndex)?.takeIf { it.isNotBlank() }

                        if (number == null || serial == null || place == null || registeredStr == null) {
                            Log.w(TAG, "Line $lineNumber has blank required fields")
                            skippedLines++
                            continue
                        }

                        val registered = parseBoolean(registeredStr)

                        meters.add(
                            MeterStatus(
                                number = number,
                                serialNumber = serial,
                                place = place,
                                registered = registered,
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

                Log.d(TAG, "Processed $sourceFileName: $successfullyParsed meters parsed, $skippedLines lines skipped")
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
     * Converts various boolean representations to actual boolean
     */
    fun parseBoolean(value: String): Boolean {
        return when (value.trim().lowercase()) {
            "true", "1", "yes", "y", "on", "enabled" -> true
            "false", "0", "no", "n", "off", "disabled" -> false
            else -> {
                Log.w(TAG, "Unknown boolean value: '$value', defaulting to false")
                false
            }
        }
    }

    /**
     * Validates if a CSV line has the correct number of columns
     */
    fun validateCsvLine(line: String, expectedColumns: Int = 4): Boolean {
        if (line.isBlank()) return false

        val tokens = parseCsvLine(line)
        return tokens.size >= expectedColumns && tokens.all { it.isNotBlank() }
    }

    /**
     * Gets CSV format requirements as a formatted string for user guidance
     */
    fun getCsvFormatRequirements(): String {
        return """
            📋 CSV Format Requirements:
            
            Required Columns (in any order):
            • Number - Meter identification number (e.g., M001, METER_123)
            • SerialNumber - Unique serial number (e.g., SN123456789, 2203312)
            • Place - Location/address (e.g., "123 Main St Apt 1A")
            • Registered - Boolean value (true/false, yes/no, 1/0)
            
            Example CSV Content:
            Number,SerialNumber,Place,Registered
            M001,2203312,"123 Main St Apt 1A",true
            M002,SN987654321,"456 Oak Ave Unit 2B",false
            
            Notes:
            • Header row is required
            • Comma-separated values
            • Text containing commas should be quoted
            • Boolean values: true/false, yes/no, 1/0, on/off
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
    fun formatFileSize(sizeBytes: Long): String {
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${String.format("%.1f", sizeBytes / 1024.0)} KB"
            sizeBytes < 1024 * 1024 * 1024 -> "${String.format("%.1f", sizeBytes / (1024.0 * 1024.0))} MB"
            else -> "${String.format("%.1f", sizeBytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    /**
     * Gets the list of required columns
     */
    fun getRequiredColumns(): List<String> = REQUIRED_COLUMNS.toList()

    /**
     * Validates CSV content preview (first few lines)
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