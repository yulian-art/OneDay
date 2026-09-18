package com.julien.feature_device.di

import android.content.Context
import com.julien.feature_device.data.repository.DeviceRepository
import com.julien.feature_device.data.repository.DeviceRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {

    @Provides
    @Singleton
    fun provideDeviceRepository(
        @ApplicationContext context: Context
    ): DeviceRepository {
        return DeviceRepositoryImpl(context)
    }
}
