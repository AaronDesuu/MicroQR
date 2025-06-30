package com.example.microqr.ui.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LocationUiState(
    val locations: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val clearInput: Boolean = false
)

class LocationViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    init {
        loadLocations()
    }

    private fun loadLocations() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            // In a real app, you would load from database or SharedPreferences
            // For now, we'll use a simple in-memory set
            val savedLocations = getSavedLocations()

            _uiState.value = _uiState.value.copy(
                locations = savedLocations,
                isLoading = false
            )
        }
    }

    fun addLocation(locationName: String) {
        viewModelScope.launch {
            val trimmedName = locationName.trim()

            if (trimmedName.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    error = "Location name is required"
                )
                return@launch
            }

            val currentLocations = _uiState.value.locations

            if (currentLocations.contains(trimmedName)) {
                _uiState.value = _uiState.value.copy(
                    error = "Location already exists"
                )
                return@launch
            }

            val updatedLocations = currentLocations + trimmedName
            saveLocations(updatedLocations)

            _uiState.value = _uiState.value.copy(
                locations = updatedLocations,
                message = "Location added: $trimmedName",
                clearInput = true,
                error = null
            )
        }
    }

    fun updateLocation(oldName: String, newName: String) {
        viewModelScope.launch {
            val trimmedNewName = newName.trim()

            if (trimmedNewName.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    error = "Location name is required"
                )
                return@launch
            }

            val currentLocations = _uiState.value.locations

            if (currentLocations.contains(trimmedNewName) && trimmedNewName != oldName) {
                _uiState.value = _uiState.value.copy(
                    error = "Location already exists"
                )
                return@launch
            }

            val updatedLocations = currentLocations - oldName + trimmedNewName
            saveLocations(updatedLocations)

            _uiState.value = _uiState.value.copy(
                locations = updatedLocations,
                message = "Location updated: $trimmedNewName",
                error = null
            )
        }
    }

    fun deleteLocation(locationName: String) {
        viewModelScope.launch {
            val currentLocations = _uiState.value.locations
            val updatedLocations = currentLocations - locationName
            saveLocations(updatedLocations)

            _uiState.value = _uiState.value.copy(
                locations = updatedLocations,
                message = "Location deleted: $locationName"
            )
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearInputFlag() {
        _uiState.value = _uiState.value.copy(clearInput = false)
    }

    fun getLocations(): Set<String> {
        return _uiState.value.locations
    }

    private fun getSavedLocations(): Set<String> {
        // In a real app, load from SharedPreferences or database
        // For demonstration, return some default locations
        return setOf(
            "Building A - Floor 1",
            "Building A - Floor 2",
            "Building B - Basement",
            "Outdoor Area"
        )
    }

    private fun saveLocations(locations: Set<String>) {
        // In a real app, save to SharedPreferences or database
        // For now, we just keep them in memory
        // You could implement this using SharedPreferences like:
        //
        // val sharedPref = context.getSharedPreferences("locations", Context.MODE_PRIVATE)
        // with(sharedPref.edit()) {
        //     putStringSet("location_list", locations)
        //     apply()
        // }
    }
}