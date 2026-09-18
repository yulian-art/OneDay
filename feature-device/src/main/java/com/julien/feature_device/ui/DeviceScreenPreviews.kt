package com.julien.feature_device.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.julien.feature_device.model.ConnectionStatus
import com.julien.feature_device.model.DeviceInfo

/**
 * 设备列表项预览
 */
@Preview(name = "Device List Item - Light", showBackground = true)
@Composable
private fun PreviewDeviceListItem() {
    MaterialTheme {
        Surface {
            DeviceListItem(
                device = DeviceInfo(
                    deviceId = "INS_X4_001",
                    deviceName = "Insta360 X4",
                    deviceType = "X4",
                    firmwareVersion = "v1.0.45",
                    batteryLevel = 85
                ),
                onConnect = {}
            )
        }
    }
}

/**
 * 已连接设备卡片预览
 */
@Preview(name = "Connected Device Card", showBackground = true)
@Composable
private fun PreviewConnectedDeviceCard() {
    MaterialTheme {
        Surface {
            ConnectedDeviceCard(
                device = DeviceInfo(
                    deviceId = "INS_X4_001",
                    deviceName = "Insta360 X4",
                    deviceType = "X4",
                    firmwareVersion = "v1.0.45",
                    batteryLevel = 85,
                    status = ConnectionStatus.CONNECTED
                ),
                onDisconnect = {}
            )
        }
    }
}

/**
 * 扫描指示器预览
 */
@Preview(name = "Scanning Indicator", showBackground = true)
@Composable
private fun PreviewScanningIndicator() {
    MaterialTheme {
        Surface {
            ScanningIndicator()
        }
    }
}

/**
 * 空设备列表预览
 */
@Preview(name = "Empty Device List", showBackground = true)
@Composable
private fun PreviewEmptyDeviceList() {
    MaterialTheme {
        Surface {
            EmptyDeviceList()
        }
    }
}

/**
 * 错误横幅预览
 */
@Preview(name = "Error Banner", showBackground = true)
@Composable
private fun PreviewErrorBanner() {
    MaterialTheme {
        Surface {
            ErrorBanner(
                message = "连接失败: 设备未响应",
                onDismiss = {}
            )
        }
    }
}
