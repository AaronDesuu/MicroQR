package com.example.microqr.ui.metercheck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.microqr.ui.files.MeterStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MeterCheckUiState(
    val meters: List<MeterStatus> = emptyList(),
    val searchQuery: String = "",
    val selectedLocation: String = "",
    val selectedStatus: String = "",
    val sortOption: String = "",
    val totalCount: Int = 0,
    val scannedCount: Int = 0,
    val remainingCount: Int = 0,
    val hasActiveFilters: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedMeter: MeterStatus? = null,
    val shouldNavigateToScan: Boolean = false
)

class MeterCheckViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MeterCheckUiState())
    val uiState: StateFlow<MeterCheckUiState> = _uiState.asStateFlow()

    fun setMeters(meters: List<MeterStatus>) {
        viewModelScope.launch {
            // Assign numbers starting from 1 for MeterCheck workflow
            val numberedMeters = meters.mapIndexed { index, meter ->
                meter.copy(number = (index + 1).toString().padStart(3, '0'))
            }

            _uiState.value = _uiState.value.copy(
                meters = numberedMeters,
                totalCount = numberedMeters.size,
                scannedCount = numberedMeters.count { it.isChecked },
                remainingCount = numberedMeters.count { !it.isChecked }
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            hasActiveFilters = calculateHasActiveFilters(query, _uiState.value.selectedLocation, _uiState.value.selectedStatus)
        )
    }

    fun setLocationFilter(location: String) {
        _uiState.value = _uiState.value.copy(
            selectedLocation = location,
            hasActiveFilters = calculateHasActiveFilters(_uiState.value.searchQuery, location, _uiState.value.selectedStatus)
        )
    }

    fun setStatusFilter(status: String) {
        _uiState.value = _uiState.value.copy(
            selectedStatus = status,
            hasActiveFilters = calculateHasActiveFilters(_uiState.value.searchQuery, _uiState.value.selectedLocation, status)
        )
    }

    fun setSortOption(sortOption: String) {
        _uiState.value = _uiState.value.copy(sortOption = sortOption)
    }

    fun updateStatistics(total: Int, scanned: Int, remaining: Int) {
        _uiState.value = _uiState.value.copy(
            totalCount = total,
            scannedCount = scanned,
            remainingCount = remaining
        )
    }

    fun selectMeterForScanning(meter: MeterStatus) {
        _uiState.value = _uiState.value.copy(
            selectedMeter = meter,
            shouldNavigateToScan = true
        )
    }

    fun clearNavigationFlag() {
        _uiState.value = _uiState.value.copy(
            shouldNavigateToScan = false,
            selectedMeter = null
        )
    }

    fun clearAllFilters() {
        _uiState.value = _uiState.value.copy(
            searchQuery = "",
            selectedLocation = "",
            selectedStatus = "",
            sortOption = "",
            hasActiveFilters = false
        )
    }

    private fun calculateHasActiveFilters(query: String, location: String, status: String): Boolean {
        return query.isNotEmpty() ||
                (location.isNotEmpty() && location != "All Locations") ||
                (status.isNotEmpty() && status != "All")
    }
}