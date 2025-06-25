package com.example.microqr.ui.files

data class MeterStatus(
    val number: String,
    val serialNumber: String,
    val place: String,
    val registered: Boolean,
    val fromFile: String,
    var isChecked: Boolean = false,
    var isSelectedForProcessing: Boolean = false
)

data class CsvValidationResult(
    val isValid: Boolean,
    val missingColumns: List<String> = emptyList(),
    val errorMessage: String = ""
)

data class FileItem(
    val fileName: String,
    val uploadDate: String,
    val meterCount: Int,
    val isValid: Boolean,
    val validationError: String = "",
    val destination: ProcessingDestination? = null
)

data class FileProcessingResult(
    val success: Boolean,
    val fileName: String,
    val meterCount: Int,
    val destination: ProcessingDestination?,
    val errorMessage: String = ""
)

enum class ProcessingDestination(val displayName: String, val fragmentName: String) {
    METER_CHECK("MeterCheck", "MeterCheckFragment"),
    METER_MATCH("MeterMatch", "MeterMatchFragment");

    companion object {
        fun fromString(value: String): ProcessingDestination? {
            return when (value.uppercase()) {
                "METERCHECK", "METER_CHECK", "METERCHECKFRAGMENT" -> METER_CHECK
                "MATCH", "METERMATCH", "METER_MATCH", "METERMATCHFRAGMENT" -> METER_MATCH
                else -> null
            }
        }

        fun fromFragmentName(fragmentName: String): ProcessingDestination? {
            return when (fragmentName) {
                "MeterCheckFragment" -> METER_CHECK
                "MeterMatchFragment" -> METER_MATCH
                else -> null
            }
        }
    }
}