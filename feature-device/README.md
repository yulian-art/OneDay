# Feature-Device 模块

## 概述

设备连接和管理功能模块，负责与 Insta360 相机设备的蓝牙通信和连接管理。

## 功能特性

### 1. 设备扫描
- 蓝牙 BLE 设备扫描
- 实时显示可用设备列表
- 设备信息展示（名称、型号、电量）

### 2. 设备连接
- 一键连接 Insta360 设备
- 连接状态实时反馈
- 连接错误处理和重试

### 3. 设备管理
- 查看已连接设备详情
- 监控设备电量状态
- 断开连接功能
- 固件版本显示

## 架构设计

### MVVM 架构
```
ui/
  ├── DeviceScreen.kt          # 主界面 Composable
  ├── DeviceScreenPreviews.kt  # UI 预览
  
viewmodel/
  ├── DeviceViewModel.kt       # ViewModel 层
  └── DeviceUiState.kt         # UI 状态管理
  
model/
  ├── DeviceInfo.kt            # 设备信息数据类
  └── ConnectionStatus.kt      # 连接状态枚举
  
repository/
  └── DeviceRepository.kt      # 数据仓库（蓝牙通信）
```

### 数据流
```
UI (DeviceScreen)
  ↓ 用户交互
ViewModel (DeviceViewModel)
  ↓ 业务逻辑
Repository (DeviceRepository)
  ↓ 蓝牙通信
Insta360 设备
```

## 使用方式

### 1. 在主 app 中导入
```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":feature-device"))
}
```

### 2. 使用 Composable
```kotlin
import com.julien.feature_device.DeviceModule

@Composable
fun MainScreen() {
    DeviceModule.getDeviceScreen(
        onNavigateBack = { /* 返回导航 */ }
    )
}
```

### 3. 权限要求
已在模块的 AndroidManifest.xml 中声明：
- `BLUETOOTH` / `BLUETOOTH_ADMIN`
- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (Android 12+)
- `ACCESS_FINE_LOCATION` (蓝牙扫描需要)

**注意**: 主 app 需要在运行时请求这些权限。

## UI 组件

### DeviceScreen
主界面，包含：
- **扫描控制**: 开始/停止扫描按钮
- **设备列表**: 显示所有可用设备
- **连接卡片**: 已连接设备的详细信息
- **错误提示**: 连接失败等错误信息

### 核心组件
- `DeviceListItem`: 设备列表项（可点击连接）
- `ConnectedDeviceCard`: 已连接设备信息卡片
- `ScanningIndicator`: 扫描进度指示器
- `EmptyDeviceList`: 空状态占位
- `ErrorBanner`: 错误提示横幅

## 状态管理

### DeviceUiState
```kotlin
data class DeviceUiState(
    val isScanning: Boolean = false,
    val availableDevices: List<DeviceInfo> = emptyList(),
    val connectedDevice: DeviceInfo? = null,
    val errorMessage: String? = null
)
```

### ConnectionStatus
```kotlin
enum class ConnectionStatus {
    DISCONNECTED,  // 未连接
    CONNECTING,    // 连接中
    CONNECTED,     // 已连接
    ERROR          // 错误
}
```

## 依赖项

```toml
[versions]
hilt = "2.51.1"

[libraries]
# DI
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
hilt-navigation-compose = "androidx.hilt:hilt-navigation-compose:1.2.0"

# Lifecycle
androidx-lifecycle-viewmodel-compose = "androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7"
androidx-lifecycle-runtime-compose = "androidx.lifecycle:lifecycle-runtime-compose:2.8.7"
```

## 开发状态

### ✅ 已完成
- [x] 模块架构搭建
- [x] MVVM 层级实现
- [x] Compose UI 界面
- [x] 设备扫描 UI
- [x] 设备连接 UI
- [x] 状态管理
- [x] 错误处理 UI
- [x] UI 预览
- [x] DeviceRepository 真实蓝牙通信实现
- [x] Insta360 SDK 集成

### 🚧 待实现
- [ ] 权限请求封装
- [ ] 单元测试
- [ ] 集成测试

## 构建注意事项

### WSL 环境问题
当前在 WSL 环境中构建时，可能遇到 Android SDK Build Tools 问题：
```
Build-tool 36.0.0 is missing AAPT at /mnt/d/AndroidSDK/build-tools/36.0.0/aapt
```

**原因**: Windows 版本的 Android SDK 只包含 `.exe` 文件，WSL 需要 Linux 版本。

**解决方案**:
1. 在 Windows 环境中使用 Android Studio 构建
2. 或在 WSL 中安装 Linux 版本的 Android SDK
3. 或使用 Windows 版本的 Gradle: `./gradlew.bat` (从 PowerShell/CMD 运行)

## 下一步

1. **集成 Insta360 SDK**: 实现 `DeviceRepository` 中的真实蓝牙通信
2. **权限封装**: 创建权限请求工具类
3. **错误处理**: 完善各种连接场景的错误提示
4. **测试**: 编写单元测试和 UI 测试
5. **文档**: 补充 API 文档和使用示例

## 参考

- [Insta360 SDK 文档](需补充链接)
- [Android Bluetooth LE](https://developer.android.com/guide/topics/connectivity/bluetooth/ble-overview)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
