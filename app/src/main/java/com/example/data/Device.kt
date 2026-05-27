package com.example.data

import androidx.room.*

@Entity(tableName = "devices")
data class Device(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val ip: String,
    val httpPort: Int,
    val udpPort: Int,
    val lastConnected: Long = System.currentTimeMillis()
)
