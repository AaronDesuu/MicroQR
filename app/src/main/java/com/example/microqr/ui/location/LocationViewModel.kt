package com.example.microqr.ui.location

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.microqr.data.repository.LocationRepository
import com.example.microqr.R

data class LocationUiState(
    val locations: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val clearInput: Boolean = false
)

class LocationViewModel(
    private val locationRepository: LocationRepository,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    init {
        loadLocations()
    }

    private fun loadLocations() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                locationRepository.getAllActiveLocations().collect { locations ->
                    _uiState.value = _uiState.value.copy(
                        locations = locations,
                        isLoading = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = context.getString(R.string.error_loading_locations)
                )
            }
        }
    }

    fun addLocation(locationName: String) {
        viewModelScope.launch {
            val trimmedName = locationName.trim()

            if (trimmedName.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    error = context.getString(R.string.location_name_required)
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                val result = locationRepository.addLocation(trimmedName)

                if (result.isSuccess) {
                    _uiState.value = _uiState.value.copy(
                        message = context.getString(R.string.location_added, trimmedName),
                        clearInput = true,
                        error = null,
                        isLoading = false
                    )
                } else {
                    val errorMessage = when (result.exceptionOrNull()?.message) {
                        "Location already exists" -> context.getString(R.string.location_already_exists)
                        "Location name cannot be empty" -> context.getString(R.string.location_name_required)
                        else -> context.getString(R.string.error_adding_location)
                    }
                    _uiState.value = _uiState.value.copy(
                        error = errorMessage,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = context.getString(R.string.error_adding_location),
                    isLoading = false
                )
            }
        }
    }

    fun updateLocation(oldName: String, newName: String) {
        viewModelScope.launch {
            val trimmedNewName = newName.trim()

            if (trimmedNewName.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    error = context.getString(R.string.location_name_required)
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                val result = locationRepository.updateLocation(oldName, trimmedNewName)

                if (result.isSuccess) {
                    _uiState.value = _uiState.value.copy(
                        message = context.getString(R.string.location_updated, trimmedNewName),
                        error = null,
                        isLoading = false
                    )
                } else {
                    val errorMessage = when (result.exceptionOrNull()?.message) {
                        "Location already exists" -> context.getString(R.string.location_already_exists)
                        "Location name cannot be empty" -> context.getString(R.string.location_name_required)
                        else -> context.getString(R.string.error_updating_location)
                    }
                    _uiState.value = _uiState.value.copy(
                        error = errorMessage,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = context.getString(R.string.error_updating_location),
                    isLoading = false
                )
            }
        }
    }

    fun deleteLocation(locationName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                val result = locationRepository.deleteLocation(locationName)

                if (result.isSuccess) {
                    _uiState.value = _uiState.value.copy(
                        message = context.getString(R.string.location_deleted, locationName),
                        isLoading = false
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = context.getString(R.string.error_deleting_location),
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = context.getString(R.string.error_deleting_location),
                    isLoading = false
                )
            }
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

    fun getLocations(): List<String> {
        return _uiState.value.locations
    }

    // Function to get locations for other parts of the app (like spinners)
    suspend fun getActiveLocationNames(): List<String> {
        return try {
            locationRepository.getActiveLocationNames()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// ViewModelFactory for dependency injection
class LocationViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LocationViewModel::class.java)) {
            val repository = LocationRepository(context)
            @Suppress("UNCHECKED_CAST")
            return LocationViewModel(repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}