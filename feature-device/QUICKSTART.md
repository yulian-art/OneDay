# Feature-Device 快速启动指南

## 🚀 立即开始

### 第一步：验证模块结构
```bash
cd /home/julien/OneDay
ls -la feature-device/src/main/java/com/julien/feature_device/
```

应该看到：
```
├── DeviceModule.kt
├── model/
├── repository/
├── viewmodel/
└── ui/
```

### 第二步：在主 app 中集成

#### 2.1 确认依赖已添加
检查 `app/build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":feature-device"))  // ✅ 已添加
    // ...
}
```

#### 2.2 配置 Hilt (如果未配置)
```kotlin
// app/src/main/java/com/julien/oneday/MainApplication.kt
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 初始化代码
    }
}
```

#### 2.3 在 Navigation 中添加设备页面
```kotlin
// app/src/main/java/com/julien/oneday/navigation/NavGraph.kt
import com.julien.feature_device.DeviceModule

@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(navController, startDestination = "home") {
        composable("home") { HomeScreen(/* ... */) }
        
        // 添加设备页面
        composable("devices") {
            DeviceModule.getDeviceScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
```

#### 2.4 添加权限请求
```kotlin
// app/src/main/java/com/julien/oneday/MainActivity.kt
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            // 权限已授予，可以导航到设备页面
        } else {
            // 权限被拒绝
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 请求蓝牙权限
        bluetoothPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )
    }
}
```

### 第三步：实现 DeviceRepository

这是**最关键**的部分，需要集成 Insta360 SDK：

```kotlin
// feature-device/src/main/java/com/julien/feature_device/repository/DeviceRepositoryImpl.kt
import com.arashivision.sdkcamera.camera.InstaCameraManager
import javax.inject.Inject
import kotlinx.coroutines.flow.*

class DeviceRepositoryImpl @Inject constructor(
    // TODO: 注入 Insta360 SDK 实例
    private val cameraManager: InstaCameraManager
) : DeviceRepository {
    
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    
    override fun startScan() {
        // TODO: 调用 Insta360 SDK 开始扫描
        // cameraManager.startDiscovery(...)
    }
    
    override fun stopScan() {
        // TODO: 停止扫描
        // cameraManager.stopDiscovery()
    }
    
    override suspend fun connectDevice(deviceId: String): Result<Unit> {
        return try {
            // TODO: 连接设备
            // cameraManager.connectDevice(deviceId)
            _connectionStatus.value = ConnectionStatus.CONNECTED
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun disconnectDevice() {
        // TODO: 断开连接
        // cameraManager.disconnect()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }
    
    override fun observeDevices(): Flow<List<DeviceInfo>> = _devices.asStateFlow()
    
    override fun observeConnectionStatus(): Flow<ConnectionStatus> = _connectionStatus.asStateFlow()
}
```

### 第四步：配置 Hilt Module

```kotlin
// feature-device/src/main/java/com/julien/feature_device/di/DeviceModule.kt
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {
    
    @Provides
    @Singleton
    fun provideDeviceRepository(
        // TODO: 注入 Insta360 SDK 依赖
    ): DeviceRepository {
        return DeviceRepositoryImpl(/* ... */)
    }
}
```

### 第五步：构建和测试

#### 在 Windows 环境中构建（推荐）
```cmd
cd D:\path\to\OneDay
gradlew.bat :feature-device:assembleDebug
```

#### 或在 WSL 中构建（需要修复 Build Tools）
```bash
cd /home/julien/OneDay
./gradlew :feature-device:assembleDebug
```

#### 运行预览
在 Android Studio 中：
1. 打开 `feature-device/src/main/java/com/julien/feature_device/ui/DeviceScreenPreviews.kt`
2. 点击 Composable 函数旁的 ▶️ 图标
3. 查看 UI 预览效果

## 📋 检查清单

### Phase 1: 模块集成 ✅
- [x] 创建 feature-device 模块
- [x] 实现 MVVM 架构
- [x] 完成 Compose UI
- [x] 添加到主 app 依赖

### Phase 2: 蓝牙实现 ⏳
- [ ] 集成 Insta360 SDK
- [ ] 实现 DeviceRepositoryImpl
- [ ] 配置 Hilt DI
- [ ] 测试蓝牙扫描
- [ ] 测试设备连接

### Phase 3: 权限和错误处理 ⏳
- [ ] 封装权限请求工具类
- [ ] 添加权限被拒绝的提示
- [ ] 完善错误处理逻辑
- [ ] 添加重试机制

### Phase 4: 测试 ⏳
- [ ] 单元测试 (ViewModel)
- [ ] 集成测试 (Repository)
- [ ] UI 测试 (Compose)
- [ ] 真机测试

## 🔧 常见问题

### Q1: 如何添加 Insta360 SDK？
```kotlin
// feature-device/build.gradle.kts
dependencies {
    // TODO: 添加 Insta360 SDK 依赖
    implementation("com.arashivision.sdk:sdkcamera:x.x.x")
    implementation("com.arashivision.sdk:sdkmedia:x.x.x")
}
```

需要在 `settings.gradle.kts` 中添加 Insta360 的 Maven 仓库。

### Q2: WSL 构建失败怎么办？
**错误信息**:
```
Build-tool 36.0.0 is missing AAPT at /mnt/d/AndroidSDK/build-tools/36.0.0/aapt
```

**解决方案**:
1. 在 Windows 中使用 Android Studio 构建
2. 或从 PowerShell 运行: `gradlew.bat assembleDebug`
3. 或安装 Linux 版本的 Android SDK

### Q3: 如何测试 UI 预览？
打开 Android Studio → 找到 `DeviceScreenPreviews.kt` → 点击预览图标

可以看到 5 个预览场景：
- DeviceScreenEmptyPreview (空状态)
- DeviceScreenScanningPreview (扫描中)
- DeviceScreenWithDevicesPreview (有设备)
- DeviceScreenConnectedPreview (已连接)
- DeviceScreenErrorPreview (错误状态)

### Q4: 如何从主 app 导航到设备页面？
```kotlin
// 在任意 Composable 中
Button(onClick = { navController.navigate("devices") }) {
    Text("设备管理")
}
```

## 📚 参考资料

### 内部文档
- [feature-device/README.md](./README.md) - 模块详细文档
- [feature-device/IMPLEMENTATION.md](./IMPLEMENTATION.md) - 实现总结
- [ARCHITECTURE.md](../ARCHITECTURE.md) - 系统架构

### 外部文档
- [Insta360 SDK 文档](https://github.com/Insta360Develop) (需要补充链接)
- [Android Bluetooth LE Guide](https://developer.android.com/guide/topics/connectivity/bluetooth/ble-overview)
- [Jetpack Compose Documentation](https://developer.android.com/jetpack/compose)
- [Hilt Dependency Injection](https://developer.android.com/training/dependency-injection/hilt-android)

## 🎯 下一步行动

### 立即可做
1. ✅ **验证模块结构**: 检查所有文件是否正确生成
2. ✅ **阅读代码**: 理解 MVVM 架构和数据流
3. ✅ **查看预览**: 在 Android Studio 中运行 UI 预览

### 需要支持
1. 🔍 **获取 Insta360 SDK**: 找到官方 SDK 和文档
2. 🔍 **配置 Maven 仓库**: 添加 SDK 依赖源
3. 🔍 **API Key**: 申请 Insta360 开发者账号

### 后续开发
1. 📝 **实现 DeviceRepositoryImpl**: 集成真实蓝牙通信
2. 📝 **权限封装**: 创建权限请求工具类
3. 📝 **错误处理**: 完善各种边界情况
4. 📝 **单元测试**: 编写测试用例

---

**需要帮助？**
- 查看 `context.md` 了解项目当前状态
- 查看 `COLLABORATION.md` 了解协作流程
- 提交 Issue 或创建任务卡片

**创建时间**: 2026-09-18  
**状态**: ✅ UI 完成，⏳ 蓝牙实现待开始
