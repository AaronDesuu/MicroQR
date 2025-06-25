package com.example.microqr.ui.files

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class FilesViewModel(application: Application) : AndroidViewModel(application) {

    private val _fileItems = MutableLiveData<List<FileItem>>(emptyList())
    val fileItems: LiveData<List<FileItem>> = _fileItems

    private val _meterStatusList = MutableLiveData<List<MeterStatus>>(emptyList())
    val meterStatusList: LiveData<List<MeterStatus>> = _meterStatusList

    private val _selectedMetersForProcessing = MutableLiveData<List<MeterStatus>>(emptyList())
    val selectedMetersForProcessing: LiveData<List<MeterStatus>> = _selectedMetersForProcessing

    // Separate LiveData for each destination to avoid cross-contamination
    private val _meterCheckMeters = MutableLiveData<List<MeterStatus>>(emptyList())
    val meterCheckMeters: LiveData<List<MeterStatus>> = _meterCheckMeters

    private val _meterMatchMeters = MutableLiveData<List<MeterStatus>>(emptyList())
    val meterMatchMeters: LiveData<List<MeterStatus>> = _meterMatchMeters

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

        // Initialize destination-specific LiveData as empty
        _meterCheckMeters.value = emptyList()
        _meterMatchMeters.value = emptyList()
        _selectedMetersForProcessing.value = emptyList()
        _lastProcessedDestination.value = null

        Log.d("FilesViewModel", "🔧 Initialized with empty destination-specific data")

        // Load files from storage (but don't auto-assign to destinations)
        loadDataFromInternalStorage()
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
                val validation = CsvValidationResult(true) // We already validated in validateCsvColumns

                // Find column indices
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

    private fun loadDataFromInternalStorage() {
        viewModelScope.launch {
            val loadedMeterStatusList = mutableListOf<MeterStatus>()
            val loadedFileItems = mutableListOf<FileItem>()

            withContext(Dispatchers.IO) {
                internalStorageDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".csv", ignoreCase = true)) {
                        val fileName = file.name
                        try {
                            FileInputStream(file).use { fis ->
                                val (metersFromFile, validation) = parseCsvStream(fis, fileName)

                                val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                                val uploadDate = dateFormat.format(Date(file.lastModified()))

                                val fileItem = FileItem(
                                    fileName = fileName,
                                    uploadDate = uploadDate,
                                    meterCount = metersFromFile.size,
                                    isValid = validation.isValid,
                                    validationError = validation.errorMessage,
                                    destination = null // Files loaded from storage have no destination initially
                                )

                                loadedFileItems.add(fileItem)

                                if (validation.isValid && metersFromFile.isNotEmpty()) {
                                    loadedMeterStatusList.addAll(metersFromFile)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            val fileItem = FileItem(
                                fileName = fileName,
                                uploadDate = "Unknown",
                                meterCount = 0,
                                isValid = false,
                                validationError = "Error loading file: ${e.message}",
                                destination = null
                            )
                            loadedFileItems.add(fileItem)
                        }
                    }
                }
            }

            _fileItems.postValue(loadedFileItems)
            _meterStatusList.postValue(loadedMeterStatusList)

            // DO NOT set destination-specific data when loading from storage
            // Only show message if files were loaded, but don't auto-assign to destinations
            if (loadedFileItems.isNotEmpty()) {
                _toastMessage.postValue("Found ${loadedFileItems.size} file(s) with ${loadedMeterStatusList.size} total meters. Process files to assign destinations.")
            }

            Log.d("FilesViewModel", "Loaded ${loadedFileItems.size} files from storage, but NO destination-specific data set")
        }
    }

    // NEW METHOD: Process CSV file with destination
    fun processCsvFileWithDestination(uri: Uri, contentResolver: ContentResolver, destination: ProcessingDestination) {
        viewModelScope.launch {
            try {
                var extractedFileName: String? = null
                val metersFromFile = mutableListOf<MeterStatus>()
                var validationResult: CsvValidationResult = CsvValidationResult(false, emptyList(), "Initialization error or URI processing failed")

                var inputStreamForValidation: InputStream? = null
                var inputStreamForSaving: InputStream? = null
                var inputStreamForProcessing: InputStream? = null

                try {
                    inputStreamForValidation = contentResolver.openInputStream(uri)
                    inputStreamForSaving = contentResolver.openInputStream(uri)
                    inputStreamForProcessing = contentResolver.openInputStream(uri)

                    if (inputStreamForValidation == null || inputStreamForSaving == null || inputStreamForProcessing == null) {
                        _toastMessage.postValue("Could not open input stream for URI.")
                        validationResult = CsvValidationResult(false, emptyList(), "Could not open input stream for URI.")
                    } else {
                        withContext(Dispatchers.IO) {
                            extractedFileName = uri.getFileName(contentResolver)
                            if (extractedFileName == null) {
                                _toastMessage.postValue("Failed to get file name from URI.")
                                validationResult = CsvValidationResult(false, emptyList(), "Failed to get file name from URI.")
                                return@withContext
                            }

                            val sanitizedFileName = extractedFileName!!
                            val internalFile = File(internalStorageDir, sanitizedFileName)

                            if (internalFile.exists()) {
                                _toastMessage.postValue("File '$sanitizedFileName' has already been processed and saved.")
                                validationResult = CsvValidationResult(false, emptyList(), "File already exists: $sanitizedFileName")
                                return@withContext
                            }

                            // First validate file size
                            val tempFile = File.createTempFile("upload_check", ".csv")
                            try {
                                inputStreamForValidation?.let { stream ->
                                    FileOutputStream(tempFile).use { output ->
                                        stream.copyTo(output)
                                    }

                                    if (!FileUtils.isFileSizeValid(tempFile)) {
                                        val fileSize = FileUtils.formatFileSize(tempFile.length())
                                        _toastMessage.postValue("File too large: $fileSize. Maximum allowed: ${FileConstants.MAX_FILE_SIZE_MB}MB")
                                        validationResult = CsvValidationResult(false, emptyList(), "File size exceeds ${FileConstants.MAX_FILE_SIZE_MB}MB limit")
                                        return@withContext
                                    }
                                }
                            } finally {
                                tempFile.delete()
                            }

                            // Close and reopen input stream for CSV validation
                            inputStreamForValidation?.close()
                            val validationStream = contentResolver.openInputStream(uri)

                            // Validate the CSV structure
                            validationResult = if (validationStream != null) {
                                validateCsvColumns(validationStream)
                            } else {
                                CsvValidationResult(false, emptyList(), "Could not reopen input stream for validation")
                            }
                            validationStream?.close()

                            if (!validationResult.isValid) {
                                _toastMessage.postValue("Invalid CSV: ${validationResult.errorMessage}")
                                // Still save the file but mark it as invalid
                                inputStreamForSaving?.let { saveDataToInternalStorage(sanitizedFileName, it) }

                                val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                                val currentDate = dateFormat.format(Date())

                                val fileItem = FileItem(
                                    fileName = sanitizedFileName,
                                    uploadDate = currentDate,
                                    meterCount = 0,
                                    isValid = false,
                                    validationError = validationResult.errorMessage
                                )

                                val currentFileItems = _fileItems.value?.toMutableList() ?: mutableListOf()
                                currentFileItems.add(fileItem)
                                _fileItems.postValue(currentFileItems)
                                return@withContext
                            }

                            // Save the file to internal storage
                            inputStreamForSaving?.let { saveDataToInternalStorage(sanitizedFileName, it) }

                            // Process the data
                            val (parsedMeters, _) = if (inputStreamForProcessing != null) {
                                parseCsvStream(inputStreamForProcessing, sanitizedFileName)
                            } else {
                                Pair(emptyList(), CsvValidationResult(false, emptyList(), "Could not open processing stream"))
                            }
                            metersFromFile.addAll(parsedMeters)
                        }
                    }

                    if (extractedFileName == null) {
                        Log.e("FilesViewModel", "File processing aborted due to null extractedFileName. Validation: ${validationResult.errorMessage}")
                        return@launch
                    }

                    if (validationResult.isValid) {
                        val currentGlobalList = _meterStatusList.value?.toMutableList() ?: mutableListOf()
                        currentGlobalList.addAll(metersFromFile)
                        _meterStatusList.postValue(currentGlobalList)

                        _toastMessage.postValue("File '$extractedFileName' processed successfully: ${metersFromFile.size} meters added.")

                        // Create date format for file item
                        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                        val currentDate = dateFormat.format(Date())

                        // Mark the file with its destination
                        val fileItemWithDestination = FileItem(
                            fileName = extractedFileName!!,
                            uploadDate = currentDate,
                            meterCount = metersFromFile.size,
                            isValid = true,
                            validationError = "",
                            destination = destination
                        )

                        val currentFileItems = _fileItems.value?.toMutableList() ?: mutableListOf()
                        // Remove the old item and add the updated one
                        currentFileItems.removeAll { it.fileName == extractedFileName }
                        currentFileItems.add(fileItemWithDestination)
                        _fileItems.postValue(currentFileItems)

                        // Set selected meters for processing and update destination-specific data
                        _selectedMetersForProcessing.value = metersFromFile
                        _lastProcessedDestination.value = destination

                        // Update destination-specific LiveData
                        when (destination) {
                            ProcessingDestination.METER_CHECK -> {
                                _meterCheckMeters.value = metersFromFile
                                _meterMatchMeters.value = emptyList() // Clear other destination
                                _toastMessage.postValue("✅ File processed for MeterCheck: ${metersFromFile.size} meters ready")
                            }
                            ProcessingDestination.METER_MATCH -> {
                                _meterMatchMeters.value = metersFromFile
                                _meterCheckMeters.value = emptyList() // Clear other destination
                                _toastMessage.postValue("✅ File processed for MeterMatch: ${metersFromFile.size} meters ready")
                            }
                        }
                    } else {
                        Log.w("FilesViewModel", "Skipping meter addition for '$extractedFileName' as validation was not successful. Reason: ${validationResult.errorMessage}")
                    }

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

    // Keep the original method for backward compatibility (if needed)
    fun processCsvFile(uri: Uri, contentResolver: ContentResolver) {
        // This can now just call the new method with a default destination
        processCsvFileWithDestination(uri, contentResolver, ProcessingDestination.METER_CHECK)
    }

    fun deleteFile(fileNameToDelete: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val fileInInternalStorage = File(internalStorageDir, fileNameToDelete)
                if (fileInInternalStorage.exists()) {
                    if (fileInInternalStorage.delete()) {
                        Log.d("FilesViewModel", "File '$fileNameToDelete' deleted from internal storage.")
                    } else {
                        _toastMessage.postValue("Error deleting '$fileNameToDelete' from storage.")
                        return@withContext
                    }
                }

                // Update file items
                val currentFileItems = _fileItems.value?.toMutableList() ?: mutableListOf()
                currentFileItems.removeAll { it.fileName == fileNameToDelete }
                _fileItems.postValue(currentFileItems)

                // Update meter status list - remove meters from deleted file
                val currentGlobalMeterList = _meterStatusList.value?.toMutableList() ?: mutableListOf()
                val originalSize = currentGlobalMeterList.size
                val updatedMeterList = currentGlobalMeterList.filterNot { it.fromFile == fileNameToDelete }
                val metersRemovedCount = originalSize - updatedMeterList.size

                _meterStatusList.postValue(updatedMeterList)

                // CRITICAL: Update destination-specific LiveData to remove meters from deleted file
                val currentMeterCheckMeters = _meterCheckMeters.value?.toMutableList() ?: mutableListOf()
                val updatedMeterCheckMeters = currentMeterCheckMeters.filterNot { it.fromFile == fileNameToDelete }
                _meterCheckMeters.postValue(updatedMeterCheckMeters)

                val currentMeterMatchMeters = _meterMatchMeters.value?.toMutableList() ?: mutableListOf()
                val updatedMeterMatchMeters = currentMeterMatchMeters.filterNot { it.fromFile == fileNameToDelete }
                _meterMatchMeters.postValue(updatedMeterMatchMeters)

                // Clear selected meters if they were from the deleted file
                val currentSelectedMeters = _selectedMetersForProcessing.value?.toMutableList() ?: mutableListOf()
                val updatedSelectedMeters = currentSelectedMeters.filterNot { it.fromFile == fileNameToDelete }
                _selectedMetersForProcessing.postValue(updatedSelectedMeters)

                Log.d("FilesViewModel", "🗑️ File '$fileNameToDelete' deleted - updated all LiveData streams")
                Log.d("FilesViewModel", "   - MeterCheck meters: ${currentMeterCheckMeters.size} → ${updatedMeterCheckMeters.size}")
                Log.d("FilesViewModel", "   - MeterMatch meters: ${currentMeterMatchMeters.size} → ${updatedMeterMatchMeters.size}")
                Log.d("FilesViewModel", "   - Selected meters: ${currentSelectedMeters.size} → ${updatedSelectedMeters.size}")

                _toastMessage.postValue("File '$fileNameToDelete' and its $metersRemovedCount meters removed from all destinations.")
            }
        }
    }

    fun processForMeterCheck(fileName: String) {
        val metersFromFile = _meterStatusList.value?.filter { it.fromFile == fileName } ?: emptyList()
        if (metersFromFile.isNotEmpty()) {
            // Clear all destination data first
            clearSelectedMetersForProcessing()

            // Set new selection for MeterCheck
            _selectedMetersForProcessing.value = metersFromFile
            _meterCheckMeters.value = metersFromFile
            _meterMatchMeters.value = emptyList() // Clear other destination
            _lastProcessedDestination.value = ProcessingDestination.METER_CHECK

            // Update the file item with new destination
            updateFileDestination(fileName, ProcessingDestination.METER_CHECK)

            _toastMessage.postValue("✅ ${metersFromFile.size} meters processed for MeterCheck")
        } else {
            _toastMessage.postValue("❌ No meters found in file '$fileName'")
        }
    }

    fun processForMatch(fileName: String) {
        val metersFromFile = _meterStatusList.value?.filter { it.fromFile == fileName } ?: emptyList()
        if (metersFromFile.isNotEmpty()) {
            // Clear all destination data first
            clearSelectedMetersForProcessing()

            // Set new selection for MeterMatch
            _selectedMetersForProcessing.value = metersFromFile
            _meterMatchMeters.value = metersFromFile
            _meterCheckMeters.value = emptyList() // Clear other destination
            _lastProcessedDestination.value = ProcessingDestination.METER_MATCH

            // Update the file item with new destination
            updateFileDestination(fileName, ProcessingDestination.METER_MATCH)

            _toastMessage.postValue("✅ ${metersFromFile.size} meters processed for MeterMatch")
        } else {
            _toastMessage.postValue("❌ No meters found in file '$fileName'")
        }
    }

    private fun updateFileDestination(fileName: String, destination: ProcessingDestination) {
        val currentFileItems = _fileItems.value?.toMutableList() ?: return
        val fileIndex = currentFileItems.indexOfFirst { it.fileName == fileName }

        if (fileIndex != -1) {
            val oldDestination = currentFileItems[fileIndex].destination
            val updatedFile = currentFileItems[fileIndex].copy(destination = destination)
            currentFileItems[fileIndex] = updatedFile
            _fileItems.postValue(currentFileItems)

            Log.d("FilesViewModel", "File '$fileName' destination changed from $oldDestination to $destination")
        }
    }

    // Method to check if selected meters match a specific destination
    fun areSelectedMetersForDestination(destination: ProcessingDestination): Boolean {
        val selectedMeters = _selectedMetersForProcessing.value ?: return false
        if (selectedMeters.isEmpty()) return false

        val selectedFileNames = selectedMeters.map { it.fromFile }.distinct()
        val destinationFiles = _fileItems.value?.filter {
            it.destination == destination
        }?.map { it.fileName } ?: emptyList()

        return selectedFileNames.all { it in destinationFiles }
    }

    fun updateMeterCheckedStatus(serialNumber: String, isChecked: Boolean, fromFile: String) {
        val currentList = _meterStatusList.value?.toMutableList() ?: return
        val itemIndex = currentList.indexOfFirst {
            it.serialNumber == serialNumber && it.fromFile == fromFile
        }

        if (itemIndex != -1) {
            currentList[itemIndex] = currentList[itemIndex].copy(isChecked = isChecked)
            _meterStatusList.value = currentList
        }
    }

    fun updateMeterCheckedStatusBySerial(serialNumberToFind: String): Pair<Boolean, String?> {
        val currentList = _meterStatusList.value?.toMutableList() ?: return Pair(false, null)
        val itemIndex = currentList.indexOfFirst { it.serialNumber == serialNumberToFind && !it.isChecked }

        if (itemIndex != -1) {
            val itemToUpdate = currentList[itemIndex]
            val originalFromFile = itemToUpdate.fromFile

            currentList[itemIndex] = itemToUpdate.copy(isChecked = true)
            _meterStatusList.value = ArrayList(currentList)
            Log.d("FilesViewModel", "Meter '$serialNumberToFind' from '$originalFromFile' marked as checked.")
            return Pair(true, originalFromFile)
        } else {
            val alreadyCheckedItem = currentList.find { it.serialNumber == serialNumberToFind && it.isChecked }
            if (alreadyCheckedItem != null) {
                Log.d("FilesViewModel", "Meter '$serialNumberToFind' from '${alreadyCheckedItem.fromFile}' was already checked.")
                return Pair(true, alreadyCheckedItem.fromFile)
            }
            Log.w("FilesViewModel", "Meter '$serialNumberToFind' not found or already checked.")
            return Pair(false, null)
        }
    }

    fun updateMeterSelectionStatus(serialNumber: String, isSelected: Boolean, fromFile: String) {
        val currentList = _meterStatusList.value?.toMutableList() ?: return
        val itemIndex = currentList.indexOfFirst {
            it.serialNumber == serialNumber && it.fromFile == fromFile
        }

        if (itemIndex != -1) {
            currentList[itemIndex] = currentList[itemIndex].copy(isSelectedForProcessing = isSelected)
            _meterStatusList.value = currentList

            // Also update the selected meters for processing list
            val selectedMeters = currentList.filter { it.isSelectedForProcessing }
            _selectedMetersForProcessing.value = selectedMeters
        }
    }

    // Add methods to clear processing state
    fun clearSelectedMetersForProcessing() {
        _selectedMetersForProcessing.value = emptyList()
        _meterCheckMeters.value = emptyList()
        _meterMatchMeters.value = emptyList()
        _lastProcessedDestination.value = null
        Log.d("FilesViewModel", "🧹 Cleared all selected meters and destination data")
    }

    fun resetAllMeterSelections() {
        val currentList = _meterStatusList.value?.toMutableList() ?: return
        val updatedList = currentList.map { it.copy(isSelectedForProcessing = false) }
        _meterStatusList.value = updatedList
        clearSelectedMetersForProcessing()
        Log.d("FilesViewModel", "🔄 Reset all meter selections and cleared destination data")
    }

    // Debug method to check current state
    fun logCurrentState() {
        Log.d("FilesViewModel", "📊 Current FilesViewModel State:")
        Log.d("FilesViewModel", "   - Total meters: ${_meterStatusList.value?.size ?: 0}")
        Log.d("FilesViewModel", "   - MeterCheck meters: ${_meterCheckMeters.value?.size ?: 0}")
        Log.d("FilesViewModel", "   - MeterMatch meters: ${_meterMatchMeters.value?.size ?: 0}")
        Log.d("FilesViewModel", "   - Selected meters: ${_selectedMetersForProcessing.value?.size ?: 0}")
        Log.d("FilesViewModel", "   - Last destination: ${_lastProcessedDestination.value}")
        Log.d("FilesViewModel", "   - File items: ${_fileItems.value?.size ?: 0}")
    }

    // Method to get meters by destination
    fun getMetersByDestination(destination: ProcessingDestination): List<MeterStatus> {
        val allFileItems = _fileItems.value ?: emptyList()
        val filesWithDestination = allFileItems.filter { it.destination == destination }.map { it.fileName }
        return _meterStatusList.value?.filter { it.fromFile in filesWithDestination } ?: emptyList()
    }
}