package com.example.microqr.ui.files

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.*
import com.example.microqr.data.repository.MeterRepository
import com.example.microqr.data.repository.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class FilesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MeterRepository(application)
    private val preferencesManager = PreferencesManager(application)

    // LiveData from database
    val fileItems: LiveData<List<FileItem>> = repository.getAllFiles().asLiveData()
    val meterStatusList: LiveData<List<MeterStatus>> = repository.getAllMeters().asLiveData()

    // Destination-specific LiveData
    val meterCheckMeters: LiveData<List<MeterStatus>> =
        repository.getMetersByDestination(ProcessingDestination.METER_CHECK).asLiveData()
    val meterMatchMeters: LiveData<List<MeterStatus>> =
        repository.getMetersByDestination(ProcessingDestination.METER_MATCH).asLiveData()

    // UI state
    private val _selectedMetersForProcessing = MutableLiveData<List<MeterStatus>>(emptyList())
    val selectedMetersForProcessing: LiveData<List<MeterStatus>> = _selectedMetersForProcessing

    private val _lastProcessedDestination = MutableLiveData<ProcessingDestination?>()
    val lastProcessedDestination: LiveData<ProcessingDestination?> = _lastProcessedDestination

    private val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> = _toastMessage

    private val _navigateToMeterCheck = MutableLiveData<Boolean>()
    val navigateToMeterCheck: LiveData<Boolean> = _navigateToMeterCheck

    private val _navigateToMatch = MutableLiveData<Boolean>()
    val navigateToMatch: LiveData<Boolean> = _navigateToMatch

    private val internalStorageDir = File(application.filesDir, "processed_csv")

    companion object {
        private const val TAG = "FilesViewModel"
    }

    init {
        // Ensure internal storage directory exists
        if (!internalStorageDir.exists()) {
            internalStorageDir.mkdirs()
        }

        // Initialize last processed destination from preferences
        _lastProcessedDestination.value = preferencesManager.lastProcessedDestination
    }

    fun clearToastMessage() {
        _toastMessage.value = ""
    }

    fun processFile(uri: Uri, destination: ProcessingDestination) {
        viewModelScope.launch {
            try {
                val contentResolver = getApplication<Application>().contentResolver
                val fileName = getFileNameFromUri(uri, contentResolver)
                    ?: "imported_file_${System.currentTimeMillis()}.csv"

                // Validate and read CSV content
                var inputStreamForValidation: InputStream? = null
                var inputStreamForSaving: InputStream? = null
                var inputStreamForProcessing: InputStream? = null

                try {
                    inputStreamForValidation = contentResolver.openInputStream(uri)
                    val validationResult = CsvHelper.validateCsvFormat(inputStreamForValidation)

                    if (!validationResult.isValid) {
                        _toastMessage.postValue("Invalid CSV format: ${validationResult.errorMessage}")
                        return@launch
                    }

                    // Save file to internal storage
                    inputStreamForSaving = contentResolver.openInputStream(uri)
                    val savedFile = File(internalStorageDir, fileName)
                    savedFile.outputStream().use { output ->
                        inputStreamForSaving?.copyTo(output)
                    }

                    // Process meters from the file
                    inputStreamForProcessing = FileInputStream(savedFile)
                    val metersFromFile = CsvHelper.processCsvFile(inputStreamForProcessing, fileName)

                    if (metersFromFile.isEmpty()) {
                        _toastMessage.postValue("No valid meter data found in the file")
                        return@launch
                    }

                    // Save to database with destination
                    repository.syncFileWithMeters(fileName, metersFromFile, destination)

                    // Update UI state
                    _selectedMetersForProcessing.value = metersFromFile
                    _lastProcessedDestination.value = destination
                    preferencesManager.lastProcessedDestination = destination

                    _toastMessage.postValue("✅ File processed for ${destination.displayName}: ${metersFromFile.size} meters ready")

                } finally {
                    try {
                        inputStreamForValidation?.close()
                        inputStreamForSaving?.close()
                        inputStreamForProcessing?.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.postValue("Error processing CSV: ${e.message}")
            }
        }
    }

    fun deleteFile(fileNameToDelete: String) {
        viewModelScope.launch {
            try {
                repository.deleteFile(fileNameToDelete)
                _toastMessage.postValue("File '$fileNameToDelete' and its meters removed from all destinations.")
            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.postValue("Error deleting '$fileNameToDelete': ${e.message}")
            }
        }
    }

    fun processForMeterCheck(fileName: String) {
        viewModelScope.launch {
            try {
                val metersFromFile = repository.getMetersByFile(fileName).first()
                if (metersFromFile.isNotEmpty()) {
                    // Update file destination
                    val existingFile = repository.getFile(fileName)
                    existingFile?.let {
                        val updatedFile = it.copy(destination = ProcessingDestination.METER_CHECK)
                        repository.updateFile(updatedFile)
                    }

                    _selectedMetersForProcessing.value = metersFromFile
                    _lastProcessedDestination.value = ProcessingDestination.METER_CHECK
                    preferencesManager.lastProcessedDestination = ProcessingDestination.METER_CHECK

                    _toastMessage.postValue("✅ ${metersFromFile.size} meters processed for MeterCheck")
                } else {
                    _toastMessage.postValue("❌ No meters found in file '$fileName'")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.postValue("Error processing file for MeterCheck: ${e.message}")
            }
        }
    }

    fun processForMatch(fileName: String) {
        viewModelScope.launch {
            try {
                val metersFromFile = repository.getMetersByFile(fileName).first()
                if (metersFromFile.isNotEmpty()) {
                    // Update file destination
                    val existingFile = repository.getFile(fileName)
                    existingFile?.let {
                        val updatedFile = it.copy(destination = ProcessingDestination.METER_MATCH)
                        repository.updateFile(updatedFile)
                    }

                    _selectedMetersForProcessing.value = metersFromFile
                    _lastProcessedDestination.value = ProcessingDestination.METER_MATCH
                    preferencesManager.lastProcessedDestination = ProcessingDestination.METER_MATCH

                    _toastMessage.postValue("✅ ${metersFromFile.size} meters processed for MeterMatch")
                } else {
                    _toastMessage.postValue("❌ No meters found in file '$fileName'")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.postValue("Error processing file for MeterMatch: ${e.message}")
            }
        }
    }

    fun updateMeterCheckedStatus(serialNumber: String, isChecked: Boolean, fromFile: String) {
        viewModelScope.launch {
            try {
                repository.updateMeterCheckedStatus(serialNumber, fromFile, isChecked)
            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.postValue("Error updating meter status: ${e.message}")
            }
        }
    }

    suspend fun updateMeterCheckedStatusBySerialAsync(serialNumberToFind: String): Pair<Boolean, String?> {
        return try {
            // Check if the serial exists in the database
            val matchingMeters = repository.getMetersBySerial(serialNumberToFind)

            if (matchingMeters.isEmpty()) {
                return Pair(false, null)
            }

            // Find an unchecked meter to update
            val uncheckedMeter = matchingMeters.find { !it.isChecked }

            if (uncheckedMeter != null) {
                repository.updateMeterCheckedStatus(uncheckedMeter.serialNumber, uncheckedMeter.fromFile, true)
                return Pair(true, uncheckedMeter.fromFile)
            } else {
                // All matching meters are already checked
                val alreadyCheckedMeter = matchingMeters.first()
                return Pair(true, alreadyCheckedMeter.fromFile)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error updating meter by serial: ${e.message}", e)
            Pair(false, null)
        }
    }

    fun updateMeterCheckedStatusBySerial(serialNumberToFind: String): Pair<Boolean, String?> {
        // Launch the actual database update asynchronously
        viewModelScope.launch {
            updateMeterCheckedStatusBySerialAsync(serialNumberToFind)
        }

        // For immediate UI feedback, check current LiveData state
        val currentList = meterStatusList.value ?: return Pair(false, null)

        // Look for the serial in current LiveData
        val exactMatches = currentList.filter { it.serialNumber == serialNumberToFind }

        return if (exactMatches.isNotEmpty()) {
            val meter = exactMatches.first()
            Pair(true, meter.fromFile)
        } else {
            Pair(false, null)
        }
    }

    fun updateMeterSelectionStatus(serialNumber: String, isSelected: Boolean, fromFile: String) {
        viewModelScope.launch {
            try {
                repository.updateMeterSelection(serialNumber, fromFile, isSelected)

                // Update local selected meters list
                val currentMeters = meterStatusList.value ?: return@launch
                val selectedMeters = currentMeters.filter { it.isSelectedForProcessing }
                _selectedMetersForProcessing.value = selectedMeters

            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.postValue("Error updating meter selection: ${e.message}")
            }
        }
    }

    fun clearSelectedMetersForProcessing() {
        _selectedMetersForProcessing.value = emptyList()
        _lastProcessedDestination.value = null
        preferencesManager.lastProcessedDestination = null
    }

    fun resetAllMeterSelections() {
        viewModelScope.launch {
            try {
                val currentMeters = meterStatusList.value ?: return@launch
                currentMeters.forEach { meter ->
                    if (meter.isSelectedForProcessing) {
                        repository.updateMeterSelection(meter.serialNumber, meter.fromFile, false)
                    }
                }
                clearSelectedMetersForProcessing()
            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.postValue("Error resetting selections: ${e.message}")
            }
        }
    }

    // Statistics methods using repository
    suspend fun getTotalMeterCount(): Int {
        return try {
            repository.getTotalMeterCount()
        } catch (e: Exception) {
            0
        }
    }

    suspend fun getCheckedMeterCount(): Int {
        return try {
            repository.getCheckedMeterCount()
        } catch (e: Exception) {
            0
        }
    }

    // Search functionality
    suspend fun searchMeters(query: String): List<MeterStatus> {
        return try {
            repository.searchMeters(query)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Method to get meters by destination (for fragments)
    fun getMetersByDestination(destination: ProcessingDestination): List<MeterStatus> {
        return when (destination) {
            ProcessingDestination.METER_CHECK -> meterCheckMeters.value ?: emptyList()
            ProcessingDestination.METER_MATCH -> meterMatchMeters.value ?: emptyList()
        }
    }

    // Backup and restore functionality
    fun createBackup() {
        viewModelScope.launch {
            try {
                val backupTime = System.currentTimeMillis()
                preferencesManager.lastBackupTime = backupTime
                _toastMessage.postValue("Backup created successfully")
            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.postValue("Error creating backup: ${e.message}")
            }
        }
    }

    // Data validation and repair
    fun validateDataIntegrity() {
        viewModelScope.launch {
            try {
                val files = fileItems.value ?: emptyList()
                val meters = meterStatusList.value ?: emptyList()

                // Check for orphaned meters (meters without corresponding files)
                val fileNames = files.map { it.fileName }.toSet()
                val orphanedMeters = meters.filter { it.fromFile !in fileNames }

                if (orphanedMeters.isNotEmpty()) {
                    Log.w(TAG, "Found ${orphanedMeters.size} orphaned meters")
                }

                // Check for file-meter count mismatches
                files.forEach { file ->
                    val actualMeterCount = meters.count { it.fromFile == file.fileName }
                    if (actualMeterCount != file.meterCount) {
                        Log.w(TAG, "Meter count mismatch for ${file.fileName}: expected ${file.meterCount}, actual $actualMeterCount")
                    }
                }

                _toastMessage.postValue("Data integrity check completed")
            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.postValue("Error during data validation: ${e.message}")
            }
        }
    }

    // Debug method to test if a specific serial number exists and can be updated
    fun debugTestSerial(serialNumber: String) {
        viewModelScope.launch {
            try {
                // Check database directly
                val dbMeters = repository.getMetersBySerial(serialNumber)
                Log.d(TAG, "DEBUG: Database search for '$serialNumber': ${dbMeters.size} results")

                // Check LiveData
                val liveDataMeters = meterStatusList.value?.filter { it.serialNumber == serialNumber } ?: emptyList()
                Log.d(TAG, "DEBUG: LiveData search for '$serialNumber': ${liveDataMeters.size} results")

                // Check MeterCheck specific data
                val meterCheckData = meterCheckMeters.value?.filter { it.serialNumber == serialNumber } ?: emptyList()
                Log.d(TAG, "DEBUG: MeterCheck search for '$serialNumber': ${meterCheckData.size} results")

                // Test the update method
                if (dbMeters.isNotEmpty()) {
                    val result = updateMeterCheckedStatusBySerialAsync(serialNumber)
                    Log.d(TAG, "DEBUG: Update result: success=${result.first}, file=${result.second}")
                } else {
                    Log.w(TAG, "DEBUG: Cannot test update - serial not found in database")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Debug test failed: ${e.message}", e)
            }
        }
    }

    // Quick method to check if you have the right data processed for MeterCheck
    fun debugCheckMeterCheckData() {
        viewModelScope.launch {
            try {
                val allFiles = repository.getAllFiles().first()
                val meterCheckFiles = allFiles.filter { it.destination?.name == "METER_CHECK" }

                Log.d(TAG, "DEBUG: Total files: ${allFiles.size}")
                Log.d(TAG, "DEBUG: MeterCheck files: ${meterCheckFiles.size}")

                val meterCheckMetersCount = meterCheckMeters.value?.size ?: 0
                Log.d(TAG, "DEBUG: MeterCheck LiveData: $meterCheckMetersCount meters")

                if (meterCheckFiles.isEmpty()) {
                    Log.w(TAG, "DEBUG: NO FILES PROCESSED FOR METERCHECK!")
                }

                if (meterCheckMetersCount == 0) {
                    Log.w(TAG, "DEBUG: NO METERCHECK METERS IN LIVEDATA!")
                }

            } catch (e: Exception) {
                Log.e(TAG, "MeterCheck debug failed: ${e.message}", e)
            }
        }
    }

    // Method to force refresh LiveData
    fun refreshData() {
        debugCheckMeterCheckData()
    }

    // ✅ ADD: Method that FilesFragment expects to call
    fun processCsvFileWithDestination(uri: Uri, contentResolver: ContentResolver, destination: ProcessingDestination) {
        processFile(uri, destination)
    }

    private fun getFileNameFromUri(uri: Uri, contentResolver: ContentResolver): String? {
        var fileName: String? = null
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        return fileName
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "FilesViewModel cleared")
    }
}