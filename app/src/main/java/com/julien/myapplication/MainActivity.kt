package com.julien.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.julien.feature_device.DeviceModule
import com.julien.feature_recording.RecordingModule

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { granted -> !granted }) {
            Toast.makeText(
                this,
                "需要蓝牙和附近设备权限才能扫描相机",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 极简导航：不引入 navigation-compose，只用一个布尔状态切换两个功能页。
            //
            // 录制入口做成叠加在设备页之上的悬浮按钮，而不是去改
            // feature-device 的 DeviceScreen 布局 —— 那个页面的排版权不属于
            // 录制与媒体这一块，叠加式入口对它的改动为零。
            var showRecording by remember { mutableStateOf(false) }

            if (showRecording) {
                RecordingModule.getRecordingScreen(
                    onNavigateBack = { showRecording = false },
                )
            } else {
                Box(Modifier.fillMaxSize()) {
                    DeviceModule.getDeviceScreen()
                    FloatingActionButton(
                        onClick = { showRecording = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Videocam,
                            contentDescription = "录制与媒体",
                        )
                    }
                }
            }
        }
        requestDevicePermissions()
    }

    private fun requestDevicePermissions() {
        val requiredPermissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (requiredPermissions.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }
}
