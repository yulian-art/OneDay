package com.julien.feature_device.data.repository

import com.arashivision.inskmp.insble.data.BleDeviceCore
import com.julien.feature_device.model.DeviceInfo
import kotlinx.coroutines.flow.Flow

sealed class ConnectionState {
    object Idle : ConnectionState()
    object Scanning : ConnectionState()
    data class Connecting(val deviceName: String) : ConnectionState()
    data class Connected(val device: DeviceInfo) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

interface DeviceRepository {
    val scannedDevices: Flow<List<BleDeviceCore>>
    val connectionState: Flow<ConnectionState>

    suspend fun startScanning()
    suspend fun stopScanning()
    suspend fun connect(device: BleDeviceCore)
    suspend fun disconnect()
}
