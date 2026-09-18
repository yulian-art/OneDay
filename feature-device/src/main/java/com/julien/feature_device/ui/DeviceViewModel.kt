package com.julien.feature_device.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.julien.feature_device.data.ConnectionState
import com.julien.feature_device.data.ScannedDevice
import com.julien.feature_device.domain.DeviceConnectionManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设备连接页面的 ViewModel
 */
class DeviceViewModel(application: Application) : AndroidViewModel(application) {

    private val connectionManager = DeviceConnectionManager(application)

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ConnectionState.Idle
        )

    val scannedDevices: StateFlow<List<ScannedDevice>> = connectionManager.scannedDevices
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * 开始扫描蓝牙设备
     */
    fun startScan() {
        viewModelScope.launch {
            connectionManager.startBleScan()
        }
    }

    /**
     * 停止扫描
     */
    fun stopScan() {
        connectionManager.stopBleScan()
    }

    /**
     * 通过蓝牙连接设备
     */
    fun connectViaBluetooth(device: ScannedDevice) {
        viewModelScope.launch {
            connectionManager.connectViaBluetooth(device)
        }
    }

    /**
     * 通过 WiFi 连接
     */
    fun connectViaWifi() {
        viewModelScope.launch {
            connectionManager.connectViaWifi()
        }
    }

    /**
     * 通过 USB 连接
     */
    fun connectViaUsb() {
        viewModelScope.launch {
            connectionManager.connectViaUsb()
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        connectionManager.disconnect()
    }

    /**
     * 获取当前相机设备（供其他模块使用）
     */
    fun getCurrentCamera() = connectionManager.getCurrentCamera()

    override fun onCleared() {
        super.onCleared()
        connectionManager.cleanup()
    }
}
