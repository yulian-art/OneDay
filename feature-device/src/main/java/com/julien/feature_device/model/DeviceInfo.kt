package com.julien.feature_device.model

data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val firmwareVersion: String,
    val batteryLevel: Int
)
