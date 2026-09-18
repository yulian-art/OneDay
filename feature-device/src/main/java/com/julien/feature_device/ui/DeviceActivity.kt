package com.julien.feature_device.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import com.arashivision.sdk.camera.InstaCameraSDK
import com.arashivision.sdk.media.InstaMediaSDK
import com.julien.feature_device.util.PermissionHelper
import timber.log.Timber

/**
 * 设备连接 Activity
 */
class DeviceActivity : ComponentActivity() {

    private lateinit var viewModel: DeviceViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Timber.d("All permissions granted")
            initializeSDK()
        } else {
            Timber.w("Permissions denied: ${permissions.filter { !it.value }}")
            Toast.makeText(
                this,
                "需要授予蓝牙和位置权限才能扫描设备",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化 Timber
        if (Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
        }

        // 初始化 ViewModel
        viewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[DeviceViewModel::class.java]

        // 请求权限
        requestPermissions()

        setContent {
            AppTheme {
                DeviceScreen(
                    viewModel = viewModel,
                    onNavigateToRecording = {
                        // 导航到录制界面
                        Toast.makeText(
                            this,
                            "准备进入录制界面",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }
    }

    private fun requestPermissions() {
        val permissions = PermissionHelper.getAllRequiredPermissions()

        if (PermissionHelper.hasBluetoothPermissions(this)) {
            initializeSDK()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun initializeSDK() {
        try {
            // 初始化 Insta360 SDK
            InstaCameraSDK.init(application) {
                cacheDir = externalCacheDir?.absolutePath
                logLevel = if (BuildConfig.DEBUG) 2 else 0 // 0=关闭, 1=错误, 2=详细
            }
            InstaMediaSDK.init(application)
            Timber.d("Insta360 SDK initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize SDK")
            Toast.makeText(
                this,
                "SDK 初始化失败: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.disconnect()
    }
}

@Composable
private fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF38BDF8),
            secondary = Color(0xFF6EE7B7),
            background = Color(0xFF0A0D12),
            surface = Color(0xFF161D2B),
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF38BDF8),
            secondary = Color(0xFF6EE7B7),
            background = Color(0xFFF8FAFC),
            surface = Color.White,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = Color(0xFF0A0D12),
            onSurface = Color(0xFF0A0D12)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
