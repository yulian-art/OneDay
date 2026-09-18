package com.julien.myapplication

import android.app.Application
import com.arashivision.inskmp.editsdk.manager.INSKMPEditSDKMgr
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class OneDayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        INSKMPEditSDKMgr.enableDebug(true)
        Timber.d("OneDay Application started")
    }
}
