package com.example.microqr.ui.export

import android.app.Application
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.microqr.data.repository.MeterRepository
import com.example.microqr.ui.files.MeterStatus
import com.example.microqr.ui.files.ProcessingDestination
import com.example.microqr.utils.CsvExportHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
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

                // Create export config
                val config = CsvExportHelper.ExportConfig(
                    filename = filename,
                    includeTimestamp = includeTimestamp,
                    selectedFiles = _uiState.value?.selectedFiles ?: emptySet(),
                    selectedPlaces = _uiState.value?.selectedPlaces ?: emptySet(),
                    registrationFilter = when (registrationFilter) {
                        RegistrationFilter.REGISTERED_ONLY -> CsvExportHelper.RegistrationFilter.REGISTERED_ONLY
                        RegistrationFilter.UNREGISTERED_ONLY -> CsvExportHelper.RegistrationFilter.UNREGISTERED_ONLY
                        else -> CsvExportHelper.RegistrationFilter.ALL
                    },
                    checkFilter = when (checkFilter) {
                        CheckFilter.CHECKED_ONLY -> CsvExportHelper.CheckFilter.CHECKED_ONLY
                        CheckFilter.UNCHECKED_ONLY -> CsvExportHelper.CheckFilter.UNCHECKED_ONLY
                        else -> CsvExportHelper.CheckFilter.ALL
                    }
                )

                // Use the CsvExportHelper
                val result = CsvExportHelper.exportMeterDataToCsv(
                    data = filteredData,
                    config = config,
                    progressCallback = object : CsvExportHelper.ProgressCallback {
                        override fun onStarted(totalRecords: Int) {
                            _exportProgress.postValue(ExportProgress(
                                isInProgress = true,
                                message = "Starting export of $totalRecords records...",
                                isIndeterminate = false,
                                progress = 0
                            ))
                        }

                        override fun onProgress(current: Int, total: Int, message: String) {
                            val progress = ((current * 100) / total).coerceAtMost(100)
                            _exportProgress.postValue(ExportProgress(
                                isInProgress = true,
                                message = message,
                                isIndeterminate = false,
                                progress = progress
                            ))
                        }

                        override fun onCompleted() {
                            _exportProgress.postValue(ExportProgress(
                                isInProgress = false,
                                message = "Export completed",
                                isIndeterminate = false,
                                progress = 100
                            ))
                        }

                        override fun onError(error: String) {
                            _exportProgress.postValue(ExportProgress())
                            _exportResult.postValue(ExportResult.Error(error))
                        }
                    }
                )

                // Handle the result
                when (result) {
                    is CsvExportHelper.ExportResult.Success -> {
                        _exportResult.value = ExportResult.Success(result.filePath)
                        _exportProgress.value = ExportProgress()
                    }
                    is CsvExportHelper.ExportResult.Error -> {
                        _exportResult.value = ExportResult.Error(result.message)
                        _exportProgress.value = ExportProgress()
                    }
                    is CsvExportHelper.ExportResult.NoData -> {
                        _exportResult.value = ExportResult.NoData
                        _exportProgress.value = ExportProgress()
                    }
                    is CsvExportHelper.ExportResult.Cancelled -> {
                        _exportResult.value = ExportResult.Error("Export was cancelled")
                        _exportProgress.value = ExportProgress()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                _exportResult.value = ExportResult.Error(e.message ?: "Unknown error")
                _exportProgress.value = ExportProgress()
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
                val totalRecords = getCurrentMeters().size
                val filteredRecords = getFilteredData().size
                val estimatedSize = calculateEstimatedSize(filteredRecords)

                _uiState.value = currentState.copy(
                    totalRecords = totalRecords,
                    filteredRecords = filteredRecords,
                    estimatedSize = estimatedSize
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error updating UI state", e)
            }
        }
    }

    private fun calculateEstimatedSize(recordCount: Int): String {
        // Estimate approximately 120 bytes per record (including headers and formatting)
        val estimatedBytes = recordCount * 120
        return when {
            estimatedBytes < 1024 -> "$estimatedBytes B"
            estimatedBytes < 1024 * 1024 -> "${(estimatedBytes / 1024.0).format(1)} KB"
            else -> "${(estimatedBytes / (1024.0 * 1024.0)).format(1)} MB"
        }
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)

    /**
     * Refresh data from repository (call this when data might have changed)
     */
    fun refreshData() {
        loadData()
    }

    /**
     * Get current meter data for specific data source
     */
    fun getCurrentMeterData(): List<MeterStatus> = getCurrentMeters()

    /**
     * Validate export configuration
     */
    fun validateExportConfig(filename: String): List<String> {
        val errors = mutableListOf<String>()

        if (filename.isBlank()) {
            errors.add("Filename cannot be empty")
        }

        if (!CsvExportHelper.isValidFilename(filename)) {
            errors.add("Filename contains invalid characters")
        }

        if (getCurrentMeters().isEmpty()) {
            errors.add("No data available to export")
        }

        return errors
    }

    /**
     * Get export summary for preview
     */
    suspend fun getExportSummary(
        registrationFilter: RegistrationFilter,
        checkFilter: CheckFilter
    ): String = withContext(Dispatchers.IO) {
        val currentState = _uiState.value!!
        val totalData = getCurrentMeters()
        val filteredData = getFilteredData()
            .let { applyRegistrationFilter(it, registrationFilter) }
            .let { applyCheckFilter(it, checkFilter) }

        buildString {
            appendLine("Export Summary:")
            appendLine("• Data Source: ${currentState.dataSource}")
            appendLine("• Total available records: ${totalData.size}")
            appendLine("• Records after filtering: ${filteredData.size}")
            appendLine("• Estimated file size: ${calculateEstimatedSize(filteredData.size)}")

            if (currentState.selectedFiles.isNotEmpty()) {
                appendLine("• Selected files: ${currentState.selectedFiles.size}")
            }

            if (currentState.selectedPlaces.isNotEmpty()) {
                appendLine("• Selected places: ${currentState.selectedPlaces.size}")
            }

            if (registrationFilter != RegistrationFilter.ALL) {
                appendLine("• Registration filter: $registrationFilter")
            }

            if (checkFilter != CheckFilter.ALL) {
                appendLine("• Check filter: $checkFilter")
            }
        }
    }
}