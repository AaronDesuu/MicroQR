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

    // ======================================
    // FILE PROCESSING METHODS
    // ======================================

    fun processFile(uri: Uri, destination: ProcessingDestination) {
        viewModelScope.launch {
            try {
                val contentResolver = getApplication<Application>().contentResolver
                val fileName = getFileNameFromUri(uri, contentResolver)
                    ?: "uploaded_file_${System.currentTimeMillis()}.csv"

                // Copy file to internal storage
                val internalFile = File(internalStorageDir, fileName)
                copyFileToInternalStorage(uri, internalFile, contentResolver)

                // Process the file
                val meters = parseAndValidateCsvFile(internalFile, fileName)
                if (meters.isNotEmpty()) {
                    repository.syncFileWithMeters(fileName, meters, destination)
                    _toastMessage.postValue("✅ File processed: ${meters.size} meters loaded")

                    // Update destination tracking
                    _lastProcessedDestination.value = destination
                    preferencesManager.lastProcessedDestination = destination
                } else {
                    _toastMessage.postValue("❌ No valid meters found in file")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing file", e)
                _toastMessage.postValue("❌ Error processing file: ${e.message}")
            }
        }
    }

    /**
     * Process CSV file with destination (used by FilesFragment)
     * This is the main method called from the UI when users upload files
     */
    fun processCsvFileWithDestination(uri: Uri, contentResolver: ContentResolver, destination: ProcessingDestination) {
        viewModelScope.launch {
            try {
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
                Log.e(TAG, "Error processing file for MeterCheck", e)
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
                Log.e(TAG, "Error processing file for Match", e)
                _toastMessage.postValue("Error processing file for Match: ${e.message}")
            }
        }
    }

    fun deleteFile(fileName: String) {
        viewModelScope.launch {
            try {
                repository.deleteFile(fileName)
                _toastMessage.postValue("File '$fileName' deleted successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting file", e)
                _toastMessage.postValue("Error deleting '$fileName': ${e.message}")
            }
        }
    }

    // ======================================
    // METER MANAGEMENT METHODS (ORIGINAL + DETECTED FRAGMENT)
    // ======================================

    fun updateMeterCheckedStatus(serialNumber: String, isChecked: Boolean, fromFile: String) {
        viewModelScope.launch {
            try {
                repository.updateMeterCheckedStatus(serialNumber, fromFile, isChecked)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating meter status", e)
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

    // ======================================
    // DETECTED FRAGMENT INTEGRATION METHODS
    // ======================================

    /**
     * Find a meter by serial number across all loaded data
     */
    suspend fun findMeterBySerial(serialNumber: String): MeterStatus? {
        return withContext(Dispatchers.IO) {
            try {
                // First check MeterCheck data
                meterCheckMeters.value?.find { it.serialNumber == serialNumber }
                    ?: // Then check general meter data
                    meterStatusList.value?.find { it.serialNumber == serialNumber }
                    ?: // Finally check database directly
                    repository.getMetersBySerial(serialNumber).firstOrNull()
            } catch (e: Exception) {
                Log.e(TAG, "Error finding meter by serial: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Update meter location and number information - FIXED
     */
    suspend fun updateMeterLocationAndNumber(serialNumber: String, location: String, number: String) {
        return withContext(Dispatchers.IO) {
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
                    Log.w(TAG, "⚠️ No meters found with serial number: $serialNumber")
                    // Don't throw error here, might be creating new meter
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error updating meter location and number: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Add a new meter to the database and in-memory data - FIXED
     */
    suspend fun addNewMeter(serialNumber: String, location: String, number: String) {
        return withContext(Dispatchers.IO) {
            try {
                val timestamp = System.currentTimeMillis()
                val fileName = "scanner_input_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"

                // Create new meter
                val newMeter = MeterStatus(
                    serialNumber = serialNumber,
                    number = number,
                    place = location,
                    registered = false,  // Will be set to true during S/N verification
                    fromFile = fileName,
                    isChecked = false,
                    isSelectedForProcessing = false
                )

                // Check if file entry exists for scanner entries
                var existingFile = repository.getFile(fileName)
                if (existingFile == null) {
                    // Create file entry for scanner entries
                    val fileItem = FileItem(
                        fileName = fileName,
                        uploadDate = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date()),
                        meterCount = 1,
                        isValid = true,
                        validationError = "",
                        destination = ProcessingDestination.METER_CHECK
                    )
                    repository.insertFile(fileItem)
                } else {
                    // Update meter count
                    val updatedFile = existingFile.copy(
                        meterCount = existingFile.meterCount + 1
                    )
                    repository.updateFile(updatedFile)
                }

                // Insert the meter
                repository.insertMeters(listOf(newMeter))

                Log.d(TAG, "✅ Added new meter: $serialNumber")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error adding new meter: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Add a new meter to the database with a custom file name
     */
    suspend fun addNewMeterWithCustomFileName(
        serialNumber: String,
        location: String,
        number: String,
        fileName: String
    ) {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Adding new meter with custom file name: $fileName")

                // Validate and clean the file name
                val cleanFileName = cleanFileName(fileName)

                // Create new meter
                val newMeter = MeterStatus(
                    serialNumber = serialNumber,
                    number = number,
                    place = location,
                    registered = false,  // Will be set to true during S/N verification
                    fromFile = cleanFileName,
                    isChecked = false,
                    isSelectedForProcessing = false
                )

                // Check if file entry exists
                var existingFile = repository.getFile(cleanFileName)
                if (existingFile == null) {
                    // Create new file entry
                    val fileItem = FileItem(
                        fileName = cleanFileName,
                        uploadDate = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date()),
                        meterCount = 1,
                        isValid = true,
                        validationError = "",
                        destination = ProcessingDestination.METER_CHECK
                    )
                    repository.insertFile(fileItem)
                    Log.d(TAG, "Created new file entry: $cleanFileName")
                } else {
                    // Update meter count for existing file
                    val updatedFile = existingFile.copy(
                        meterCount = existingFile.meterCount + 1
                    )
                    repository.updateFile(updatedFile)
                    Log.d(TAG, "Updated existing file meter count: $cleanFileName")
                }

                // Insert the meter
                repository.insertMeters(listOf(newMeter))

                Log.d(TAG, "✅ Added new meter to custom file: $cleanFileName")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error adding new meter with custom file name: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Clean and validate the file name
     */
    private fun cleanFileName(fileName: String): String {
        var cleaned = fileName.trim()

        // Remove invalid characters
        val invalidChars = charArrayOf('/', '\\', '?', '%', '*', ':', '|', '"', '<', '>')
        invalidChars.forEach { char ->
            cleaned = cleaned.replace(char, '_')
        }

        // Ensure it doesn't start with a dot
        if (cleaned.startsWith(".")) {
            cleaned = "file$cleaned"
        }

        // Ensure it has .csv extension
        if (!cleaned.endsWith(".csv", ignoreCase = true)) {
            cleaned = "$cleaned.csv"
        }

        // Limit length
        if (cleaned.length > 100) {
            val nameWithoutExt = cleaned.substringBeforeLast(".csv")
            cleaned = "${nameWithoutExt.take(96)}.csv"
        }

        return cleaned
    }

    /**
     * Get repository access for direct database operations
     */
    fun getMeterRepository(): MeterRepository {
        return repository
    }

    // ======================================
    // METER SELECTION METHODS
    // ======================================

    fun updateMeterSelection(serialNumber: String, fromFile: String, isSelected: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateMeterSelection(serialNumber, fromFile, isSelected)

                // Update the in-memory selected list
                val currentSelected = _selectedMetersForProcessing.value?.toMutableList() ?: mutableListOf()
                val allMeters = meterStatusList.value ?: emptyList()

                val meter = allMeters.find { it.serialNumber == serialNumber && it.fromFile == fromFile }
                meter?.let {
                    if (isSelected && !currentSelected.contains(it)) {
                        currentSelected.add(it.copy(isSelectedForProcessing = true))
                    } else if (!isSelected) {
                        currentSelected.removeAll { selected ->
                            selected.serialNumber == serialNumber && selected.fromFile == fromFile
                        }
                    }
                }

                _selectedMetersForProcessing.value = currentSelected
                Log.d(TAG, "✅ Updated meter $serialNumber selection to $isSelected")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error updating meter selection: ${e.message}", e)
                _toastMessage.postValue("Error updating selection: ${e.message}")
            }
        }
    }

    fun clearSelectedMetersForProcessing() {
        _selectedMetersForProcessing.value = emptyList()
    }

    fun resetAllSelections() {
        viewModelScope.launch {
            try {
                val currentMeters = meterStatusList.value ?: emptyList()
                currentMeters.forEach { meter ->
                    if (meter.isSelectedForProcessing) {
                        repository.updateMeterSelection(meter.serialNumber, meter.fromFile, false)
                    }
                }
                clearSelectedMetersForProcessing()
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting selections", e)
                _toastMessage.postValue("Error resetting selections: ${e.message}")
            }
        }
    }

    // ======================================
    // NAVIGATION METHODS
    // ======================================

    fun navigateToMeterCheck() {
        _navigateToMeterCheck.value = true
    }

    fun onNavigateToMeterCheckComplete() {
        _navigateToMeterCheck.value = false
    }

    fun navigateToMatch() {
        _navigateToMatch.value = true
    }

    fun onNavigateToMatchComplete() {
        _navigateToMatch.value = false
    }

    // ======================================
    // STATISTICS METHODS
    // ======================================

    suspend fun getTotalMeterCount(): Int {
        return try {
            repository.getTotalMeterCount()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting total meter count", e)
            0
        }
    }

    suspend fun getCheckedMeterCount(): Int {
        return try {
            repository.getCheckedMeterCount()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting checked meter count", e)
            0
        }
    }

    // ======================================
    // SEARCH AND UTILITY METHODS
    // ======================================

    suspend fun searchMeters(query: String): List<MeterStatus> {
        return try {
            repository.searchMeters(query)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching meters", e)
            emptyList()
        }
    }

    fun getMetersByDestination(destination: ProcessingDestination): List<MeterStatus> {
        return when (destination) {
            ProcessingDestination.METER_CHECK -> meterCheckMeters.value ?: emptyList()
            ProcessingDestination.METER_MATCH -> meterMatchMeters.value ?: emptyList()
        }
    }

    // ======================================
    // BACKUP AND MAINTENANCE METHODS
    // ======================================

    fun createBackup() {
        viewModelScope.launch {
            try {
                val backupTime = System.currentTimeMillis()
                preferencesManager.lastBackupTime = backupTime
                _toastMessage.postValue("Backup created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating backup", e)
                _toastMessage.postValue("Error creating backup: ${e.message}")
            }
        }
    }

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
                Log.e(TAG, "Error during validation", e)
                _toastMessage.postValue("Error during validation: ${e.message}")
            }
        }
    }

    // ======================================
    // DEBUG METHODS
    // ======================================

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

    // ======================================
    // PRIVATE HELPER METHODS
    // ======================================

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

    private suspend fun copyFileToInternalStorage(uri: Uri, destinationFile: File, contentResolver: ContentResolver) {
        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
    }

    private suspend fun copyUriToInternalStorage(
        sourceUri: Uri,
        destinationFile: File,
        contentResolver: ContentResolver
    ) = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            FileOutputStream(destinationFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

    private suspend fun readCsvFile(file: File): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                file.readLines()
            } catch (e: Exception) {
                Log.e(TAG, "Error reading CSV file: ${e.message}", e)
                emptyList()
            }
        }
    }

    private fun parseCsvData(csvData: List<String>, fromFile: String): List<MeterStatus> {
        if (csvData.isEmpty()) return emptyList()

        val meters = mutableListOf<MeterStatus>()

        try {
            // Find header and required columns
            val header = csvData.first().split(",").map { it.trim().lowercase() }
            val serialIndex = header.indexOfFirst {
                it.contains("serial") || it.contains("serialnumber") || it == "sn"
            }

            if (serialIndex == -1) {
                throw IllegalArgumentException("SerialNumber column not found")
            }

            val numberIndex = header.indexOfFirst {
                it.contains("number") || it.contains("meter") || it == "no"
            }
            val placeIndex = header.indexOfFirst {
                it.contains("place") || it.contains("location") || it.contains("site")
            }

            // Process data rows
            for (i in 1 until csvData.size) {
                try {
                    val values = csvData[i].split(",").map { it.trim() }
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
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing CSV data: ${e.message}", e)
            throw e
        }

        return meters
    }

    private suspend fun parseAndValidateCsvFile(file: File, fromFile: String): List<MeterStatus> {
        return withContext(Dispatchers.IO) {
            val meters = mutableListOf<MeterStatus>()

            try {
                val lines = file.readLines()
                if (lines.isEmpty()) return@withContext meters

                // Find header and required columns
                val header = lines.first().split(",").map { it.trim().lowercase() }
                val serialIndex = header.indexOfFirst {
                    it.contains("serial") || it.contains("serialnumber") || it == "sn"
                }

                if (serialIndex == -1) {
                    throw IllegalArgumentException("SerialNumber column not found")
                }

                val numberIndex = header.indexOfFirst {
                    it.contains("number") || it.contains("meter") || it == "no"
                }
                val placeIndex = header.indexOfFirst {
                    it.contains("place") || it.contains("location") || it.contains("site")
                }

                // Process data rows
                for (i in 1 until lines.size) {
                    try {
                        val values = lines[i].split(",").map { it.trim() }
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
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing CSV file: ${e.message}", e)
                throw e
            }

            meters
        }
    }
}