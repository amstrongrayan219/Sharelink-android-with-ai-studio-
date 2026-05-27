package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY lastConnected DESC")
    fun getAllDevices(): Flow<List<Device>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: Device): Long

    @Query("DELETE FROM devices WHERE ip = :ip")
    suspend fun deleteDeviceByIp(ip: String)

    @Query("DELETE FROM devices")
    suspend fun clearHistory()
}
