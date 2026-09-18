package com.julien.feature_device.di

import android.app.Application
import com.julien.feature_device.data.repository.DeviceRepository
import com.julien.feature_device.data.repository.DeviceRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DeviceDataModule {

    @Provides
    @Singleton
    fun provideDeviceRepository(application: Application): DeviceRepository {
        return DeviceRepositoryImpl(application)
    }
}
