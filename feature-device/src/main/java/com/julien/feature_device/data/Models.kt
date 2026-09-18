package com.julien.feature_device.data

import com.arashivision.sdk.discovery.IDeviceConnection

/**
 * 扫描到的设备
 */
data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val connection: IDeviceConnection? = null
)

/**
 * 设备信息
 */
data class DeviceInfo(
    val cameraType: String,
    val serialNumber: String,
    val firmware: String,
    val batteryLevel: Int,
    val storageRemaining: String,
    val storageTotal: String,
    val connectionType: ConnectionType?
)

/**
 * 连接方式
 */
enum class ConnectionType {
    BLUETOOTH,
    WIFI,
    USB
}

/**
 * 连接状态
 */
sealed class ConnectionState {
    object Idle : ConnectionState()
    object Scanning : ConnectionState()
    data class Connecting(val deviceName: String) : ConnectionState()
    data class Connected(val device: DeviceInfo) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
