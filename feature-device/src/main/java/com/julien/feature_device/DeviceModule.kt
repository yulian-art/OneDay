package com.julien.feature_device

import android.app.Application
import androidx.compose.runtime.Composable
import com.arashivision.sdk.camera.InstaCameraSDK
import com.arashivision.sdk.common.log.LogLevel
import com.arashivision.sdk.media.InstaMediaSDK
import com.julien.feature_device.ui.DeviceScreen

/**
 * 设备模块对外导出的入口点
 */
object DeviceModule {

    fun initialize(application: Application, debug: Boolean): Result<Unit> = runCatching {
        val sdkLogLevel = if (debug) LogLevel.DEBUG else LogLevel.ERROR
        val sdkCacheDir = application.externalCacheDir?.absolutePath
            ?: application.cacheDir.absolutePath
        val sdkFileDir = application.filesDir.absolutePath

        InstaCameraSDK.init(application) {
            cacheDir = sdkCacheDir
            fileDir = sdkFileDir
            logLevel = sdkLogLevel
        }
        InstaMediaSDK.init(application) {
            cacheDir = sdkCacheDir
            fileDir = sdkFileDir
            logLevel = sdkLogLevel
        }
    }

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
