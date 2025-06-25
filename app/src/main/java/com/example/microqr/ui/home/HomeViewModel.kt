package com.example.microqr.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.asLiveData
import com.example.microqr.R
import com.example.microqr.data.repository.MeterRepository
import com.example.microqr.ui.files.ProcessingDestination
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MeterRepository(application)
    private val context = application

    // Navigation events
    enum class NavigationEvent {
        NAVIGATE_TO_MATCH,
        NAVIGATE_TO_METER_CHECK,
        NAVIGATE_TO_FILE_UPLOAD,
        NAVIGATE_TO_LOGIN
    }

    private val _navigationEvent = MutableLiveData<NavigationEvent>()
    val navigationEvent: LiveData<NavigationEvent> = _navigationEvent

    // User data
    private val _userGreeting = MutableLiveData<String>()
    val userGreeting: LiveData<String> = _userGreeting

    // Total meters count from repository
    private val _totalMeters = MutableLiveData<Int>()
    val totalMeters: LiveData<Int> = _totalMeters

    // Match-specific stats (from METER_MATCH destination)
    private val _matchedMeters = MutableLiveData<Int>()
    val matchedMeters: LiveData<Int> = _matchedMeters

    private val _unmatchedMeters = MutableLiveData<Int>()
    val unmatchedMeters: LiveData<Int> = _unmatchedMeters

    // Check-specific stats (from METER_CHECK destination)
    private val _scannedMeters = MutableLiveData<Int>()
    val scannedMeters: LiveData<Int> = _scannedMeters

    private val _unscannedMeters = MutableLiveData<Int>()
    val unscannedMeters: LiveData<Int> = _unscannedMeters

    // UI state
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    // LiveData from repository for real-time updates
    val meterCheckMeters = repository.getMetersByDestination(ProcessingDestination.METER_CHECK).asLiveData()
    val meterMatchMeters = repository.getMetersByDestination(ProcessingDestination.METER_MATCH).asLiveData()

    init {
        // Initialize with default values using string resources
        _userGreeting.value = context.getString(R.string.ready_to_manage_meters)
        _totalMeters.value = 0
        _matchedMeters.value = 0
        _unmatchedMeters.value = 0
        _scannedMeters.value = 0
        _unscannedMeters.value = 0
        _isLoading.value = false

        // Observe real-time data changes
        observeDataChanges()
    }

    private fun observeDataChanges() {
        // Observe MeterCheck data changes
        meterCheckMeters.observeForever { checkMeters ->
            checkMeters?.let {
                val scanned = it.count { meter -> meter.isChecked }
                val unscanned = it.size - scanned

                _scannedMeters.value = scanned
                _unscannedMeters.value = unscanned

                updateTotalCount()
            }
        }

        // Observe MeterMatch data changes
        meterMatchMeters.observeForever { matchMeters ->
            matchMeters?.let {
                val matched = it.count { meter -> meter.isChecked }
                val unmatched = it.size - matched

                _matchedMeters.value = matched
                _unmatchedMeters.value = unmatched

                updateTotalCount()
            }
        }
    }

    private fun updateTotalCount() {
        viewModelScope.launch {
            try {
                val total = repository.getTotalMeterCount()
                _totalMeters.value = total
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.failed_to_get_total_count, e.message)
            }
        }
    }

    fun loadUserData() {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                // Get current time-based greeting using string resources
                val greeting = getTimeBasedGreeting()
                _userGreeting.value = greeting

            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.failed_to_load_user_data, e.message)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadQuickStats() {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                // Load real statistics from repository
                loadMatchStatsFromRepository()
                loadCheckStatsFromRepository()
                updateTotalCount()

            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.failed_to_load_statistics, e.message)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadMatchStatsFromRepository() {
        try {
            val matchMeters = repository.getMetersByDestination(ProcessingDestination.METER_MATCH).asLiveData().value ?: emptyList()
            val matched = matchMeters.count { it.isChecked }
            val unmatched = matchMeters.size - matched

            _matchedMeters.value = matched
            _unmatchedMeters.value = unmatched
        } catch (e: Exception) {
            _errorMessage.value = context.getString(R.string.failed_to_load_match_statistics, e.message)
        }
    }

    private suspend fun loadCheckStatsFromRepository() {
        try {
            val checkMeters = repository.getMetersByDestination(ProcessingDestination.METER_CHECK).asLiveData().value ?: emptyList()
            val scanned = checkMeters.count { it.isChecked }
            val unscanned = checkMeters.size - scanned

            _scannedMeters.value = scanned
            _unscannedMeters.value = unscanned
        } catch (e: Exception) {
            _errorMessage.value = context.getString(R.string.failed_to_load_check_statistics, e.message)
        }
    }

    // Navigation handlers
    fun onMatchMeterClicked() {
        _navigationEvent.value = NavigationEvent.NAVIGATE_TO_MATCH
    }

    fun onMeterCheckClicked() {
        _navigationEvent.value = NavigationEvent.NAVIGATE_TO_METER_CHECK
    }

    fun onFileUploadClicked() {
        _navigationEvent.value = NavigationEvent.NAVIGATE_TO_FILE_UPLOAD
    }

    fun onLogoutClicked() {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                // Perform logout operations
                performLogout()

                // Navigate to login
                _navigationEvent.value = NavigationEvent.NAVIGATE_TO_LOGIN

            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.logout_failed, e.message)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    // Helper methods using string resources
    private fun getTimeBasedGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> context.getString(R.string.greeting_morning)
            in 12..17 -> context.getString(R.string.greeting_afternoon)
            in 18..21 -> context.getString(R.string.greeting_evening)
            else -> context.getString(R.string.greeting_late)
        }
    }

    private fun performLogout() {
        // Simulate logout operations
        Thread.sleep(1000)
    }

    /**
     * Refresh stats when user returns to home screen
     */
    fun refreshStats() {
        loadQuickStats()
    }

    /**
     * Force refresh data from repository
     */
    fun forceRefreshData() {
        viewModelScope.launch {
            try {
                updateTotalCount()
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.failed_to_refresh_statistics, e.message)
            }
        }
    }

    /**
     * Get detailed statistics for debugging or analytics
     */
    fun getDetailedStats(): DetailedStats {
        return DetailedStats(
            totalMeters = _totalMeters.value ?: 0,
            matchedMeters = _matchedMeters.value ?: 0,
            unmatchedMeters = _unmatchedMeters.value ?: 0,
            scannedMeters = _scannedMeters.value ?: 0,
            unscannedMeters = _unscannedMeters.value ?: 0
        )
    }

    /**
     * Handle deep link navigation
     */
    fun handleDeepLink(destination: String) {
        when (destination.lowercase()) {
            "match", "meter_match" -> onMatchMeterClicked()
            "check", "meter_check" -> onMeterCheckClicked()
            "upload", "file_upload" -> onFileUploadClicked()
            else -> {
                _errorMessage.value = context.getString(R.string.unknown_destination, destination)
            }
        }
    }

    /**
     * Get summary text for current stats using string resources
     */
    fun getStatsSummary(): String {
        val total = _totalMeters.value ?: 0
        val matched = _matchedMeters.value ?: 0
        val scanned = _scannedMeters.value ?: 0

        return context.getString(R.string.stats_summary, total, matched, scanned)
    }

    /**
     * Check if there's any work pending
     */
    fun hasPendingWork(): Boolean {
        val unmatchedCount = _unmatchedMeters.value ?: 0
        val unscannedCount = _unscannedMeters.value ?: 0
        return unmatchedCount > 0 || unscannedCount > 0
    }

    /**
     * Get work priority suggestion using string resources
     */
    fun getWorkPrioritySuggestion(): String? {
        val unmatched = _unmatchedMeters.value ?: 0
        val unscanned = _unscannedMeters.value ?: 0

        return when {
            unmatched > unscanned && unmatched > 0 ->
                context.getString(R.string.pending_match_suggestion, unmatched)
            unscanned > unmatched && unscanned > 0 ->
                context.getString(R.string.pending_scan_suggestion, unscanned)
            unmatched > 0 && unscanned > 0 ->
                context.getString(R.string.pending_both_suggestion, unmatched, unscanned)
            else -> null
        }
    }

    // Data classes for statistics
    data class DetailedStats(
        val totalMeters: Int,
        val matchedMeters: Int,
        val unmatchedMeters: Int,
        val scannedMeters: Int,
        val unscannedMeters: Int
    ) {
        val matchTotal: Int get() = matchedMeters + unmatchedMeters
        val checkTotal: Int get() = scannedMeters + unscannedMeters
        val matchPercentage: Float get() = if (matchTotal > 0) (matchedMeters.toFloat() / matchTotal * 100) else 0f
        val scanPercentage: Float get() = if (checkTotal > 0) (scannedMeters.toFloat() / checkTotal * 100) else 0f
    }

    /**
     * Clean up resources when ViewModel is cleared
     */
    override fun onCleared() {
        super.onCleared()
        // Remove observers to prevent memory leaks
        meterCheckMeters.removeObserver { }
        meterMatchMeters.removeObserver { }
    }
}