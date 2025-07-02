package com.example.microqr.ui.metercheck

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.microqr.R
import com.example.microqr.ui.files.MeterStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class MeterCheckFilterState(
    val selectedPlaces: Set<String> = emptySet(),
    val selectedFiles: Set<String> = emptySet()
)

enum class MeterCheckSortOption(@StringRes val displayNameRes: Int) {
    METER_NUMBER(R.string.sort_meter_number),
    SERIAL_NUMBER(R.string.sort_serial_number),
    PLACE(R.string.sort_place),
    SOURCE_FILE(R.string.sort_source_file)
}

data class MeterCheckUiState(
    val allMeters: List<MeterStatus> = emptyList(),
    val filteredMeters: List<MeterStatus> = emptyList(),
    val availablePlaces: List<String> = emptyList(),
    val availableFiles: List<String> = emptyList(),
    val filterState: MeterCheckFilterState = MeterCheckFilterState(),
    val sortOption: MeterCheckSortOption = MeterCheckSortOption.METER_NUMBER,
    val sortAscending: Boolean = true,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val totalCount: Int = 0,
    val scannedCount: Int = 0,
    val remainingCount: Int = 0,
    val hasActiveFilters: Boolean = false,
    val selectedMeter: MeterStatus? = null,
    val shouldNavigateToScan: Boolean = false
)

class MeterCheckViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MeterCheckUiState())
    val uiState: StateFlow<MeterCheckUiState> = _uiState.asStateFlow()

    private val _allMeters = MutableStateFlow<List<MeterStatus>>(emptyList())
    private val _filterState = MutableStateFlow(MeterCheckFilterState())
    private val _sortOption = MutableStateFlow(MeterCheckSortOption.METER_NUMBER)
    private val _sortAscending = MutableStateFlow(true)
    private val _searchQuery = MutableStateFlow("")
    private val _isLoading = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            // Combine all state flows to create the UI state
            combine(
                _allMeters,
                _filterState,
                _sortOption,
                _sortAscending,
                _searchQuery,
                _isLoading
            ) { flows ->
                val allMeters = flows[0] as List<MeterStatus>
                val filterState = flows[1] as MeterCheckFilterState
                val sortOption = flows[2] as MeterCheckSortOption
                val sortAscending = flows[3] as Boolean
                val searchQuery = flows[4] as String
                val isLoading = flows[5] as Boolean

                val filteredAndSortedMeters = allMeters
                    .let { meters -> filterMeters(meters, filterState) }
                    .let { meters -> searchMeters(meters, searchQuery) }
                    .let { meters -> sortMeters(meters, sortOption, sortAscending) }

                MeterCheckUiState(
                    allMeters = allMeters,
                    filteredMeters = filteredAndSortedMeters,
                    availablePlaces = allMeters.map { it.place }.distinct().sorted(),
                    availableFiles = allMeters.map { it.fromFile }.distinct().sorted(),
                    filterState = filterState,
                    sortOption = sortOption,
                    sortAscending = sortAscending,
                    searchQuery = searchQuery,
                    isLoading = isLoading,
                    totalCount = allMeters.size,
                    scannedCount = allMeters.count { it.isChecked },
                    remainingCount = allMeters.count { !it.isChecked },
                    hasActiveFilters = hasActiveFilters(filterState, searchQuery)
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    fun setMeters(meters: List<MeterStatus>) {
        viewModelScope.launch {
            // Don't assign automatic numbers - keep original meter numbers
            // Show dashes for unknown/empty numbers instead
            val processedMeters = meters.map { meter ->
                meter.copy(
                    number = if (meter.number.isBlank() || meter.number == "0" || meter.number.lowercase() == "unknown") {
                        "---"
                    } else {
                        meter.number
                    }
                )
            }
            _allMeters.value = processedMeters
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun togglePlaceFilter(place: String) {
        val currentFilters = _filterState.value
        val newPlaces = if (place in currentFilters.selectedPlaces) {
            currentFilters.selectedPlaces - place
        } else {
            currentFilters.selectedPlaces + place
        }
        _filterState.value = currentFilters.copy(selectedPlaces = newPlaces)
    }

    fun toggleFileFilter(file: String) {
        val currentFilters = _filterState.value
        val newFiles = if (file in currentFilters.selectedFiles) {
            currentFilters.selectedFiles - file
        } else {
            currentFilters.selectedFiles + file
        }
        _filterState.value = currentFilters.copy(selectedFiles = newFiles)
    }

    fun setSortOption(sortOption: MeterCheckSortOption) {
        if (_sortOption.value == sortOption) {
            // If same sort option, toggle ascending/descending
            _sortAscending.value = !_sortAscending.value
        } else {
            _sortOption.value = sortOption
            _sortAscending.value = true
        }
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
        _filterState.value = MeterCheckFilterState()
        _searchQuery.value = ""
    }

    fun clearPlaceFilters() {
        _filterState.value = _filterState.value.copy(selectedPlaces = emptySet())
    }

    fun clearFileFilters() {
        _filterState.value = _filterState.value.copy(selectedFiles = emptySet())
    }

    private fun hasActiveFilters(filterState: MeterCheckFilterState, searchQuery: String): Boolean {
        return searchQuery.isNotEmpty() ||
                filterState.selectedPlaces.isNotEmpty() ||
                filterState.selectedFiles.isNotEmpty()
    }

    private fun filterMeters(meters: List<MeterStatus>, filterState: MeterCheckFilterState): List<MeterStatus> {
        return meters.filter { meter ->
            val placeMatch = filterState.selectedPlaces.isEmpty() ||
                    meter.place in filterState.selectedPlaces

            val fileMatch = filterState.selectedFiles.isEmpty() ||
                    meter.fromFile in filterState.selectedFiles

            placeMatch && fileMatch
        }
    }

    private fun searchMeters(meters: List<MeterStatus>, query: String): List<MeterStatus> {
        if (query.isBlank()) return meters

        val searchQuery = query.lowercase().trim()
        return meters.filter { meter ->
            meter.serialNumber.lowercase().contains(searchQuery) ||
                    meter.number.lowercase().contains(searchQuery) ||
                    meter.place.lowercase().contains(searchQuery)
        }
    }

    private fun sortMeters(
        meters: List<MeterStatus>,
        sortOption: MeterCheckSortOption,
        ascending: Boolean
    ): List<MeterStatus> {
        val sorted = when (sortOption) {
            MeterCheckSortOption.METER_NUMBER -> meters.sortedBy { it.number }
            MeterCheckSortOption.SERIAL_NUMBER -> meters.sortedBy { it.serialNumber }
            MeterCheckSortOption.PLACE -> meters.sortedBy { it.place }
            MeterCheckSortOption.SOURCE_FILE -> meters.sortedBy { it.fromFile }
        }

        return if (ascending) sorted else sorted.reversed()
    }
}