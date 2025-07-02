package com.example.microqr.ui.export

import android.app.Application
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.microqr.R
import com.example.microqr.data.repository.MeterRepository
import com.example.microqr.ui.files.MeterStatus
import com.example.microqr.ui.files.ProcessingDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class ExportDataSource {
    ALL, METER_CHECK, METER_MATCH
}

enum class RegistrationFilter {
    ALL, REGISTERED_ONLY, UNREGISTERED_ONLY
}

enum class CheckFilter {
    ALL, CHECKED_ONLY, UNCHECKED_ONLY
}

data class ExportUiState(
    val totalRecords: Int = 0,
    val filteredRecords: Int = 0,
    val estimatedSize: String = "0 KB",
    val selectedFiles: Set<String> = emptySet(),
    val selectedPlaces: Set<String> = emptySet(),
    val dataSource: ExportDataSource = ExportDataSource.ALL
)

data class ExportProgress(
    val isInProgress: Boolean = false,
    val progress: Int = 0,
    val message: String = "",
    val isIndeterminate: Boolean = true
)

sealed class ExportResult {
    data class Success(val filePath: String) : ExportResult()
    data class Error(val message: String) : ExportResult()
    object NoData : ExportResult()
}

class ExportViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MeterRepository(application)

    private val _uiState = MutableLiveData(ExportUiState())
    val uiState: LiveData<ExportUiState> = _uiState

    private val _exportProgress = MutableLiveData(ExportProgress())
    val exportProgress: LiveData<ExportProgress> = _exportProgress

    private val _exportResult = MutableLiveData<ExportResult>()
    val exportResult: LiveData<ExportResult> = _exportResult

    // Cache for meter data
    private var allMeters: List<MeterStatus> = emptyList()
    private var meterCheckMeters: List<MeterStatus> = emptyList()
    private var meterMatchMeters: List<MeterStatus> = emptyList()

    companion object {
        private const val TAG = "ExportViewModel"
        private const val EXPORT_FOLDER = "MeterExports"
    }

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                // Load all data sources using Flow.first() to get current values
                allMeters = repository.getAllMeters().first()
                meterCheckMeters = repository.getMetersByDestination(ProcessingDestination.METER_CHECK).first()
                meterMatchMeters = repository.getMetersByDestination(ProcessingDestination.METER_MATCH).first()

                Log.d(TAG, "Loaded data - All: ${allMeters.size}, MeterCheck: ${meterCheckMeters.size}, MeterMatch: ${meterMatchMeters.size}")
                updateUiState()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading data", e)
                _exportResult.value = ExportResult.Error("Failed to load data: ${e.message}")
            }
        }
    }

    fun setDataSource(dataSource: ExportDataSource) {
        val currentState = _uiState.value!!
        _uiState.value = currentState.copy(dataSource = dataSource)
        updateUiState()
    }

    fun setSelectedFiles(files: Set<String>) {
        val currentState = _uiState.value!!
        _uiState.value = currentState.copy(selectedFiles = files)
        updateUiState()
    }

    fun setSelectedPlaces(places: Set<String>) {
        val currentState = _uiState.value!!
        _uiState.value = currentState.copy(selectedPlaces = places)
        updateUiState()
    }

    fun clearAllFilters() {
        _uiState.value = ExportUiState(dataSource = ExportDataSource.ALL)
        updateUiState()
    }

    suspend fun getAvailableFiles(): List<String> = withContext(Dispatchers.IO) {
        getCurrentMeters().map { it.fromFile }.distinct().sorted()
    }

    suspend fun getAvailablePlaces(): List<String> = withContext(Dispatchers.IO) {
        getCurrentMeters().map { it.place }.distinct().sorted()
    }

    fun getSelectedFiles(): Set<String> = _uiState.value?.selectedFiles ?: emptySet()

    fun getSelectedPlaces(): Set<String> = _uiState.value?.selectedPlaces ?: emptySet()

    suspend fun getFilteredData(): List<MeterStatus> = withContext(Dispatchers.IO) {
        val state = _uiState.value!!
        var meters = getCurrentMeters()

        // Apply file filter
        if (state.selectedFiles.isNotEmpty()) {
            meters = meters.filter { it.fromFile in state.selectedFiles }
        }

        // Apply place filter
        if (state.selectedPlaces.isNotEmpty()) {
            meters = meters.filter { it.place in state.selectedPlaces }
        }

        meters
    }

    fun exportToCsv(
        filename: String,
        includeTimestamp: Boolean,
        registrationFilter: RegistrationFilter,
        checkFilter: CheckFilter
    ) {
        viewModelScope.launch {
            try {
                _exportProgress.value = ExportProgress(
                    isInProgress = true,
                    message = "Preparing CSV file...",
                    isIndeterminate = true
                )

                val filteredData = getFilteredData()
                    .let { applyRegistrationFilter(it, registrationFilter) }
                    .let { applyCheckFilter(it, checkFilter) }

                if (filteredData.isEmpty()) {
                    _exportResult.value = ExportResult.NoData
                    _exportProgress.value = ExportProgress()
                    return@launch
                }

                // Create filename with timestamp if requested
                val finalFilename = if (includeTimestamp) {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    "${filename}_$timestamp"
                } else {
                    filename
                }

                // Use appropriate storage location based on Android version
                val exportFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                    // Android 11+ with MANAGE_EXTERNAL_STORAGE permission
                    createExternalFile(finalFilename)
                } else {
                    // Use app's external files directory (no special permission needed)
                    createAppExternalFile(finalFilename)
                }

                _exportProgress.value = ExportProgress(
                    isInProgress = true,
                    message = "Writing CSV data...",
                    isIndeterminate = false,
                    progress = 0
                )

                // Write CSV data
                writeDataToCsv(exportFile, filteredData) { current, total ->
                    val progress = ((current * 100) / total).coerceAtMost(100)
                    _exportProgress.postValue(ExportProgress(
                        isInProgress = true,
                        message = "Writing record $current of $total...",
                        isIndeterminate = false,
                        progress = progress
                    ))
                }

                _exportProgress.value = ExportProgress(
                    isInProgress = false,
                    message = "Export completed",
                    progress = 100
                )

                _exportResult.value = ExportResult.Success(exportFile.absolutePath)

            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                _exportResult.value = ExportResult.Error(e.message ?: "Unknown error")
                _exportProgress.value = ExportProgress()
            }
        }
    }

    private suspend fun createExternalFile(filename: String): File = withContext(Dispatchers.IO) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val exportDir = File(downloadsDir, EXPORT_FOLDER)

        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }

        File(exportDir, "$filename.csv")
    }

    private suspend fun createAppExternalFile(filename: String): File = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val exportDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), EXPORT_FOLDER)

        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }

        File(exportDir, "$filename.csv")
    }

    private suspend fun writeDataToCsv(
        file: File,
        data: List<MeterStatus>,
        progressCallback: (current: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        // Use UTF-8 with BOM for proper Japanese character support in Excel
        file.outputStream().use { fos ->
            // Write UTF-8 BOM for Excel compatibility
            fos.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))

            fos.writer(Charsets.UTF_8).use { writer ->
                // Write CSV header in English
                writer.appendLine("Number,SerialNumber,Place,Registered,Checked,SourceFile,ExportDate")

                val exportDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                data.forEachIndexed { index, meter ->
                    val line = buildString {
                        append("${meter.number},")
                        append("${meter.serialNumber},")
                        // Place data can contain Japanese characters, properly escaped
                        append("\"${meter.place.replace("\"", "\"\"")}\",")
                        // Keep boolean values as true/false in English
                        append("${meter.registered},")
                        append("${meter.isChecked},")
                        append("\"${meter.fromFile.replace("\"", "\"\"")}\",")
                        append("\"$exportDate\"")
                    }
                    writer.appendLine(line)

                    // Update progress
                    progressCallback(index + 1, data.size)
                }
            }
        }
    }

    private fun getCurrentMeters(): List<MeterStatus> {
        return when (_uiState.value?.dataSource) {
            ExportDataSource.METER_CHECK -> meterCheckMeters
            ExportDataSource.METER_MATCH -> meterMatchMeters
            else -> allMeters
        }
    }

    private fun applyRegistrationFilter(
        meters: List<MeterStatus>,
        filter: RegistrationFilter
    ): List<MeterStatus> {
        return when (filter) {
            RegistrationFilter.REGISTERED_ONLY -> meters.filter { it.registered }
            RegistrationFilter.UNREGISTERED_ONLY -> meters.filter { !it.registered }
            RegistrationFilter.ALL -> meters
        }
    }

    private fun applyCheckFilter(
        meters: List<MeterStatus>,
        filter: CheckFilter
    ): List<MeterStatus> {
        return when (filter) {
            CheckFilter.CHECKED_ONLY -> meters.filter { it.isChecked }
            CheckFilter.UNCHECKED_ONLY -> meters.filter { !it.isChecked }
            CheckFilter.ALL -> meters
        }
    }

    private fun updateUiState() {
        viewModelScope.launch {
            try {
                val currentState = _uiState.value!!
                val meters = getCurrentMeters()

                // Apply current filters
                var filteredMeters = meters

                if (currentState.selectedFiles.isNotEmpty()) {
                    filteredMeters = filteredMeters.filter { it.fromFile in currentState.selectedFiles }
                }

                if (currentState.selectedPlaces.isNotEmpty()) {
                    filteredMeters = filteredMeters.filter { it.place in currentState.selectedPlaces }
                }

                // Calculate estimated file size (rough estimate: ~50 bytes per record)
                val estimatedBytes = filteredMeters.size * 50
                val estimatedSize = when {
                    estimatedBytes < 1024 -> "$estimatedBytes B"
                    estimatedBytes < 1024 * 1024 -> "${estimatedBytes / 1024} KB"
                    else -> "${estimatedBytes / (1024 * 1024)} MB"
                }

                _uiState.value = currentState.copy(
                    totalRecords = meters.size,
                    filteredRecords = filteredMeters.size,
                    estimatedSize = estimatedSize
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error updating UI state", e)
            }
        }
    }
}