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
                    ?: "unknown_${System.currentTimeMillis()}.csv"

                Log.d(TAG, "Processing file: $fileName for destination: $destination")

                // Copy file to internal storage
                val internalFile = File(internalStorageDir, fileName)
                copyUriToInternalStorage(uri, internalFile, contentResolver)

                // Read and parse the CSV
                val csvData = readCsvFile(internalFile)
                val meters = parseCsvData(csvData, fileName)

                Log.d(TAG, "Parsed ${meters.size} meters from $fileName")

                if (meters.isNotEmpty()) {
                    // Save to database
                    repository.syncFileWithMeters(fileName, meters, destination)

                    _selectedMetersForProcessing.value = meters
                    _lastProcessedDestination.value = destination
                    preferencesManager.lastProcessedDestination = destination

                    _toastMessage.postValue("✅ ${meters.size} meters processed for ${destination.displayName}")

                    // Trigger navigation
                    when (destination) {
                        ProcessingDestination.METER_CHECK -> _navigateToMeterCheck.postValue(true)
                        ProcessingDestination.METER_MATCH -> _navigateToMatch.postValue(true)
                    }
                } else {
                    _toastMessage.postValue("❌ No valid meters found in file")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing file: ${e.message}", e)
                _toastMessage.postValue("Error processing file: ${e.message}")
            }
        }
    }

    private suspend fun copyUriToInternalStorage(
        sourceUri: Uri,
        destinationFile: File,
        contentResolver: ContentResolver
    ) = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            destinationFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

    private suspend fun readCsvFile(file: File): List<String> = withContext(Dispatchers.IO) {
        try {
            file.readLines()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading CSV file: ${e.message}")
            emptyList()
        }
    }

    private fun parseCsvData(csvLines: List<String>, fromFile: String): List<MeterStatus> {
        if (csvLines.isEmpty()) return emptyList()

        val headers = csvLines.first().split(",").map { it.trim().replace("\"", "") }
        Log.d(TAG, "CSV Headers: $headers")

        // Find required columns
        val serialIndex = headers.indexOfFirst {
            it.equals("SerialNumber", ignoreCase = true) ||
                    it.equals("Serial", ignoreCase = true) ||
                    it.equals("serial_number", ignoreCase = true)
        }
        val numberIndex = headers.indexOfFirst {
            it.equals("Number", ignoreCase = true) ||
                    it.equals("MeterNumber", ignoreCase = true) ||
                    it.equals("meter_number", ignoreCase = true)
        }
        val placeIndex = headers.indexOfFirst {
            it.equals("Place", ignoreCase = true) ||
                    it.equals("Location", ignoreCase = true) ||
                    it.equals("Address", ignoreCase = true)
        }

        if (serialIndex == -1) {
            Log.e(TAG, "SerialNumber column not found in CSV")
            return emptyList()
        }

        val meters = mutableListOf<MeterStatus>()

        for (i in 1 until csvLines.size) {
            try {
                val line = csvLines[i]
                if (line.isBlank()) continue

                val values = line.split(",").map { it.trim().replace("\"", "") }

                if (values.size <= serialIndex) continue

                val serialNumber = values[serialIndex].trim()
                if (serialNumber.isBlank()) continue

                val number = if (numberIndex != -1 && values.size > numberIndex) {
                    values[numberIndex].trim().ifEmpty { "0" }
                } else "0"

                val place = if (placeIndex != -1 && values.size > placeIndex) {
                    values[placeIndex].trim().ifEmpty { "unknown" }
                } else "unknown"

                meters.add(
                    MeterStatus(
                        number = number,
                        serialNumber = serialNumber,
                        place = place,
                        registered = true,
                        fromFile = fromFile,
                        isChecked = false,
                        isSelectedForProcessing = false
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing line ${i + 1}: ${e.message}")
            }
        }

        return meters
    }

    fun deleteFile(fileName: String) {
        viewModelScope.launch {
            try {
                repository.deleteFile(fileName)
                _toastMessage.postValue("File '$fileName' deleted successfully")
            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.postValue("Error deleting '$fileName': ${e.message}")
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

    suspend fun updateMeterLocationAndNumber(serialNumber: String, location: String, number: String) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Updating meter location and number: $serialNumber -> location: $location, number: $number")

                // Get all meters with this serial number
                val matchingMeters = repository.getMetersBySerial(serialNumber)

                if (matchingMeters.isNotEmpty()) {
                    // Update each matching meter
                    for (meter in matchingMeters) {
                        val updatedMeter = meter.copy(
                            place = location,
                            number = number
                        )
                        repository.updateMeter(updatedMeter)
                    }
                    Log.d(TAG, "✅ Updated ${matchingMeters.size} meters with serial $serialNumber")
                } else {
                    Log.w(TAG, "⚠️ No meters found with serial $serialNumber to update")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error updating meter location and number: ${e.message}", e)
                _toastMessage.postValue("Error updating meter: ${e.message}")
                throw e
            }
        }
    }

    // ✅ NEW METHOD: Add new meter to the system (needed by DetectedFragment)
    suspend fun addNewMeter(serialNumber: String, location: String, number: String) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Adding new meter: $serialNumber at $location with number $number")

                // Create a manual entry file name
                val fromFile = "manual_entry_${System.currentTimeMillis()}"

                val newMeter = MeterStatus(
                    serialNumber = serialNumber,
                    place = location,
                    number = number,
                    registered = true,
                    fromFile = fromFile,
                    isChecked = false,
                    isSelectedForProcessing = false
                )

                repository.insertMeters(listOf(newMeter))
                Log.d(TAG, "✅ Successfully added new meter: $serialNumber")
                _toastMessage.postValue("✅ New meter added: $number at $location")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error adding new meter: ${e.message}", e)
                _toastMessage.postValue("Error adding meter: ${e.message}")
                throw e
            }
        }
    }

    // ✅ HELPER METHOD: Determine appropriate file for new meters
    private suspend fun determineAppropriateFile(): String? {
        return try {
            // Try to use the most recent MeterCheck file, or create a manual entry file
            val meterCheckFiles = repository.getFilesByDestination(ProcessingDestination.METER_CHECK).first()
            meterCheckFiles.maxByOrNull { it.uploadDate }?.fileName
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine appropriate file: ${e.message}")
            null
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

                Log.d(TAG, "Data validation: ${files.size} files, ${meters.size} meters")

                var issuesFound = 0
                val fileNames = files.map { it.fileName }.toSet()

                // Check for orphaned meters
                meters.forEach { meter ->
                    if (meter.fromFile !in fileNames && !meter.fromFile.startsWith("manual_entry")) {
                        Log.w(TAG, "Orphaned meter found: ${meter.serialNumber} from ${meter.fromFile}")
                        issuesFound++
                    }
                }

                if (issuesFound > 0) {
                    _toastMessage.postValue("⚠️ Data validation found $issuesFound issues")
                } else {
                    _toastMessage.postValue("✅ Data validation passed")
                }

            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.postValue("Error during validation: ${e.message}")
            }
        }
    }

    // Debug methods
    fun debugTestSerial(serialNumber: String) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "DEBUG: Testing serial number '$serialNumber'")

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

    fun refreshData() {
        debugCheckMeterCheckData()
    }

    fun processCsvFileWithDestination(uri: Uri, contentResolver: ContentResolver, destination: ProcessingDestination) {
        processFile(uri, destination)
    }

    private fun getFileNameFromUri(uri: Uri, contentResolver: ContentResolver): String? {
        var fileName: String? = null

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }

        return fileName ?: "unknown_${System.currentTimeMillis()}.csv"
    }

    // Navigation state management
    fun onNavigateToMeterCheckComplete() {
        _navigateToMeterCheck.value = false
    }

    fun onNavigateToMatchComplete() {
        _navigateToMatch.value = false
    }
}