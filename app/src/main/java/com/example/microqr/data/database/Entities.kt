package com.example.microqr.data.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// Entity for FileItem
@Entity(tableName = "files")
data class FileEntity(
    @PrimaryKey val fileName: String,
    val uploadDate: String,
    val meterCount: Int,
    val isValid: Boolean,
    val validationError: String = "",
    val destination: String? = null // Store as string, convert to/from ProcessingDestination
)

// Entity for MeterStatus
@Entity(
    tableName = "meters",
    primaryKeys = ["serialNumber", "fromFile"] // Composite key to handle duplicate serials from different files
)
data class MeterEntity(
    val serialNumber: String,
    val number: String,
    val place: String,
    val registered: Boolean,
    val fromFile: String,
    val isChecked: Boolean = false,
    val isSelectedForProcessing: Boolean = false,
    val lastModified: Long = System.currentTimeMillis()
)

// Entity for Location
@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)

// DAO for FileEntity
@Dao
interface FileDao {
    @Query("SELECT * FROM files ORDER BY uploadDate DESC")
    fun getAllFiles(): Flow<List<FileEntity>>

    @Query("SELECT * FROM files WHERE fileName = :fileName")
    suspend fun getFile(fileName: String): FileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: FileEntity)

    @Update
    suspend fun updateFile(file: FileEntity)

    @Delete
    suspend fun deleteFile(file: FileEntity)

    @Query("DELETE FROM files WHERE fileName = :fileName")
    suspend fun deleteFileByName(fileName: String)

    @Query("SELECT * FROM files WHERE destination = :destination")
    fun getFilesByDestination(destination: String): Flow<List<FileEntity>>
}

// DAO for MeterEntity
@Dao
interface MeterDao {
    @Query("SELECT * FROM meters ORDER BY fromFile, number")
    fun getAllMeters(): Flow<List<MeterEntity>>

    @Query("SELECT * FROM meters WHERE fromFile = :fileName ORDER BY number")
    fun getMetersByFile(fileName: String): Flow<List<MeterEntity>>

    @Query("SELECT * FROM meters WHERE serialNumber = :serialNumber")
    suspend fun getMetersBySerial(serialNumber: String): List<MeterEntity>

    @Query("SELECT * FROM meters WHERE serialNumber = :serialNumber AND fromFile = :fromFile")
    suspend fun getMeter(serialNumber: String, fromFile: String): MeterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeter(meter: MeterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeters(meters: List<MeterEntity>)

    @Update
    suspend fun updateMeter(meter: MeterEntity)

    @Query("UPDATE meters SET isChecked = :isChecked, lastModified = :timestamp WHERE serialNumber = :serialNumber AND fromFile = :fromFile")
    suspend fun updateMeterCheckedStatus(serialNumber: String, fromFile: String, isChecked: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE meters SET isSelectedForProcessing = :isSelected, lastModified = :timestamp WHERE serialNumber = :serialNumber AND fromFile = :fromFile")
    suspend fun updateMeterSelection(serialNumber: String, fromFile: String, isSelected: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM meters WHERE fromFile = :fileName")
    suspend fun deleteMetersFromFile(fileName: String)

    @Query("DELETE FROM meters")
    suspend fun deleteAllMeters()

    @Query("DELETE FROM meters WHERE serialNumber = :serialNumber AND fromFile = :fromFile")
    suspend fun deleteMeter(serialNumber: String, fromFile: String)

    // Get meters by destination (via file destination)
    @Query("""
        SELECT m.* FROM meters m 
        INNER JOIN files f ON m.fromFile = f.fileName 
        WHERE f.destination = :destination 
        ORDER BY m.number
    """)
    fun getMetersByDestination(destination: String): Flow<List<MeterEntity>>

    @Query("SELECT COUNT(*) FROM meters")
    suspend fun getMeterCount(): Int

    @Query("SELECT COUNT(*) FROM meters WHERE isChecked = 1")
    suspend fun getCheckedMeterCount(): Int
}

// DAO for LocationEntity
@Dao
interface LocationDao {
    @Query("SELECT * FROM locations WHERE isActive = 1 ORDER BY name ASC")
    fun getAllActiveLocations(): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations ORDER BY name ASC")
    fun getAllLocations(): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations WHERE name = :name")
    suspend fun getLocationByName(name: String): LocationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: LocationEntity)

    @Update
    suspend fun updateLocation(location: LocationEntity)

    @Delete
    suspend fun deleteLocation(location: LocationEntity)

    @Query("DELETE FROM locations WHERE name = :name")
    suspend fun deleteLocationByName(name: String)

    @Query("UPDATE locations SET isActive = 0 WHERE name = :name")
    suspend fun deactivateLocation(name: String)

    @Query("UPDATE locations SET name = :newName, updatedAt = :updatedAt WHERE name = :oldName")
    suspend fun updateLocationName(oldName: String, newName: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM locations WHERE isActive = 1")
    suspend fun getActiveLocationCount(): Int

    @Query("SELECT name FROM locations WHERE isActive = 1 ORDER BY name ASC")
    suspend fun getActiveLocationNames(): List<String>
}

// Main Database
@Database(
    entities = [FileEntity::class, MeterEntity::class, LocationEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MeterDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun meterDao(): MeterDao
    abstract fun locationDao(): LocationDao

    companion object {
        @Volatile
        private var INSTANCE: MeterDatabase? = null

        fun getDatabase(context: Context): MeterDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MeterDatabase::class.java,
                    "meter_database"
                )
                    .fallbackToDestructiveMigration() // For development - remove in production
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// Type Converters
class Converters {
    @TypeConverter
    fun fromProcessingDestination(destination: com.example.microqr.ui.files.ProcessingDestination?): String? {
        return destination?.name
    }

    @TypeConverter
    fun toProcessingDestination(destinationString: String?): com.example.microqr.ui.files.ProcessingDestination? {
        return destinationString?.let {
            com.example.microqr.ui.files.ProcessingDestination.valueOf(it)
        }
    }
}

// Extension functions to convert between entities and domain models
fun FileEntity.toFileItem(): com.example.microqr.ui.files.FileItem {
    return com.example.microqr.ui.files.FileItem(
        fileName = fileName,
        uploadDate = uploadDate,
        meterCount = meterCount,
        isValid = isValid,
        validationError = validationError,
        destination = destination?.let { com.example.microqr.ui.files.ProcessingDestination.valueOf(it) }
    )
}

fun com.example.microqr.ui.files.FileItem.toEntity(): FileEntity {
    return FileEntity(
        fileName = fileName,
        uploadDate = uploadDate,
        meterCount = meterCount,
        isValid = isValid,
        validationError = validationError,
        destination = destination?.name
    )
}

fun MeterEntity.toMeterStatus(): com.example.microqr.ui.files.MeterStatus {
    return com.example.microqr.ui.files.MeterStatus(
        number = number,
        serialNumber = serialNumber,
        place = place,
        registered = registered,
        fromFile = fromFile,
        isChecked = isChecked,
        isSelectedForProcessing = isSelectedForProcessing
    )
}

fun com.example.microqr.ui.files.MeterStatus.toEntity(): MeterEntity {
    return MeterEntity(
        serialNumber = serialNumber,
        number = number,
        place = place,
        registered = registered,
        fromFile = fromFile,
        isChecked = isChecked,
        isSelectedForProcessing = isSelectedForProcessing
    )
}