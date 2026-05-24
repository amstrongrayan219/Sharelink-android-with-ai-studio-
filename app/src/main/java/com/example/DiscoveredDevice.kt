package com.example

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class DiscoveredDevice(
    val name: String,
    val ip: String,
    val port: Int = 8080,
    @Transient val lastSeen: Long = System.currentTimeMillis()
)
