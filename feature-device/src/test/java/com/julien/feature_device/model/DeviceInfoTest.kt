package com.julien.feature_device.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceInfoTest {

    @Test
    fun `device info retains sdk values`() {
        val device = DeviceInfo(
            deviceId = "camera-001",
            deviceName = "Insta360 X4",
            deviceType = "X4",
            firmwareVersion = "1.0.45",
            batteryLevel = 85
        )

        assertEquals("camera-001", device.deviceId)
        assertEquals("Insta360 X4", device.deviceName)
        assertEquals("X4", device.deviceType)
        assertEquals("1.0.45", device.firmwareVersion)
        assertEquals(85, device.batteryLevel)
    }
}
