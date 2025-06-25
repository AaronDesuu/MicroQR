package com.example.microqr.ui.metermatch

import androidx.lifecycle.ViewModel
import com.example.microqr.ui.files.MeterStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MeterDetailScanViewModel : ViewModel() {

    // Target meter information
    private val _targetMeter = MutableStateFlow<MeterStatus?>(null)
    val targetMeter: StateFlow<MeterStatus?> = _targetMeter.asStateFlow()

    // Scan result display
    private val _scanResult = MutableStateFlow("Position QR code within the frame")
    val scanResult: StateFlow<String> = _scanResult.asStateFlow()

    // Scanning state
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Success state
    private val _scanSuccess = MutableStateFlow(false)
    val scanSuccess: StateFlow<Boolean> = _scanSuccess.asStateFlow()

    // Error state
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun setTargetMeter(meter: MeterStatus) {
        _targetMeter.value = meter
        _scanResult.value = "Scan QR code for meter: ${meter.number}"
    }

    fun startScanning() {
        _isScanning.value = true
        _scanSuccess.value = false
        _errorMessage.value = null
        _scanResult.value = "Scanning for meter: ${_targetMeter.value?.number ?: "Unknown"}"
    }

    fun stopScanning() {
        _isScanning.value = false
    }

    fun setScanSuccess(success: Boolean) {
        _scanSuccess.value = success
        if (success) {
            _isScanning.value = false
        }
    }

    fun setSuccessResult(message: String) {
        _scanResult.value = message
        _scanSuccess.value = true
        _isScanning.value = false
    }

    fun setErrorResult(message: String) {
        _scanResult.value = message
        _errorMessage.value = message
        _isScanning.value = false
    }

    fun resetScanState() {
        _scanSuccess.value = false
        _errorMessage.value = null
        _scanResult.value = "Position QR code within the frame"
        // Don't reset isScanning here as it will be set by startScanning()
    }

    override fun onCleared() {
        super.onCleared()
    }
}