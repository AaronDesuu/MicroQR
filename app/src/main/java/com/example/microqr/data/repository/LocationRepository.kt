package com.example.microqr.data.repository

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.example.microqr.data.database.MeterDatabase
import com.example.microqr.data.database.LocationDao
import com.example.microqr.data.database.LocationEntity

class LocationRepository(context: Context) {

    private val locationDao: LocationDao = MeterDatabase.getDatabase(context).locationDao()

    // Get all active locations as a flow of strings
    fun getAllActiveLocations(): Flow<List<String>> {
        return locationDao.getAllActiveLocations().map { entities ->
            entities.map { it.name }
        }
    }

    // Get all locations (including inactive) as a flow of entities
    fun getAllLocationEntities(): Flow<List<LocationEntity>> {
        return locationDao.getAllLocations()
    }

    // Add a new location
    suspend fun addLocation(name: String): Result<Unit> {
        return try {
            val trimmedName = name.trim()
            if (trimmedName.isEmpty()) {
                return Result.failure(IllegalArgumentException("Location name cannot be empty"))
            }

            // Check if location already exists
            val existing = locationDao.getLocationByName(trimmedName)
            if (existing != null) {
                if (existing.isActive) {
                    return Result.failure(IllegalArgumentException("Location already exists"))
                } else {
                    // Reactivate the location
                    val reactivated = existing.copy(
                        isActive = true,
                        updatedAt = System.currentTimeMillis()
                    )
                    locationDao.updateLocation(reactivated)
                    return Result.success(Unit)
                }
            }

            // Create new location
            val newLocation = LocationEntity(
                name = trimmedName,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                isActive = true
            )

            locationDao.insertLocation(newLocation)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Update location name
    suspend fun updateLocation(oldName: String, newName: String): Result<Unit> {
        return try {
            val trimmedNewName = newName.trim()
            if (trimmedNewName.isEmpty()) {
                return Result.failure(IllegalArgumentException("Location name cannot be empty"))
            }

            if (oldName == trimmedNewName) {
                return Result.success(Unit) // No change needed
            }

            // Check if new name already exists
            val existing = locationDao.getLocationByName(trimmedNewName)
            if (existing != null && existing.isActive) {
                return Result.failure(IllegalArgumentException("Location already exists"))
            }

            // Update the location name
            locationDao.updateLocationName(oldName, trimmedNewName)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Delete location (soft delete by deactivating)
    suspend fun deleteLocation(name: String): Result<Unit> {
        return try {
            locationDao.deactivateLocation(name)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Permanently delete location (use with caution)
    suspend fun permanentlyDeleteLocation(name: String): Result<Unit> {
        return try {
            locationDao.deleteLocationByName(name)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Get location by name
    suspend fun getLocationByName(name: String): LocationEntity? {
        return try {
            locationDao.getLocationByName(name)
        } catch (e: Exception) {
            null
        }
    }

    // Get count of active locations
    suspend fun getActiveLocationCount(): Int {
        return try {
            locationDao.getActiveLocationCount()
        } catch (e: Exception) {
            0
        }
    }

    // Get all active location names (for spinners, etc.)
    suspend fun getActiveLocationNames(): List<String> {
        return try {
            locationDao.getActiveLocationNames()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Check if location exists and is active
    suspend fun isLocationActive(name: String): Boolean {
        return try {
            val location = locationDao.getLocationByName(name)
            location?.isActive == true
        } catch (e: Exception) {
            false
        }
    }
}