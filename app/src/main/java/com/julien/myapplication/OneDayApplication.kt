package com.julien.myapplication

import android.app.Application
import com.julien.feature_device.DeviceModule
import com.julien.feature_recording.RecordingModule
import timber.log.Timber

class OneDayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        DeviceModule.initialize(this, BuildConfig.DEBUG)
            .onFailure { error -> Timber.e(error, "Failed to initialize device SDK") }

        // 必须在 DeviceModule 之后：它内部会完成 InstaCameraSDK.init，
        // 而录制模块的预览流需要拿到已初始化的相机实例。
        // 失败不抛异常（返回 Result），所以这里只会记日志，不会影响启动。
        RecordingModule.initialize(this, BuildConfig.DEBUG)
            .onFailure { error -> Timber.e(error, "Failed to initialize recording module") }

        Timber.d("OneDay Application started")
    }
}
