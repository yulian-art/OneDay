package com.julien.myapplication

import android.app.Application
import com.julien.feature_device.DeviceModule
import timber.log.Timber

class OneDayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        DeviceModule.initialize(this, BuildConfig.DEBUG)
            .onFailure { error -> Timber.e(error, "Failed to initialize device SDK") }
        Timber.d("OneDay Application started")
    }
}
