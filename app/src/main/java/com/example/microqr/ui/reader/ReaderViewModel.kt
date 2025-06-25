package com.example.microqr.ui.reader // Or your actual ViewModel package

import androidx.compose.ui.geometry.isEmpty
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ReaderViewModel : ViewModel() {

    // Scan result display
    private val _scanResult = MutableStateFlow("Point camera at QR code")
    val scanResult: StateFlow<String> = _scanResult.asStateFlow()

    // Verification info (from micro QR or other sources)
    private val _verificationInfo = MutableStateFlow<String?>(null)
    val verificationInfo: StateFlow<String?> = _verificationInfo.asStateFlow()

    // Navigation trigger
    private val _navigationTrigger = MutableStateFlow(false)
    val navigationTrigger: StateFlow<Boolean> = _navigationTrigger.asStateFlow()

    // Data storage
    // Keep these if you still need to parse and use them within ReaderFragment/ViewModel
    private var mRegularSerialID: String? = null
    private var mMicroQrSerialID: String? = null
    private var mAddress: String? = null
    private var mVerification: String? = null

    // THIS WILL HOLD THE RAW QR VALUE TO PASS TO THE NEXT FRAGMENT
    private val _rawQrDataToNavigate = MutableStateFlow<String?>(null)
    val rawQrDataToNavigate: StateFlow<String?> = _rawQrDataToNavigate.asStateFlow()

    // Scanning state
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()


    fun startScanning() {
        _isScanning.value = true
        _scanResult.value = "Scanning..."
        _rawQrDataToNavigate.value = null // Reset raw data when starting a new scan session
    }

    fun stopScanning() {
        _isScanning.value = false
    }

    // Call this from your Fragment when ANY QR code (regular or micro) is successfully scanned
    // and you decide its raw value is what you want to pass.
    fun setRawQrDataForNavigation(rawData: String) {
        _rawQrDataToNavigate.value = rawData
        // Optionally, update scan result to show this raw data or a confirmation
        // _scanResult.value = "QR Ready: ${rawData.take(30)}..."
    }

    // --- Methods for internal logic (keep if ReaderFragment/ViewModel still use parsed data) ---
    fun setRegularSerialId(serialId: String) {
        mRegularSerialID = serialId
        updateScanResult()
    }

    fun setMicroQrSerialId(serialId: String) {
        mMicroQrSerialID = serialId
        updateScanResult()
    }

    fun setAddress(address: String) {
        mAddress = address
        updateScanResult()
    }

    fun setVerification(verification: String) {
        mVerification = verification
        _verificationInfo.value = verification
        updateScanResult()
    }

    fun getSerialId(): String? = mMicroQrSerialID ?: mRegularSerialID
    fun hasSerialId(): Boolean = getSerialId() != null
    fun hasAddress(): Boolean = mAddress != null
    fun hasVerification(): Boolean = mVerification != null
    fun hasAllRegularQrData(): Boolean = mRegularSerialID != null && mAddress != null
    // --- End of methods for internal logic ---

    /**
     * Determines if the criteria are met to navigate to the DetectedFragment.
     * Now, this simply checks if there's raw QR data ready for navigation.
     */
    fun isReadyToNavigate(): Boolean {
        return _rawQrDataToNavigate.value != null
    }

    fun recordOperation() {
        val currentSerialId = getSerialId()
        val operation = "$currentSerialId,$mAddress" // This might need raw data too if it's the primary identifier
        android.util.Log.i("ReaderViewModel", "Recording operation: $operation")
    }

    fun triggerNavigation() {
        if (isReadyToNavigate()) {
            _navigationTrigger.value = true
        } else {
            android.util.Log.w("ReaderViewModel", "TriggerNavigation called, but no raw QR data is ready for navigation.")
            _scanResult.value = "Scan a QR code to proceed."
        }
    }

    fun resetNavigationTrigger() {
        _navigationTrigger.value = false
    }

    fun resetVerificationData() {
        mVerification = null
        _verificationInfo.value = null
        updateScanResult()
    }

    private fun updateScanResult() {
        val results = mutableListOf<String>()
        val currentSerial = getSerialId()

        currentSerial?.let { results.add("Serial: $it") } // Still useful for display in ReaderFragment
        mAddress?.let { results.add("Address: $it") }     // Still useful for display

        mVerification?.let { results.add("Verification: $it") }

        // If rawQrDataToNavigate is set, maybe you want to show a snippet of it or a confirmation
        _rawQrDataToNavigate.value?.let {
            // results.add("Next QR: ${it.take(20)}...") // Example
        }


        _scanResult.value = if (results.isEmpty() && !_isScanning.value && _rawQrDataToNavigate.value == null) {
            "Point camera at QR code"
        } else if (_rawQrDataToNavigate.value != null && !_isScanning.value) {
            "QR code captured. Ready to proceed." // Or show part of rawQrDataToNavigate.value
        } else if (results.isEmpty() && _isScanning.value) {
            "Scanning..."
        }
        else {
            results.joinToString("\n")
        }
    }

    fun resetAllData() {
        mRegularSerialID = null
        mMicroQrSerialID = null
        mAddress = null
        mVerification = null
        _scanResult.value = "Point camera at QR code"
        _verificationInfo.value = null
        _navigationTrigger.value = false
        _rawQrDataToNavigate.value = null // IMPORTANT: Reset this
        updateScanResult()
    }

    // This function might be redundant if setRawQrDataForNavigation is the primary way
    // to set the data you intend to pass.
    // fun setRawQrCode(qrData: String) {
    //     _rawQrCode.value = qrData
    // }

    // fun getRawQrCode(): String? {
    //     return _rawQrCode.value
    // }

    override fun onCleared() {
        super.onCleared()
    }
}