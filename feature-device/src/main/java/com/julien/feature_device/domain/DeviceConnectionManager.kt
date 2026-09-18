package com.julien.feature_device.domain

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import com.arashivision.sdk.camera.InstaCameraManager
import com.arashivision.sdk.camera.callback.IDiscoveryBleCallBack
import com.arashivision.sdk.camera.callback.IDiscoveryUsbCallBack
import com.arashivision.sdk.camera.callback.IDiscoveryWiFiCallBack
import com.arashivision.sdk.camera.scan.DeviceType
import com.arashivision.sdk.discovery.IDeviceConnection
import com.julien.feature_device.data.ConnectionState
import com.julien.feature_device.data.ConnectionType
import com.julien.feature_device.data.DeviceInfo
import com.julien.feature_device.data.ScannedDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 设备连接管理器
 * 负责蓝牙、WiFi、USB 设备的扫描和连接
 */
class DeviceConnectionManager(private val context: Application) {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private var currentCamera: com.arashivision.sdk.camera.ICamera? = null
    private val deviceMap = mutableMapOf<String, ScannedDevice>()

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }

    /**
     * 开始蓝牙扫描
     */
    suspend fun startBleScan() {
        try {
            _connectionState.value = ConnectionState.Scanning
            deviceMap.clear()
            _scannedDevices.value = emptyList()

            Timber.d("Starting BLE scan...")

            // 使用 Insta360 SDK 的蓝牙扫描
            InstaCameraManager.getInstance().openDiscoveryBle(bleCallback)

        } catch (e: Exception) {
            Timber.e(e, "Failed to start BLE scan")
            _connectionState.value = ConnectionState.Error("蓝牙扫描失败: ${e.message}")
        }
    }

    /**
     * 停止蓝牙扫描
     */
    fun stopBleScan() {
        try {
            InstaCameraManager.getInstance().closeDiscoveryBle()
            _connectionState.value = ConnectionState.Idle
            Timber.d("BLE scan stopped")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop BLE scan")
        }
    }

    /**
     * 通过蓝牙连接设备
     */
    suspend fun connectViaBluetooth(device: ScannedDevice) {
        try {
            _connectionState.value = ConnectionState.Connecting(device.name)
            stopBleScan()

            Timber.d("Connecting to device: ${device.name} (${device.address})")

            // 使用 SDK 连接设备
            val cameraConnection = deviceMap[device.address]?.connection
            if (cameraConnection != null) {
                InstaCameraManager.getInstance().connectBluetooth(cameraConnection)
            } else {
                throw IllegalStateException("Device connection not found")
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to connect via Bluetooth")
            _connectionState.value = ConnectionState.Error("蓝牙连接失败: ${e.message}")
        }
    }

    /**
     * 通过 WiFi 连接
     */
    suspend fun connectViaWifi() {
        try {
            _connectionState.value = ConnectionState.Connecting("WiFi Device")

            Timber.d("Starting WiFi discovery...")
            InstaCameraManager.getInstance().openDiscoveryWiFi(wifiCallback)

        } catch (e: Exception) {
            Timber.e(e, "Failed to connect via WiFi")
            _connectionState.value = ConnectionState.Error("WiFi 连接失败: ${e.message}")
        }
    }

    /**
     * 通过 USB 连接
     */
    suspend fun connectViaUsb() {
        try {
            _connectionState.value = ConnectionState.Connecting("USB Device")

            Timber.d("Starting USB discovery...")
            InstaCameraManager.getInstance().openDiscoveryUsb(context, usbCallback)

        } catch (e: Exception) {
            Timber.e(e, "Failed to connect via USB")
            _connectionState.value = ConnectionState.Error("USB 连接失败: ${e.message}")
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        try {
            currentCamera?.let {
                InstaCameraManager.getInstance().closeCamera()
                currentCamera = null
            }
            _connectionState.value = ConnectionState.Idle
            Timber.d("Disconnected from camera")
        } catch (e: Exception) {
            Timber.e(e, "Failed to disconnect")
        }
    }

    /**
     * 获取当前相机实例
     */
    fun getCurrentCamera() = currentCamera

    /**
     * 清理资源
     */
    fun cleanup() {
        stopBleScan()
        disconnect()
        InstaCameraManager.getInstance().closeDiscoveryWiFi()
    }

    // ========== Callbacks ==========

    private val bleCallback = object : IDiscoveryBleCallBack {
        override fun onScanBegin() {
            Timber.d("BLE scan began")
        }

        override fun onCameraFound(cameraConnection: IDeviceConnection) {
            val deviceName = cameraConnection.name ?: "Unknown"
            val deviceAddress = cameraConnection.address ?: ""

            Timber.d("Found BLE device: $deviceName ($deviceAddress)")

            val scannedDevice = ScannedDevice(
                name = deviceName,
                address = deviceAddress,
                rssi = -60, // SDK doesn't provide RSSI
                connection = cameraConnection
            )

            deviceMap[deviceAddress] = scannedDevice
            _scannedDevices.value = deviceMap.values.toList()
        }

        override fun onScanFinish() {
            Timber.d("BLE scan finished")
        }

        override fun onScanError() {
            Timber.e("BLE scan error")
            _connectionState.value = ConnectionState.Error("蓝牙扫描出错")
        }
    }

    private val wifiCallback = object : IDiscoveryWiFiCallBack {
        override fun onDiscoveryBegin() {
            Timber.d("WiFi discovery began")
        }

        override fun onCameraFound(cameraConnection: IDeviceConnection) {
            Timber.d("Found WiFi camera: ${cameraConnection.name}")

            // 直接连接 WiFi 设备
            val camera = InstaCameraManager.getInstance().createCamera(cameraConnection)
            if (camera != null) {
                onCameraConnected(camera, ConnectionType.WIFI)
            } else {
                _connectionState.value = ConnectionState.Error("无法创建相机实例")
            }
        }

        override fun onDiscoveryFinish() {
            Timber.d("WiFi discovery finished")
        }

        override fun onDiscoveryError() {
            Timber.e("WiFi discovery error")
            _connectionState.value = ConnectionState.Error("WiFi 发现失败")
        }
    }

    private val usbCallback = object : IDiscoveryUsbCallBack {
        override fun onDiscoveryBegin() {
            Timber.d("USB discovery began")
        }

        override fun onCameraFound(cameraConnection: IDeviceConnection) {
            Timber.d("Found USB camera: ${cameraConnection.name}")

            val camera = InstaCameraManager.getInstance().createCamera(cameraConnection)
            if (camera != null) {
                onCameraConnected(camera, ConnectionType.USB)
            } else {
                _connectionState.value = ConnectionState.Error("无法创建相机实例")
            }
        }

        override fun onDiscoveryFinish() {
            Timber.d("USB discovery finished")
        }

        override fun onDiscoveryError() {
            Timber.e("USB discovery error")
            _connectionState.value = ConnectionState.Error("USB 发现失败")
        }
    }

    /**
     * 相机连接成功的通用处理
     */
    private fun onCameraConnected(camera: com.arashivision.sdk.camera.ICamera, type: ConnectionType) {
        currentCamera = camera

        val deviceInfo = DeviceInfo(
            cameraType = camera.type ?: "Unknown",
            serialNumber = camera.serialNumber ?: "-",
            firmware = camera.firmwareVersion ?: "-",
            batteryLevel = camera.batteryPower,
            storageRemaining = formatStorage(camera.freeSpace),
            storageTotal = formatStorage(camera.totalSpace),
            connectionType = type
        )

        _connectionState.value = ConnectionState.Connected(deviceInfo)
        Timber.d("Camera connected: ${deviceInfo.cameraType}")
    }

    private fun formatStorage(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
            else -> String.format("%.2fGB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
