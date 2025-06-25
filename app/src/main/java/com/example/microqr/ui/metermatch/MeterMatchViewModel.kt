package com.example.microqr.ui.metermatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.microqr.ui.files.MeterStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class FilterState(
    val selectedPlaces: Set<String> = emptySet(),
    val selectedFiles: Set<String> = emptySet(),
    val selectedRegistrationStatus: Set<Boolean> = emptySet(), // true = registered, false = unregistered
    val showCheckedOnly: Boolean = false,
    val showUncheckedOnly: Boolean = false
)

enum class SortOption(val displayName: String) {
    SERIAL_NUMBER("Serial Number"),
    METER_NUMBER("Meter Number"),
    PLACE("Place"),
    SOURCE_FILE("Source File"),
    REGISTRATION_STATUS("Registration Status")
}

data class MatchUiState(
    val allMeters: List<MeterStatus> = emptyList(),
    val filteredMeters: List<MeterStatus> = emptyList(),
    val availablePlaces: List<String> = emptyList(),
    val availableFiles: List<String> = emptyList(),
    val filterState: FilterState = FilterState(),
    val sortOption: SortOption = SortOption.METER_NUMBER,
    val sortAscending: Boolean = true,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val totalMeters: Int = 0,
    val checkedCount: Int = 0,
    val registeredCount: Int = 0
)

class MatchViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MatchUiState())
    val uiState: StateFlow<MatchUiState> = _uiState.asStateFlow()

    private val _allMeters = MutableStateFlow<List<MeterStatus>>(emptyList())
    private val _filterState = MutableStateFlow(FilterState())
    private val _sortOption = MutableStateFlow(SortOption.METER_NUMBER)
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
                val filterState = flows[1] as FilterState
                val sortOption = flows[2] as SortOption
                val sortAscending = flows[3] as Boolean
                val searchQuery = flows[4] as String
                val isLoading = flows[5] as Boolean

                val filteredAndSortedMeters = allMeters
                    .let { meters -> filterMeters(meters, filterState) }
                    .let { meters -> searchMeters(meters, searchQuery) }
                    .let { meters -> sortMeters(meters, sortOption, sortAscending) }

                MatchUiState(
                    allMeters = allMeters,
                    filteredMeters = filteredAndSortedMeters,
                    availablePlaces = allMeters.map { it.place }.distinct().sorted(),
                    availableFiles = allMeters.map { it.fromFile }.distinct().sorted(),
                    filterState = filterState,
                    sortOption = sortOption,
                    sortAscending = sortAscending,
                    searchQuery = searchQuery,
                    isLoading = isLoading,
                    totalMeters = allMeters.size,
                    checkedCount = allMeters.count { it.isChecked },
                    registeredCount = allMeters.count { it.registered }
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    fun setMeters(meters: List<MeterStatus>) {
        _allMeters.value = meters
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

    fun toggleRegistrationFilter(registered: Boolean) {
        val currentFilters = _filterState.value
        val newStatus = if (registered in currentFilters.selectedRegistrationStatus) {
            currentFilters.selectedRegistrationStatus - registered
        } else {
            currentFilters.selectedRegistrationStatus + registered
        }
        _filterState.value = currentFilters.copy(selectedRegistrationStatus = newStatus)
    }

    fun toggleCheckedFilter() {
        val currentFilters = _filterState.value
        _filterState.value = when {
            currentFilters.showCheckedOnly -> currentFilters.copy(showCheckedOnly = false, showUncheckedOnly = true)
            currentFilters.showUncheckedOnly -> currentFilters.copy(showCheckedOnly = false, showUncheckedOnly = false)
            else -> currentFilters.copy(showCheckedOnly = true, showUncheckedOnly = false)
        }
    }

    fun setSortOption(sortOption: SortOption) {
        if (_sortOption.value == sortOption) {
            // If same sort option, toggle ascending/descending
            _sortAscending.value = !_sortAscending.value
        } else {
            _sortOption.value = sortOption
            _sortAscending.value = true
        }
    }

    fun clearAllFilters() {
        _filterState.value = FilterState()
        _searchQuery.value = ""
    }

    fun clearPlaceFilters() {
        _filterState.value = _filterState.value.copy(selectedPlaces = emptySet())
    }

    fun clearFileFilters() {
        _filterState.value = _filterState.value.copy(selectedFiles = emptySet())
    }

    fun clearRegistrationFilters() {
        _filterState.value = _filterState.value.copy(selectedRegistrationStatus = emptySet())
    }

    private fun filterMeters(meters: List<MeterStatus>, filterState: FilterState): List<MeterStatus> {
        return meters.filter { meter ->
            val placeMatch = filterState.selectedPlaces.isEmpty() ||
                    meter.place in filterState.selectedPlaces

            val fileMatch = filterState.selectedFiles.isEmpty() ||
                    meter.fromFile in filterState.selectedFiles

            val registrationMatch = filterState.selectedRegistrationStatus.isEmpty() ||
                    meter.registered in filterState.selectedRegistrationStatus

            val checkedMatch = when {
                filterState.showCheckedOnly -> meter.isChecked
                filterState.showUncheckedOnly -> !meter.isChecked
                else -> true
            }

            placeMatch && fileMatch && registrationMatch && checkedMatch
        }
    }

    private fun searchMeters(meters: List<MeterStatus>, query: String): List<MeterStatus> {
        if (query.isBlank()) return meters

        val searchQuery = query.lowercase().trim()
        return meters.filter { meter ->
            meter.serialNumber.lowercase().contains(searchQuery) ||
                    meter.number.lowercase().contains(searchQuery) ||
                    meter.place.lowercase().contains(searchQuery) ||
                    meter.fromFile.lowercase().contains(searchQuery)
        }
    }

    private fun sortMeters(
        meters: List<MeterStatus>,
        sortOption: SortOption,
        ascending: Boolean
    ): List<MeterStatus> {
        val sorted = when (sortOption) {
            SortOption.SERIAL_NUMBER -> meters.sortedBy { it.serialNumber }
            SortOption.METER_NUMBER -> meters.sortedBy { it.number }
            SortOption.PLACE -> meters.sortedBy { it.place }
            SortOption.SOURCE_FILE -> meters.sortedBy { it.fromFile }
            SortOption.REGISTRATION_STATUS -> meters.sortedBy { it.registered }
        }

        return if (ascending) sorted else sorted.reversed()
    }
}