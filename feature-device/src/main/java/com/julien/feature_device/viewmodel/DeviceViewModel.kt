package com.julien.feature_device.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arashivision.inskmp.insble.data.BleDeviceCore
import com.julien.feature_device.data.repository.ConnectionState
import com.julien.feature_device.data.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val repository: DeviceRepository
) : ViewModel() {

    val scannedDevices: StateFlow<List<BleDeviceCore>> = repository.scannedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectionState: StateFlow<ConnectionState> = repository.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionState.Idle)

    fun startScan() {
        viewModelScope.launch {
            try {
                repository.startScanning()
            } catch (e: Exception) {
                Timber.e(e, "Failed to start scan")
            }
        }
    }

    fun stopScan() {
        viewModelScope.launch {
            try {
                repository.stopScanning()
            } catch (e: Exception) {
                Timber.e(e, "Failed to stop scan")
            }
        }
    }

    fun connectDevice(device: BleDeviceCore) {
        viewModelScope.launch {
            try {
                repository.connect(device)
            } catch (e: Exception) {
                Timber.e(e, "Failed to connect")
            }
        }
    }

    fun disconnectDevice() {
        viewModelScope.launch {
            try {
                repository.disconnect()
            } catch (e: Exception) {
                Timber.e(e, "Failed to disconnect")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            repository.disconnect()
        }
    }
}
