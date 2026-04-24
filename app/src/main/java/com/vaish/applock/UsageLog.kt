package com.vaish.applock

data class UsageLog(
    val packageName: String,
    val startTime: Long,
    var duration: Long = 0,
    val isOwner: Boolean
)
