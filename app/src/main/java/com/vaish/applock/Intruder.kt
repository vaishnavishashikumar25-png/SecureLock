package com.vaish.applock

data class Intruder(
    val imagePath: String,
    val timestamp: Long,
    val latitude: Double? = null,
    val longitude: Double? = null
)
