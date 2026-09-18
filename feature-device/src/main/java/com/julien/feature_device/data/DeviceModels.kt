package com.julien.feature_device.data

import com.arashivision.inskmp.insble.data.BleDeviceCore
import com.arashivision.sdk.camera.core.model.ConnectType

/**
 * 设备信息数据模型
 */
data class DeviceInfo(
    val cameraType: String = "-",
    val serialNumber: String = "-",
    val firmware: String = "-",
    val batteryLevel: Int = 0,
    val isCharging: Boolean = false,
    val storageRemaining: String = "-",
    val storageTotal: String = "-",
    val connectionType: ConnectType? = null,
    val isConnected: Boolean = false
)

/**
 * 连接状态
 */
sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Scanning : ConnectionState()
    data class Connecting(val deviceName: String) : ConnectionState()
    data class Connected(val device: DeviceInfo) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * 扫描到的蓝牙设备
 */
data class ScannedDevice(
    val bleDevice: BleDeviceCore,
    val name: String,
    val address: String,
    val rssi: Int = 0
)

/**
 * 连接方式
 */
enum class ConnectionMethod {
    WIFI,           // WiFi 直连
    BLUETOOTH,      // 纯蓝牙
    WIFI_VIA_BLE,   // 通过蓝牙连接 WiFi
    USB             // USB 连接
}
