package com.example.microqr.data.repository

import android.content.Context
import android.util.Log
import com.example.microqr.data.database.*
import com.example.microqr.ui.files.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.io.File

class MeterRepository(private val context: Context) {
    private val database = MeterDatabase.getDatabase(context)
    private val fileDao = database.fileDao()
    private val meterDao = database.meterDao()

    companion object {
        private const val TAG = "MeterRepository"
    }

    // File operations
    fun getAllFiles(): Flow<List<FileItem>> {
        return fileDao.getAllFiles().map { entities ->
            entities.map { it.toFileItem() }
        }
    }

    suspend fun insertFile(fileItem: FileItem) {
        Log.d(TAG, "Inserting file: ${fileItem.fileName}")
        fileDao.insertFile(fileItem.toEntity())
    }

    suspend fun updateFile(fileItem: FileItem) {
        Log.d(TAG, "Updating file: ${fileItem.fileName}")
        fileDao.updateFile(fileItem.toEntity())
    }

    suspend fun deleteFile(fileName: String) {
        Log.d(TAG, "Deleting file: $fileName")
        // Delete meters first
        meterDao.deleteMetersFromFile(fileName)
        // Then delete the file
        fileDao.deleteFileByName(fileName)

        // Also delete the physical file from internal storage
        val internalStorageDir = File(context.filesDir, "processed_csv")
        val physicalFile = File(internalStorageDir, fileName)
        if (physicalFile.exists()) {
            physicalFile.delete()
            Log.d(TAG, "Physical file deleted: $fileName")
        }
    }

    suspend fun getFile(fileName: String): FileItem? {
        return fileDao.getFile(fileName)?.toFileItem()
    }

    fun getFilesByDestination(destination: ProcessingDestination): Flow<List<FileItem>> {
        return fileDao.getFilesByDestination(destination.name).map { entities ->
            entities.map { it.toFileItem() }
        }
    }

    // Meter operations
    fun getAllMeters(): Flow<List<MeterStatus>> {
        return meterDao.getAllMeters().map { entities ->
            entities.map { it.toMeterStatus() }
        }
    }

    fun getMetersByFile(fileName: String): Flow<List<MeterStatus>> {
        return meterDao.getMetersByFile(fileName).map { entities ->
            entities.map { it.toMeterStatus() }
        }
    }

    suspend fun insertMeters(meters: List<MeterStatus>) {
        Log.d(TAG, "Inserting ${meters.size} meters")
        val entities = meters.map { it.toEntity() }
        meterDao.insertMeters(entities)
    }

    // ✅ NEW METHOD: Update a single meter (needed for DetectedFragment)
    suspend fun updateMeter(meter: MeterStatus) {
        Log.d(TAG, "Updating meter: ${meter.serialNumber}")
        meterDao.updateMeter(meter.toEntity())
    }

    suspend fun updateMeterCheckedStatus(serialNumber: String, fromFile: String, isChecked: Boolean) {
        Log.d(TAG, "Updating meter checked status: $serialNumber = $isChecked")
        meterDao.updateMeterCheckedStatus(serialNumber, fromFile, isChecked)
    }

    suspend fun updateMeterSelection(serialNumber: String, fromFile: String, isSelected: Boolean) {
        Log.d(TAG, "Updating meter selection: $serialNumber = $isSelected")
        meterDao.updateMeterSelection(serialNumber, fromFile, isSelected)
    }

    suspend fun getMetersBySerial(serialNumber: String): List<MeterStatus> {
        return meterDao.getMetersBySerial(serialNumber).map { it.toMeterStatus() }
    }

    suspend fun getMeter(serialNumber: String, fromFile: String): MeterStatus? {
        return meterDao.getMeter(serialNumber, fromFile)?.toMeterStatus()
    }

    fun getMetersByDestination(destination: ProcessingDestination): Flow<List<MeterStatus>> {
        return meterDao.getMetersByDestination(destination.name).map { entities ->
            entities.map { it.toMeterStatus() }
        }
    }

    // Statistics
    suspend fun getTotalMeterCount(): Int {
        return meterDao.getMeterCount()
    }

    suspend fun getCheckedMeterCount(): Int {
        return meterDao.getCheckedMeterCount()
    }

    // Bulk operations
    suspend fun deleteAllData() {
        Log.d(TAG, "Deleting all data")
        meterDao.deleteAllMeters()
        // Note: We keep files as they might be needed for re-processing
    }

    suspend fun syncFileWithMeters(fileName: String, meters: List<MeterStatus>, destination: ProcessingDestination) {
        Log.d(TAG, "Syncing file $fileName with ${meters.size} meters for destination $destination")

        // First, check if file exists and update/insert it
        val existingFile = getFile(fileName)
        val fileItem = if (existingFile != null) {
            existingFile.copy(
                meterCount = meters.size,
                destination = destination
            )
        } else {
            FileItem(
                fileName = fileName,
                uploadDate = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date()),
                meterCount = meters.size,
                isValid = true,
                destination = destination
            )
        }

        if (existingFile != null) {
            updateFile(fileItem)
        } else {
            insertFile(fileItem)
        }

        // Delete existing meters from this file
        meterDao.deleteMetersFromFile(fileName)

        // Insert new meters
        insertMeters(meters)
    }

    // Search operations
    suspend fun searchMeters(query: String): List<MeterStatus> {
        val allMeters = getAllMeters().first()
        return if (query.isBlank()) {
            allMeters
        } else {
            allMeters.filter { meter ->
                meter.serialNumber.contains(query, ignoreCase = true) ||
                        meter.number.contains(query, ignoreCase = true) ||
                        meter.place.contains(query, ignoreCase = true) ||
                        meter.fromFile.contains(query, ignoreCase = true)
            }
        }
    }
}

// Keep your existing PreferencesManager exactly as it is
class PreferencesManager(context: Context) {
    private val prefs = context.getSharedPreferences("meter_app_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_PROCESSED_DESTINATION = "last_processed_destination"
        private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
        private const val KEY_APP_FIRST_RUN = "app_first_run"
        private const val KEY_CAMERA_FLASH_ENABLED = "camera_flash_enabled"
        private const val KEY_SCAN_SOUND_ENABLED = "scan_sound_enabled"
    }

    var lastProcessedDestination: ProcessingDestination?
        get() {
            val destinationName = prefs.getString(KEY_LAST_PROCESSED_DESTINATION, null)
            return destinationName?.let {
                try {
                    ProcessingDestination.valueOf(it)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
        }
        set(value) {
            prefs.edit()
                .putString(KEY_LAST_PROCESSED_DESTINATION, value?.name)
                .apply()
        }

    var isAutoBackupEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BACKUP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_BACKUP_ENABLED, value).apply()

    var lastBackupTime: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_BACKUP_TIME, value).apply()

    var isFirstRun: Boolean
        get() = prefs.getBoolean(KEY_APP_FIRST_RUN, true)
        set(value) = prefs.edit().putBoolean(KEY_APP_FIRST_RUN, value).apply()

    var isCameraFlashEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_FLASH_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_CAMERA_FLASH_ENABLED, value).apply()

    var isScanSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCAN_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SCAN_SOUND_ENABLED, value).apply()

    fun clearAllPreferences() {
        prefs.edit().clear().apply()
    }

    fun resetToDefaults() {
        prefs.edit()
            .putBoolean(KEY_AUTO_BACKUP_ENABLED, true)
            .putBoolean(KEY_CAMERA_FLASH_ENABLED, false)
            .putBoolean(KEY_SCAN_SOUND_ENABLED, true)
            .remove(KEY_LAST_PROCESSED_DESTINATION)
            .apply()
    }
}