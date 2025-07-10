package com.example.microqr.ui.metermatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.microqr.ui.files.MeterStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ContinuousReadingViewModel : ViewModel() {

    // List of meters to scan
    private val _meterList = MutableStateFlow<List<MeterStatus>>(emptyList())
    val meterList: StateFlow<List<MeterStatus>> = _meterList.asStateFlow()

    // Current meter being scanned
    private val _currentMeterIndex = MutableStateFlow(0)
    val currentMeterIndex: StateFlow<Int> = _currentMeterIndex.asStateFlow()

    // Current meter info
    private val _currentMeter = MutableStateFlow<MeterStatus?>(null)
    val currentMeter: StateFlow<MeterStatus?> = _currentMeter.asStateFlow()

    // Scan result display
    private val _scanResult = MutableStateFlow<String>("")
    val scanResult: StateFlow<String> = _scanResult.asStateFlow()

    // Scanning state
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Progress tracking
    private val _scannedCount = MutableStateFlow(0)
    val scannedCount: StateFlow<Int> = _scannedCount.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    // Success state for current meter
    private val _currentMeterScanned = MutableStateFlow(false)
    val currentMeterScanned: StateFlow<Boolean> = _currentMeterScanned.asStateFlow()

    // Error state
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Completion state
    private val _isCompleted = MutableStateFlow(false)
    val isCompleted: StateFlow<Boolean> = _isCompleted.asStateFlow()

    // Skipped meters
    private val _skippedMeters = MutableStateFlow<Set<String>>(emptySet())
    val skippedMeters: StateFlow<Set<String>> = _skippedMeters.asStateFlow()

    // Successfully scanned meters
    private val _scannedMeters = MutableStateFlow<Set<String>>(emptySet())
    val scannedMeters: StateFlow<Set<String>> = _scannedMeters.asStateFlow()

    fun initializeMeterList(meters: List<MeterStatus>) {
        _meterList.value = meters
        _totalCount.value = meters.size
        _currentMeterIndex.value = 0
        _scannedCount.value = 0
        _isCompleted.value = false
        _skippedMeters.value = emptySet()
        _scannedMeters.value = emptySet()

        if (meters.isNotEmpty()) {
            _currentMeter.value = meters[0]
            _scanResult.value = "Ready to scan meter: ${meters[0].number} at ${meters[0].place}"
        }
    }

    fun startScanning() {
        _isScanning.value = true
        _currentMeterScanned.value = false
        _errorMessage.value = null

        val currentMeter = _currentMeter.value
        if (currentMeter != null) {
            _scanResult.value = "Position QR code within frame for meter: ${currentMeter.number}"
        }
    }

    fun stopScanning() {
        _isScanning.value = false
    }

    fun onScanSuccess(scannedSerialNumber: String) {
        val currentMeter = _currentMeter.value ?: return

        if (scannedSerialNumber == currentMeter.serialNumber) {
            // Correct meter scanned
            _currentMeterScanned.value = true
            _isScanning.value = false
            _scanResult.value = "✅ Successfully scanned: ${currentMeter.number}"

            // Add to scanned meters
            val currentScanned = _scannedMeters.value.toMutableSet()
            currentScanned.add(currentMeter.serialNumber)
            _scannedMeters.value = currentScanned

            _scannedCount.value = _scannedCount.value + 1
        } else {
            // Check if this serial number belongs to any meter in the list
            val matchingMeter = _meterList.value.find { it.serialNumber == scannedSerialNumber }
            if (matchingMeter != null) {
                val meterPosition = _meterList.value.indexOf(matchingMeter) + 1
                _errorMessage.value = "Wrong meter detected!\n\nExpected: ${currentMeter.number} (S/N: ${currentMeter.serialNumber})\nScanned: ${matchingMeter.number} (S/N: ${scannedSerialNumber})\n\nThis is meter $meterPosition in your list."
                _scanResult.value = "❌ Wrong meter: Expected ${currentMeter.number}, Got ${matchingMeter.number}"
            } else {
                _errorMessage.value = "Unknown meter scanned!\n\nExpected: ${currentMeter.number} (S/N: ${currentMeter.serialNumber})\nScanned: $scannedSerialNumber\n\nThis meter is not in your current batch."
                _scanResult.value = "❌ Unknown meter: $scannedSerialNumber"
            }
            _isScanning.value = false
        }
    }

    fun onScanError(errorMessage: String) {
        _errorMessage.value = errorMessage
        _scanResult.value = "❌ Scan error: $errorMessage"
        _isScanning.value = false
    }

    fun skipCurrentMeter() {
        val currentMeter = _currentMeter.value ?: return

        // Add to skipped meters
        val currentSkipped = _skippedMeters.value.toMutableSet()
        currentSkipped.add(currentMeter.serialNumber)
        _skippedMeters.value = currentSkipped

        moveToNextMeter()
    }

    fun moveToNextMeter() {
        val currentIndex = _currentMeterIndex.value
        val totalMeters = _meterList.value.size

        if (currentIndex + 1 < totalMeters) {
            // Move to next meter
            val nextIndex = currentIndex + 1
            _currentMeterIndex.value = nextIndex
            _currentMeter.value = _meterList.value[nextIndex]
            _currentMeterScanned.value = false
            _errorMessage.value = null

            val nextMeter = _meterList.value[nextIndex]
            _scanResult.value = "Next meter: ${nextMeter.number} at ${nextMeter.place}"
        } else {
            // All meters processed
            _isCompleted.value = true
            _currentMeter.value = null
            _scanResult.value = "All meters processed! Final count: ${_scannedCount.value} scanned, ${_skippedMeters.value.size} skipped"
        }
    }

    fun resetScanState() {
        _currentMeterScanned.value = false
        _errorMessage.value = null

        val currentMeter = _currentMeter.value
        if (currentMeter != null) {
            _scanResult.value = "Position QR code within the frame for: ${currentMeter.number}"
        }
    }

    fun getProgressPercentage(): Float {
        val total = _totalCount.value
        if (total == 0) return 0f

        val processed = _scannedCount.value + _skippedMeters.value.size
        return (processed.toFloat() / total.toFloat()) * 100f
    }

    fun getCurrentMeterPosition(): String {
        val current = _currentMeterIndex.value + 1
        val total = _totalCount.value
        return "$current/$total"
    }

    fun getSummaryReport(): ContinuousReadingSummary {
        return ContinuousReadingSummary(
            totalMeters = _totalCount.value,
            scannedMeters = _scannedCount.value,
            skippedMeters = _skippedMeters.value.size,
            scannedSerialNumbers = _scannedMeters.value.toList(),
            skippedSerialNumbers = _skippedMeters.value.toList()
        )
    }

    override fun onCleared() {
        super.onCleared()
    }
}

data class ContinuousReadingSummary(
    val totalMeters: Int,
    val scannedMeters: Int,
    val skippedMeters: Int,
    val scannedSerialNumbers: List<String>,
    val skippedSerialNumbers: List<String>
)