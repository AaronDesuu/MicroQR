package com.example.microqr.ui.files

object FileConstants {
    const val CSV_MIME_TYPE = "text/csv"
    const val EXCEL_MIME_TYPE = "application/vnd.ms-excel"
    const val CSV_GENERIC_MIME_TYPE = "text/comma-separated-values"

    val SUPPORTED_MIME_TYPES = arrayOf(CSV_MIME_TYPE, EXCEL_MIME_TYPE, CSV_GENERIC_MIME_TYPE)

    const val INTERNAL_STORAGE_DIR = "processed_csv"
    const val MAX_FILE_SIZE_MB = 10
    const val MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024 * 1024
}