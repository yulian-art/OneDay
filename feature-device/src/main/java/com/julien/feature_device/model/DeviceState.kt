package com.julien.feature_device.model

/**
 * 设备连接状态
 */
enum class ConnectionStatus {
    DISCONNECTED,    // 未连接
    CONNECTING,      // 连接中
    CONNECTED,       // 已连接
    ERROR           // 连接错误
}

/**
 * 设备 UI 状态
 */
data class DeviceUiState(
    val connectedDevice: DeviceInfo? = null,
    val availableDevices: List<DeviceInfo> = emptyList(),
    val isScanning: Boolean = false,
    val errorMessage: String? = null
)
