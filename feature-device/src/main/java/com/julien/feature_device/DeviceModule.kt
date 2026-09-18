package com.julien.feature_device

import androidx.compose.runtime.Composable
import com.julien.feature_device.ui.DeviceScreen
import com.julien.feature_device.viewmodel.DeviceViewModel

/**
 * 设备模块对外导出的入口点
 */
object DeviceModule {

    /**
     * 获取设备管理屏幕的 Composable
     *
     * @param onNavigateBack 返回导航回调
     * @return 设备管理界面
     */
    @Composable
    fun getDeviceScreen(
        onNavigateBack: (() -> Unit)? = null
    ) {
        DeviceScreen(onNavigateBack = onNavigateBack)
    }
}
