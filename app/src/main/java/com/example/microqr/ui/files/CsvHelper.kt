    package com.example.microqr.ui.files

    import android.content.Context
    import java.io.File
    import java.io.FileWriter
    import java.io.IOException

    object CsvHelper {

        /**
         * Creates a sample CSV file for testing purposes
         * This demonstrates the expected CSV format for the app
         */
        fun createSampleCsv(context: Context, fileName: String = "sample_meters.csv"): File? {
            return try {
                val file = File(context.getExternalFilesDir(null), fileName)
                FileWriter(file).use { writer ->
                    // Write header
                    writer.append("Number,SerialNumber,Place,Registered\n")

                    // Write sample data
                    writer.append("M001,SN123456789,123 Main St Apt 1A,true\n")
                    writer.append("M002,SN987654321,456 Oak Ave Unit 2B,false\n")
                    writer.append("M003,SN456789123,789 Pine Rd House 3C,true\n")
                    writer.append("M004,SN321654987,321 Elm St Floor 1,false\n")
                    writer.append("M005,SN789123456,654 Maple Dr Apt 5D,true\n")
                    writer.append("M006,SN147258369,987 Cedar Ln Unit 2A,false\n")
                    writer.append("M007,SN963852741,159 Birch Way House B,true\n")
                    writer.append("M008,SN258147963,753 Spruce St Apt 4F,true\n")
                    writer.append("M009,SN852963741,426 Walnut Ave Unit 1C,false\n")
                    writer.append("M010,SN741852963,195 Cherry Rd Floor 3,true\n")
                }
                file
            } catch (e: IOException) {
                e.printStackTrace()
                null
            }
        }

        /**
         * Validates if a CSV line has the correct format
         */
        fun validateCsvLine(line: String, expectedColumns: Int = 4): Boolean {
            val tokens = line.split(",")
            return tokens.size >= expectedColumns && tokens.all { it.trim().isNotEmpty() }
        }

        /**
         * Converts various boolean representations to actual boolean
         */
        fun parseBoolean(value: String): Boolean {
            return when (value.trim().lowercase()) {
                "true", "1", "yes", "y", "on" -> true
                "false", "0", "no", "n", "off" -> false
                else -> false
            }
        }

        /**
         * Gets CSV format requirements as a formatted string
         */
        fun getCsvFormatRequirements(): String {
            return """
                CSV Format Requirements:
                
                Required Columns (in any order):
                • Number - Meter identification number (e.g., M001, METER_123)
                • SerialNumber - Unique serial number (e.g., SN123456789)
                • Place - Location/address (e.g., "123 Main St Apt 1A")
                • Registered - Boolean value (true/false, yes/no, 1/0)
                
                Example CSV Content:
                Number,SerialNumber,Place,Registered
                M001,SN123456789,"123 Main St Apt 1A",true
                M002,SN987654321,"456 Oak Ave Unit 2B",false
                
                Notes:
                • Header row is required
                • Comma-separated values
                • Text with commas should be quoted
                • Boolean values can be: true/false, yes/no, 1/0
            """.trimIndent()
        }

        /**
         * Sanitizes filename for safe storage
         */
        fun sanitizeFileName(fileName: String): String {
            return fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        }

        /**
         * Checks if file extension is valid for CSV
         */
        fun isValidCsvExtension(fileName: String): Boolean {
            val validExtensions = listOf(".csv", ".txt")
            return validExtensions.any { fileName.lowercase().endsWith(it) }
        }
    }