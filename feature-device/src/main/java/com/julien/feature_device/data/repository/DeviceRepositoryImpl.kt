package com.julien.feature_device.data.repository

import android.content.Context
import com.arashivision.inskmp.insble.InsBleSdk
import com.arashivision.inskmp.insble.data.BleDeviceCore
import com.arashivision.inskmp.insble.data.DeviceFeatureType
import com.arashivision.inskmp.insble.scan.InsBleScanCallback
import com.arashivision.inskmp.camera.camera.CameraDevice
import com.julien.feature_device.model.DeviceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : DeviceRepository {

    private val _scannedDevices = MutableStateFlow<List<BleDeviceCore>>(emptyList())
    override val scannedDevices: StateFlow<List<BleDeviceCore>> = _scannedDevices.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var currentCamera: CameraDevice? = null
    private val deviceMap = mutableMapOf<String, BleDeviceCore>()

    private val scanCallback = object : InsBleScanCallback {
        override fun onBLEScanResult(device: BleDeviceCore) {
            Timber.d("Found device: ${device.name} - ${device.address}")
            deviceMap[device.address] = device
            _scannedDevices.value = deviceMap.values.toList()
        }

        override fun onBLEScanFinished() {
            Timber.d("Scan finished")
            if (_connectionState.value is ConnectionState.Scanning) {
                _connectionState.value = ConnectionState.Idle
            }
        }

        override fun onBLEScanFailed(errorCode: Int) {
            Timber.e("Scan failed with error: $errorCode")
            _connectionState.value = ConnectionState.Error("扫描失败: $errorCode")
        }
    }

    init {
        InsBleSdk.init(context)
    }

    override suspend fun startScanning() = withContext(Dispatchers.IO) {
        try {
            deviceMap.clear()
            _scannedDevices.value = emptyList()
            _connectionState.value = ConnectionState.Scanning

            InsBleSdk.scanDevice(
                enableFilter = true,
                scanCallback = scanCallback
            )
            Timber.d("Started scanning for devices")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start scan")
            _connectionState.value = ConnectionState.Error("启动扫描失败: ${e.message}")
        }
    }

    override suspend fun stopScanning() = withContext(Dispatchers.IO) {
        try {
            InsBleSdk.stopScan()
            _connectionState.value = ConnectionState.Idle
            Timber.d("Stopped scanning")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop scan")
        }
    }


    override suspend fun connect(device: BleDeviceCore) = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = ConnectionState.Connecting(device.name ?: "Unknown")

            InsBleSdk.stopScan()

            val camera = CameraDevice.create(device)

            camera.connectAsync(
                onSuccess = {
                    Timber.d("Connected to device: ${device.name}")
                    currentCamera = camera

                    val deviceInfo = DeviceInfo(
                        deviceId = device.address,
                        deviceName = device.name ?: "Unknown Device",
                        deviceType = getDeviceType(device),
                        firmwareVersion = camera.firmwareVersion ?: "Unknown",
                        batteryLevel = camera.batteryLevel
                    )

                    _connectionState.value = ConnectionState.Connected(deviceInfo)
                },
                onFailed = { errorCode ->
                    Timber.e("Failed to connect: $errorCode")
                    _connectionState.value = ConnectionState.Error("连接失败: $errorCode")
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "Connection error")
            _connectionState.value = ConnectionState.Error("连接错误: ${e.message}")
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            currentCamera?.close()
            currentCamera = null
            _connectionState.value = ConnectionState.Idle
            Timber.d("Disconnected from device")
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
        return when {
            device.featureTypes.contains(DeviceFeatureType.X4_AIR) -> "Insta360 X4 Air"
            device.featureTypes.contains(DeviceFeatureType.X4) -> "Insta360 X4"
            device.featureTypes.contains(DeviceFeatureType.X3) -> "Insta360 X3"
            else -> "Insta360 Device"
        }
    }
}