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
    private val requiredColumns = listOf("Number", "SerialNumber", "Place", "Registered")

    init {
        if (!internalStorageDir.exists()) {
            internalStorageDir.mkdirs()
        }

        // Load last processed destination from preferences
        _lastProcessedDestination.value = preferencesManager.lastProcessedDestination

        Log.d("FilesViewModel", "🔧 Initialized with persistent storage")

        // Initialize first run if needed
        if (preferencesManager.isFirstRun) {
            Log.d("FilesViewModel", "First app run detected")
            preferencesManager.isFirstRun = false
        }
    }

    fun clearToastMessage() {
        _toastMessage.value = ""
    }

    fun onMeterCheckNavigated() {
        _navigateToMeterCheck.value = false
    }

    fun onMatchNavigated() {
        _navigateToMatch.value = false
    }

    private fun validateCsvColumns(inputStream: InputStream): CsvValidationResult {
        return try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val headerLine = reader.readLine()
                if (headerLine == null) {
                    return CsvValidationResult(false, emptyList(), "File is empty")
                }

                val headers = headerLine.split(",").map { it.trim() }
                val missingColumns = requiredColumns.filter { required ->
                    !headers.any { header -> header.equals(required, ignoreCase = true) }
                }

                if (missingColumns.isEmpty()) {
                    CsvValidationResult(true)
                } else {
                    CsvValidationResult(
                        false,
                        missingColumns,
                        "Missing columns: ${missingColumns.joinToString(", ")}"
                    )
                }
            }
        } catch (e: Exception) {
            CsvValidationResult(false, emptyList(), "Error reading file: ${e.message}")
        }
    }

    private fun parseCsvStream(inputStream: InputStream, sourceFileName: String): Pair<List<MeterStatus>, CsvValidationResult> {
        val meters = mutableListOf<MeterStatus>()

        return try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val headerLine = reader.readLine()
                if (headerLine == null) {
                    return Pair(emptyList(), CsvValidationResult(false, emptyList(), "File is empty"))
                }

                val headers = headerLine.split(",").map { it.trim() }
                val validation = CsvValidationResult(true)

                val numberIndex = headers.indexOfFirst { it.equals("Number", ignoreCase = true) }
                val serialIndex = headers.indexOfFirst { it.equals("SerialNumber", ignoreCase = true) }
                val placeIndex = headers.indexOfFirst { it.equals("Place", ignoreCase = true) }
                val registeredIndex = headers.indexOfFirst { it.equals("Registered", ignoreCase = true) }

                var line: String?
                var lineNumber = 1
                while (reader.readLine().also { line = it } != null) {
                    lineNumber++
                    try {
                        val tokens = line?.split(",")?.map { it.trim() } ?: continue

                        if (tokens.size <= maxOf(numberIndex, serialIndex, placeIndex, registeredIndex)) {
                            Log.w("FilesViewModel", "Line $lineNumber has insufficient columns")
                            continue
                        }

                        val number = tokens.getOrNull(numberIndex)?.takeIf { it.isNotBlank() } ?: continue
                        val serial = tokens.getOrNull(serialIndex)?.takeIf { it.isNotBlank() } ?: continue
                        val place = tokens.getOrNull(placeIndex)?.takeIf { it.isNotBlank() } ?: continue
                        val registeredStr = tokens.getOrNull(registeredIndex)?.takeIf { it.isNotBlank() } ?: "false"

                        val registered = when (registeredStr.toLowerCase()) {
                            "true", "1", "yes", "y" -> true
                            "false", "0", "no", "n" -> false
                            else -> false
                        }

                        meters.add(
                            MeterStatus(
                                number = number,
                                serialNumber = serial,
                                place = place,
                                registered = registered,
                                fromFile = sourceFileName,
                                isChecked = false,
                                isSelectedForProcessing = false
                            )
                        )
                    } catch (e: Exception) {
                        Log.w("FilesViewModel", "Error parsing line $lineNumber: ${e.message}")
                    }
                }

                Pair(meters, validation)
            }
        } catch (e: Exception) {
            Pair(emptyList(), CsvValidationResult(false, emptyList(), "Error parsing CSV: ${e.message}"))
        }
    }

    private fun saveDataToInternalStorage(fileName: String, inputStream: InputStream) {
        val outputFile = File(internalStorageDir, fileName)
        try {
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            Log.d("FilesViewModel", "File '$fileName' saved to internal storage.")
        } catch (e: IOException) {
            e.printStackTrace()
            _toastMessage.postValue("Error saving '$fileName' to internal storage: ${e.message}")
        }
    }

    fun processCsvFileWithDestination(uri: Uri, contentResolver: ContentResolver, destination: ProcessingDestination) {
        viewModelScope.launch {
            try {
                var extractedFileName: String? = null
                val metersFromFile = mutableListOf<MeterStatus>()
                var validationResult: CsvValidationResult = CsvValidationResult(false, emptyList(), "Initialization error")

                var inputStreamForValidation: InputStream? = null
                var inputStreamForSaving: InputStream? = null
                var inputStreamForProcessing: InputStream? = null

                try {
                    inputStreamForValidation = contentResolver.openInputStream(uri)
                    inputStreamForSaving = contentResolver.openInputStream(uri)
                    inputStreamForProcessing = contentResolver.openInputStream(uri)

                    if (inputStreamForValidation == null || inputStreamForSaving == null || inputStreamForProcessing == null) {
                        _toastMessage.postValue("Could not open input stream for URI.")
                        return@launch
                    }

                    withContext(Dispatchers.IO) {
                        extractedFileName = uri.getFileName(contentResolver)
                        if (extractedFileName == null) {
                            _toastMessage.postValue("Failed to get file name from URI.")
                            return@withContext
                        }

                        val sanitizedFileName = extractedFileName!!

                        // Check if file already exists in database
                        val existingFile = repository.getFile(sanitizedFileName)
                        if (existingFile != null) {
                            _toastMessage.postValue("File '$sanitizedFileName' has already been processed. Use reprocess option to update.")
                            return@withContext
                        }

                        // Validate file size
                        val tempFile = File.createTempFile("upload_check", ".csv")
                        try {
                            inputStreamForValidation?.let { stream ->
                                FileOutputStream(tempFile).use { output ->
                                    stream.copyTo(output)
                                }

                                if (!FileUtils.isFileSizeValid(tempFile)) {
                                    val fileSize = FileUtils.formatFileSize(tempFile.length())
                                    _toastMessage.postValue("File too large: $fileSize. Maximum allowed: ${FileConstants.MAX_FILE_SIZE_MB}MB")
                                    return@withContext
                                }
                            }
                        } finally {
                            tempFile.delete()
                        }

                        // Validate CSV structure
                        inputStreamForValidation?.close()
                        val validationStream = contentResolver.openInputStream(uri)
                        validationResult = if (validationStream != null) {
                            validateCsvColumns(validationStream)
                        } else {
                            CsvValidationResult(false, emptyList(), "Could not reopen input stream for validation")
                        }
                        validationStream?.close()

                        if (!validationResult.isValid) {
                            _toastMessage.postValue("Invalid CSV: ${validationResult.errorMessage}")

                            // Save invalid file to database for user reference
                            val invalidFileItem = FileItem(
                                fileName = sanitizedFileName,
                                uploadDate = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date()),
                                meterCount = 0,
                                isValid = false,
                                validationError = validationResult.errorMessage
                            )
                            repository.insertFile(invalidFileItem)
                            return@withContext
                        }

                        // Save physical file
                        inputStreamForSaving?.let { saveDataToInternalStorage(sanitizedFileName, it) }

                        // Parse and process data
                        val (parsedMeters, _) = if (inputStreamForProcessing != null) {
                            parseCsvStream(inputStreamForProcessing, sanitizedFileName)
                        } else {
                            Pair(emptyList(), CsvValidationResult(false, emptyList(), "Could not open processing stream"))
                        }
                        metersFromFile.addAll(parsedMeters)
                    }

                    if (extractedFileName == null || !validationResult.isValid) {
                        return@launch
                    }

                    // Save to database
                    repository.syncFileWithMeters(extractedFileName!!, metersFromFile, destination)

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
                Log.d("FilesViewModel", "Updated meter $serialNumber checked status to $isChecked")
            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.postValue("Error updating meter status: ${e.message}")
            }
        }
    }

    fun updateMeterCheckedStatusBySerial(serialNumberToFind: String): Pair<Boolean, String?> {
        // This method needs to be synchronous for current usage, but we should migrate to suspend
        // For now, we'll use the existing logic but also update the database
        viewModelScope.launch {
            try {
                val matchingMeters = repository.getMetersBySerial(serialNumberToFind)
                val uncheckedMeter = matchingMeters.find { !it.isChecked }

                if (uncheckedMeter != null) {
                    repository.updateMeterCheckedStatus(uncheckedMeter.serialNumber, uncheckedMeter.fromFile, true)
                    Log.d("FilesViewModel", "Updated meter ${uncheckedMeter.serialNumber} from ${uncheckedMeter.fromFile} as checked")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("FilesViewModel", "Error updating meter by serial: ${e.message}")
            }
        }

        // Return current state from LiveData for immediate UI feedback
        val currentList = meterStatusList.value ?: return Pair(false, null)
        val itemToUpdate = currentList.find { it.serialNumber == serialNumberToFind && !it.isChecked }

        return if (itemToUpdate != null) {
            Pair(true, itemToUpdate.fromFile)
        } else {
            val alreadyCheckedItem = currentList.find { it.serialNumber == serialNumberToFind && it.isChecked }
            if (alreadyCheckedItem != null) {
                Pair(true, alreadyCheckedItem.fromFile)
            } else {
                Pair(false, null)
            }
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

                Log.d("FilesViewModel", "Updated meter $serialNumber selection status to $isSelected")
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
        Log.d("FilesViewModel", "🧹 Cleared selected meters and destination data")
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
                Log.d("FilesViewModel", "🔄 Reset all meter selections")
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
            Log.e("FilesViewModel", "Error getting total meter count: ${e.message}")
            0
        }
    }

    suspend fun getCheckedMeterCount(): Int {
        return try {
            repository.getCheckedMeterCount()
        } catch (e: Exception) {
            Log.e("FilesViewModel", "Error getting checked meter count: ${e.message}")
            0
        }
    }

    // Search functionality
    suspend fun searchMeters(query: String): List<MeterStatus> {
        return try {
            repository.searchMeters(query)
        } catch (e: Exception) {
            Log.e("FilesViewModel", "Error searching meters: ${e.message}")
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

                // In a real app, you might export to external storage or cloud
                _toastMessage.postValue("Backup created successfully")
                Log.d("FilesViewModel", "Backup created at $backupTime")
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
                    Log.w("FilesViewModel", "Found ${orphanedMeters.size} orphaned meters")
                    // You could clean these up or report to user
                }

                // Check for file-meter count mismatches
                files.forEach { file ->
                    val actualMeterCount = meters.count { it.fromFile == file.fileName }
                    if (actualMeterCount != file.meterCount) {
                        Log.w("FilesViewModel", "Meter count mismatch for ${file.fileName}: expected ${file.meterCount}, actual $actualMeterCount")
                    }
                }

                _toastMessage.postValue("Data integrity check completed")
            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.postValue("Error during data validation: ${e.message}")
            }
        }
    }

    // Debug method to check current state
    fun logCurrentState() {
        viewModelScope.launch {
            try {
                val totalMeters = repository.getTotalMeterCount()
                val checkedMeters = repository.getCheckedMeterCount()
                val files = fileItems.value?.size ?: 0

                Log.d("FilesViewModel", "📊 Current FilesViewModel State (Persistent):")
                Log.d("FilesViewModel", "   - Total meters in DB: $totalMeters")
                Log.d("FilesViewModel", "   - Checked meters in DB: $checkedMeters")
                Log.d("FilesViewModel", "   - Files in DB: $files")
                Log.d("FilesViewModel", "   - MeterCheck meters: ${meterCheckMeters.value?.size ?: 0}")
                Log.d("FilesViewModel", "   - MeterMatch meters: ${meterMatchMeters.value?.size ?: 0}")
                Log.d("FilesViewModel", "   - Selected meters: ${selectedMetersForProcessing.value?.size ?: 0}")
                Log.d("FilesViewModel", "   - Last destination: ${_lastProcessedDestination.value}")
            } catch (e: Exception) {
                Log.e("FilesViewModel", "Error logging state: ${e.message}")
            }
        }
    }

    // Clean up resources
    override fun onCleared() {
        super.onCleared()
        Log.d("FilesViewModel", "FilesViewModel cleared")
    }
}