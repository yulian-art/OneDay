package com.julien.feature_device.data.repository

import com.arashivision.inskmp.insble.data.BleDeviceCore
import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.core.callback.BleScanCallback
import com.arashivision.sdk.camera.core.model.CameraType
import com.arashivision.sdk.camera.core.model.ConnectType
import com.julien.feature_device.model.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

class DeviceRepositoryImpl : DeviceRepository {

    private val _scannedDevices = MutableStateFlow<List<BleDeviceCore>>(emptyList())
    override val scannedDevices: StateFlow<List<BleDeviceCore>> = _scannedDevices.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val camera = CameraDevice.get(ConnectType.BLE)
    private val deviceMap = mutableMapOf<String, BleDeviceCore>()

    private val scanCallback = object : BleScanCallback {
        override fun onStarted() {
            Timber.d("BLE scan started")
        }

        override fun onScanning(bleDevice: BleDeviceCore) {
            Timber.d("Found device: ${bleDevice.name} - ${bleDevice.address}")
            deviceMap[bleDevice.address] = bleDevice
            _scannedDevices.value = deviceMap.values.toList()
        }

        override fun onFinished(bleDeviceList: List<BleDeviceCore>) {
            Timber.d("BLE scan finished with ${bleDeviceList.size} devices")
            bleDeviceList.forEach { deviceMap[it.address] = it }
            _scannedDevices.value = deviceMap.values.toList()
            if (_connectionState.value is ConnectionState.Scanning) {
                _connectionState.value = ConnectionState.Idle
            }
        }

        override fun onError(throwable: Throwable) {
            Timber.e(throwable, "BLE scan failed")
            _connectionState.value = ConnectionState.Error(
                "扫描失败: ${throwable.message ?: throwable.javaClass.simpleName}"
            )
        }
    }

    override suspend fun startScanning() = withContext(Dispatchers.IO) {
        try {
            deviceMap.clear()
            _scannedDevices.value = emptyList()
            _connectionState.value = ConnectionState.Scanning

            camera.scan(SCAN_TIMEOUT_MS, scanCallback)
            Timber.d("Started scanning for devices")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start scan")
            _connectionState.value = ConnectionState.Error("启动扫描失败: ${e.message}")
        }
    }

    override suspend fun stopScanning() = withContext(Dispatchers.IO) {
        try {
            camera.stopScan()
            _connectionState.value = ConnectionState.Idle
            Timber.d("Stopped scanning")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop scan")
        }
    }


    override suspend fun connect(device: BleDeviceCore) = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = ConnectionState.Connecting(device.name ?: "Unknown")

            camera.stopScan()

            val connectionResult = camera.connectBle(device, false)
            connectionResult.fold(
                onSuccess = {
                    Timber.d("Connected to device: ${device.name}")

                    val firmwareVersion = camera.system
                        .fetchFirmwareRevision()
                        .getOrNull()
                        ?: "Unknown"
                    val batteryLevel = camera.system
                        .fetchBatteryData()
                        .getOrNull()
                        ?.let { battery ->
                            if (battery.scale > 0) {
                                battery.level * 100 / battery.scale
                            } else {
                                battery.level
                            }
                        }
                        ?: -1
                    val cameraType = camera.system
                        .fetchCameraType()
                        .getOrNull()

                    val deviceInfo = DeviceInfo(
                        deviceId = device.address,
                        deviceName = device.name ?: "Unknown Device",
                        deviceType = cameraType?.displayName ?: getDeviceType(device),
                        firmwareVersion = firmwareVersion,
                        batteryLevel = batteryLevel
                    )

                    _connectionState.value = ConnectionState.Connected(deviceInfo)
                },
                onFailure = { error ->
                    Timber.e(error, "Failed to connect")
                    _connectionState.value = ConnectionState.Error(
                        "连接失败: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "Connection error")
            _connectionState.value = ConnectionState.Error("连接错误: ${e.message}")
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            camera.disconnect().fold(
                onSuccess = {
                    _connectionState.value = ConnectionState.Idle
                    Timber.d("Disconnected from device")
                },
                onFailure = { error -> throw error }
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to disconnect")
            _connectionState.value = ConnectionState.Error("断开连接失败: ${e.message}")
        }
    }

    override fun getCurrentDevice(): DeviceInfo? {
        return when (val state = _connectionState.value) {
            is ConnectionState.Connected -> state.device
            else -> null
        }
    }

    private fun getDeviceType(device: BleDeviceCore): String {
        val scanType = device.bleScanProto?.deviceType
        return CameraType.entries
            .firstOrNull { scanType != null && it.bleScanProtoValue == scanType }
            ?.displayName
            ?: device.name
            ?: "Insta360 Device"
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 10_000L
    }
}
