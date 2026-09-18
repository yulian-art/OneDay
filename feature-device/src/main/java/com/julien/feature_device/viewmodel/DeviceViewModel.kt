package com.julien.feature_device.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.julien.feature_device.model.ConnectionStatus
import com.julien.feature_device.model.DeviceInfo
import com.julien.feature_device.model.DeviceUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 设备管理 ViewModel
 * 负责设备扫描、连接和状态管理
 */
class DeviceViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceUiState())
    val uiState: StateFlow<DeviceUiState> = _uiState.asStateFlow()

    /**
     * 开始扫描设备
     */
    fun startScan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, errorMessage = null) }

            try {
                // 模拟扫描过程（稍后集成 Insta360 SDK）
                delay(2000)

                val mockDevices = listOf(
                    DeviceInfo(
                        deviceId = "INS_X4_001",
                        deviceName = "Insta360 X4",
                        deviceType = "X4",
                        firmwareVersion = "v1.0.45",
                        batteryLevel = 85
                    ),
                    DeviceInfo(
                        deviceId = "INS_GO3_002",
                        deviceName = "Insta360 GO 3",
                        deviceType = "GO3",
                        firmwareVersion = "v1.2.10",
                        batteryLevel = 60
                    )
                )

                _uiState.update {
                    it.copy(
                        availableDevices = mockDevices,
                        isScanning = false
                    )
                }

                Timber.d("Found ${mockDevices.size} devices")

            } catch (e: Exception) {
                Timber.e(e, "Scan failed")
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        errorMessage = "扫描失败: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * 停止扫描
     */
    fun stopScan() {
        _uiState.update { it.copy(isScanning = false) }
        Timber.d("Scan stopped")
    }

    /**
     * 连接设备
     */
    fun connectDevice(deviceInfo: DeviceInfo) {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }

            try {
                // 更新连接状态
                val connectingDevice = deviceInfo.copy(status = ConnectionStatus.CONNECTING)
                _uiState.update { it.copy(connectedDevice = connectingDevice) }

                // 模拟连接过程
                delay(1500)

                val connectedDevice = deviceInfo.copy(status = ConnectionStatus.CONNECTED)
                _uiState.update {
                    it.copy(
                        connectedDevice = connectedDevice,
                        availableDevices = emptyList()
                    )
                }

                Timber.d("Connected to device: ${deviceInfo.deviceName}")

            } catch (e: Exception) {
                Timber.e(e, "Connection failed")
                val errorDevice = deviceInfo.copy(status = ConnectionStatus.ERROR)
                _uiState.update {
                    it.copy(
                        connectedDevice = errorDevice,
                        errorMessage = "连接失败: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * 断开设备连接
     */
    fun disconnectDevice() {
        viewModelScope.launch {
            _uiState.value.connectedDevice?.let { device ->
                try {
                    Timber.d("Disconnecting from: ${device.deviceName}")
                    // 模拟断开过程
                    delay(500)

                    _uiState.update {
                        it.copy(
                            connectedDevice = null,
                            errorMessage = null
                        )
                    }

                    Timber.d("Disconnected successfully")
                } catch (e: Exception) {
                    Timber.e(e, "Disconnect failed")
                    _uiState.update {
                        it.copy(errorMessage = "断开连接失败: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}