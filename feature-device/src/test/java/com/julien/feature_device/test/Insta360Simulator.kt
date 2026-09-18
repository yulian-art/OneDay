package com.julien.feature_device.test

import com.julien.feature_device.model.ConnectionStatus
import com.julien.feature_device.model.DeviceInfo
import com.julien.feature_device.repository.DeviceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Insta360 设备模拟器
 * 用于测试环境，提供确定性的设备行为
 *
 * 使用场景：
 * - 单元测试和集成测试
 * - CI/CD 环境（无真实设备）
 * - 开发阶段的快速迭代
 */
@Singleton
class Insta360Simulator @Inject constructor() : DeviceRepository {

    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    private var connectedDeviceId: String? = null

    // 配置选项
    var scanDelayMs: Long = 1000  // 扫描延迟
    var connectDelayMs: Long = 500  // 连接延迟
    var shouldFailConnection: Boolean = false  // 模拟连接失败
    var simulatedBatteryLevel: Int = 85  // 模拟电量

    override fun startScan() {
        // 模拟扫描过程（异步）
        kotlinx.coroutines.GlobalScope.launch {
            delay(scanDelayMs)
            _devices.value = generateMockDevices()
        }
    }

    override fun stopScan() {
        // 停止扫描，清空设备列表（可选）
        // 实际实现中可能保留已扫描的设备
    }

    override suspend fun connectDevice(deviceId: String): Result<Unit> {
        return try {
            _connectionStatus.value = ConnectionStatus.CONNECTING
            delay(connectDelayMs)

            if (shouldFailConnection) {
                _connectionStatus.value = ConnectionStatus.ERROR
                throw Exception("模拟连接失败")
            }

            connectedDeviceId = deviceId
            _connectionStatus.value = ConnectionStatus.CONNECTED

            // 更新设备状态
            _devices.value = _devices.value.map { device ->
                if (device.id == deviceId) {
                    device.copy(status = ConnectionStatus.CONNECTED)
                } else {
                    device
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            _connectionStatus.value = ConnectionStatus.ERROR
            Result.failure(e)
        }
    }

    override suspend fun disconnectDevice() {
        val deviceId = connectedDeviceId
        connectedDeviceId = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED

        // 更新设备状态
        if (deviceId != null) {
            _devices.value = _devices.value.map { device ->
                if (device.id == deviceId) {
                    device.copy(status = ConnectionStatus.DISCONNECTED)
                } else {
                    device
                }
            }
        }
    }

    override fun observeDevices(): Flow<List<DeviceInfo>> = _devices.asStateFlow()

    override fun observeConnectionStatus(): Flow<ConnectionStatus> = _connectionStatus.asStateFlow()

    // === 测试辅助方法 ===

    /**
     * 生成模拟设备列表
     */
    private fun generateMockDevices(): List<DeviceInfo> {
        return listOf(
            DeviceInfo(
                id = "sim-x3-001",
                name = "Insta360 X3 (Simulator)",
                model = "X3",
                firmwareVersion = "v1.2.3",
                batteryLevel = simulatedBatteryLevel,
                status = ConnectionStatus.DISCONNECTED
            ),
            DeviceInfo(
                id = "sim-go3-002",
                name = "Insta360 GO 3 (Simulator)",
                model = "GO 3",
                firmwareVersion = "v2.0.1",
                batteryLevel = 72,
                status = ConnectionStatus.DISCONNECTED
            ),
            DeviceInfo(
                id = "sim-one-x2-003",
                name = "Insta360 ONE X2 (Simulator)",
                model = "ONE X2",
                firmwareVersion = "v1.5.0",
                batteryLevel = 60,
                status = ConnectionStatus.DISCONNECTED
            )
        )
    }

    /**
     * 模拟电量变化
     * @param newLevel 新电量 (0-100)
     */
    fun simulateBatteryChange(deviceId: String, newLevel: Int) {
        require(newLevel in 0..100) { "Battery level must be between 0 and 100" }

        _devices.value = _devices.value.map { device ->
            if (device.id == deviceId) {
                device.copy(batteryLevel = newLevel)
            } else {
                device
            }
        }
    }

    /**
     * 模拟设备断开连接（例如蓝牙断开）
     */
    suspend fun simulateDisconnection() {
        if (connectedDeviceId != null) {
            _connectionStatus.value = ConnectionStatus.ERROR
            delay(100)
            disconnectDevice()
        }
    }

    /**
     * 模拟固件升级
     */
    fun simulateFirmwareUpdate(deviceId: String, newVersion: String) {
        _devices.value = _devices.value.map { device ->
            if (device.id == deviceId) {
                device.copy(firmwareVersion = newVersion)
            } else {
                device
            }
        }
    }

    /**
     * 重置模拟器状态
     */
    fun reset() {
        _devices.value = emptyList()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        connectedDeviceId = null
        shouldFailConnection = false
        simulatedBatteryLevel = 85
    }

    /**
     * 添加自定义设备
     */
    fun addMockDevice(device: DeviceInfo) {
        _devices.value = _devices.value + device
    }

    /**
     * 移除设备
     */
    fun removeMockDevice(deviceId: String) {
        _devices.value = _devices.value.filter { it.id != deviceId }
    }

    /**
     * 获取当前连接的设备
     */
    fun getConnectedDevice(): DeviceInfo? {
        return _devices.value.find { it.id == connectedDeviceId }
    }
}

// === Hilt 测试模块配置 ===

/**
 * 在测试中使用 Simulator 替换真实 Repository
 */
@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
object TestDeviceModule {

    @dagger.Provides
    @Singleton
    fun provideDeviceRepository(
        simulator: Insta360Simulator
    ): DeviceRepository {
        return simulator
    }
}
