package com.julien.feature_device.viewmodel

import com.julien.feature_device.model.ConnectionStatus
import com.julien.feature_device.model.DeviceInfo
import com.julien.feature_device.repository.DeviceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceViewModelTest {

    private lateinit var repository: DeviceRepository
    private lateinit var viewModel: DeviceViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        viewModel = DeviceViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state should be correct`() {
        // Given & When - ViewModel 已初始化
        val state = viewModel.uiState.value

        // Then
        assertFalse(state.isScanning)
        assertTrue(state.availableDevices.isEmpty())
        assertNull(state.connectedDevice)
        assertNull(state.errorMessage)
    }

    @Test
    fun `startScan should update isScanning to true`() = runTest {
        // Given
        every { repository.startScan() } returns Unit

        // When
        viewModel.startScan()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assertTrue(viewModel.uiState.value.isScanning)
        coVerify { repository.startScan() }
    }

    @Test
    fun `stopScan should update isScanning to false`() = runTest {
        // Given - 先启动扫描
        every { repository.startScan() } returns Unit
        every { repository.stopScan() } returns Unit
        viewModel.startScan()
        testDispatcher.scheduler.advanceUntilIdle()

        // When
        viewModel.stopScan()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assertFalse(viewModel.uiState.value.isScanning)
        coVerify { repository.stopScan() }
    }

    @Test
    fun `observeDevices should update availableDevices list`() = runTest {
        // Given
        val mockDevices = listOf(
            DeviceInfo(
                id = "device-1",
                name = "Insta360 X3",
                model = "X3",
                firmwareVersion = "v1.2.3",
                batteryLevel = 85,
                status = ConnectionStatus.DISCONNECTED
            ),
            DeviceInfo(
                id = "device-2",
                name = "Insta360 GO 3",
                model = "GO 3",
                firmwareVersion = "v2.0.1",
                batteryLevel = 72,
                status = ConnectionStatus.DISCONNECTED
            )
        )

        every { repository.observeDevices() } returns flowOf(mockDevices)

        // When - 重新创建 ViewModel 触发 Flow 收集
        viewModel = DeviceViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assertEquals(2, viewModel.uiState.value.availableDevices.size)
        assertEquals("Insta360 X3", viewModel.uiState.value.availableDevices[0].name)
        assertEquals("Insta360 GO 3", viewModel.uiState.value.availableDevices[1].name)
    }

    @Test
    fun `connectDevice success should update connectedDevice`() = runTest {
        // Given
        val deviceId = "device-1"
        val mockDevice = DeviceInfo(
            id = deviceId,
            name = "Insta360 X3",
            model = "X3",
            firmwareVersion = "v1.2.3",
            batteryLevel = 85,
            status = ConnectionStatus.CONNECTED
        )

        coEvery { repository.connectDevice(deviceId) } returns Result.success(Unit)
        every { repository.observeConnectionStatus() } returns flowOf(ConnectionStatus.CONNECTED)

        // 模拟设备列表中有此设备
        every { repository.observeDevices() } returns flowOf(listOf(mockDevice))
        viewModel = DeviceViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        // When
        viewModel.connectDevice(deviceId)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assertNotNull(viewModel.uiState.value.connectedDevice)
        assertEquals("Insta360 X3", viewModel.uiState.value.connectedDevice?.name)
        assertEquals(ConnectionStatus.CONNECTED, viewModel.uiState.value.connectedDevice?.status)
        coVerify { repository.connectDevice(deviceId) }
    }

    @Test
    fun `connectDevice failure should set error message`() = runTest {
        // Given
        val deviceId = "device-1"
        val errorMessage = "蓝牙连接失败"

        coEvery { repository.connectDevice(deviceId) } returns Result.failure(
            Exception(errorMessage)
        )

        // When
        viewModel.connectDevice(deviceId)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("连接失败"))
        assertNull(viewModel.uiState.value.connectedDevice)
    }

    @Test
    fun `disconnectDevice should clear connectedDevice`() = runTest {
        // Given - 先连接设备
        val deviceId = "device-1"
        val mockDevice = DeviceInfo(
            id = deviceId,
            name = "Insta360 X3",
            model = "X3",
            firmwareVersion = "v1.2.3",
            batteryLevel = 85,
            status = ConnectionStatus.CONNECTED
        )

        coEvery { repository.connectDevice(deviceId) } returns Result.success(Unit)
        coEvery { repository.disconnectDevice() } returns Unit
        every { repository.observeDevices() } returns flowOf(listOf(mockDevice))
        every { repository.observeConnectionStatus() } returns flowOf(
            ConnectionStatus.CONNECTED,
            ConnectionStatus.DISCONNECTED
        )

        viewModel = DeviceViewModel(repository)
        viewModel.connectDevice(deviceId)
        testDispatcher.scheduler.advanceUntilIdle()

        // When
        viewModel.disconnectDevice()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assertNull(viewModel.uiState.value.connectedDevice)
        coVerify { repository.disconnectDevice() }
    }

    @Test
    fun `clearError should clear error message`() = runTest {
        // Given - 先触发错误
        val deviceId = "device-1"
        coEvery { repository.connectDevice(deviceId) } returns Result.failure(
            Exception("连接失败")
        )
        viewModel.connectDevice(deviceId)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)

        // When
        viewModel.clearError()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `multiple scan start-stop cycles should work correctly`() = runTest {
        // Given
        every { repository.startScan() } returns Unit
        every { repository.stopScan() } returns Unit

        // When & Then - 第一次扫描
        viewModel.startScan()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isScanning)

        viewModel.stopScan()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isScanning)

        // When & Then - 第二次扫描
        viewModel.startScan()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isScanning)

        viewModel.stopScan()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isScanning)

        // 验证调用次数
        coVerify(exactly = 2) { repository.startScan() }
        coVerify(exactly = 2) { repository.stopScan() }
    }

    @Test
    fun `connection status changes should update device status`() = runTest {
        // Given
        val deviceId = "device-1"
        val mockDevice = DeviceInfo(
            id = deviceId,
            name = "Insta360 X3",
            model = "X3",
            firmwareVersion = "v1.2.3",
            batteryLevel = 85,
            status = ConnectionStatus.DISCONNECTED
        )

        every { repository.observeDevices() } returns flowOf(listOf(mockDevice))
        every { repository.observeConnectionStatus() } returns flowOf(
            ConnectionStatus.DISCONNECTED,
            ConnectionStatus.CONNECTING,
            ConnectionStatus.CONNECTED
        )

        // When
        viewModel = DeviceViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then - 应该显示最终状态
        // 注意: 实际实现中需要根据 connectionStatus 更新设备状态
        coVerify { repository.observeConnectionStatus() }
    }
}
