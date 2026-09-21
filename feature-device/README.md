# Feature-Device 模块

设备连接和管理模块，负责 Insta360 相机的 BLE 扫描、连接和设备信息读取。

**进度核对：2026-09-19。** SDK 与 BLE 调用代码已接入，真机验收待完成。全项目进度见 [context.md](../context.md)。

## 当前功能

- 扫描列表显示设备名称、地址，支持开始和停止扫描。
- 连接成功后读取型号、固件版本和电量，展示设备卡片，支持断开连接。
- Compose 页面显示扫描、连接和错误状态；ViewModel 通过 Flow 订阅 Repository。
- 主 app 已初始化 SDK、显示设备页面并请求运行时权限。

电量当前在连接后读取，尚未持续监控；录制控制、预览流和后端调用尚未接入。

## 代码结构

以下目录位于 `src/main/java/com/julien/feature_device/`：

```text
DeviceModule.kt                  # Camera/Media SDK 初始化与页面入口
ui/
  ├── DeviceScreen.kt            # 设备管理页面
  └── DeviceScreenPreviews.kt    # Compose 预览
viewmodel/
  └── DeviceViewModel.kt         # 扫描、连接、断开与状态订阅
model/
  ├── DeviceInfo.kt              # 设备信息
  └── DeviceState.kt             # 早期 UI 状态模型
data/repository/
  ├── DeviceRepository.kt        # 接口与 ConnectionState
  └── DeviceRepositoryImpl.kt    # Insta360 BLE SDK 调用
util/
  └── PermissionHelper.kt        # 蓝牙及存储权限检查工具
```

## 当前架构与依赖

`DeviceScreen → DeviceViewModel → DeviceRepository → Insta360 SDK`。

当前页面订阅 `scannedDevices` 和 `connectionState`，连接状态为 `Idle`、`Scanning`、`Connecting`、`Connected`、`Error`。`DeviceState.kt` 中保留的 `DeviceUiState` / `ConnectionStatus` 未用于当前页面。

Repository 由 ViewModel 构造参数提供，默认创建 `DeviceRepositoryImpl`，当前未接入 Hilt。实际依赖见 [build.gradle.kts](build.gradle.kts) 和 [版本目录](../gradle/libs.versions.toml)：

- Insta360 `sdk-camera` / `sdk-media` 2.1.5。
- Jetpack Compose、Material 3、Lifecycle ViewModel、Kotlin Coroutines/Flow、Timber。
- 测试依赖为 JUnit 4、AndroidX JUnit 和 Espresso；MockK、Coroutines Test、Turbine、Compose UI Test 尚未加入。

## 主 app 接入

`app` 已依赖 `:feature-device`。`OneDayApplication.onCreate()` 调用 `DeviceModule.initialize(application, debug)` 初始化两个 SDK，`MainActivity` 调用以下入口显示设备页面：

```kotlin
DeviceModule.getDeviceScreen(
    onNavigateBack = { /* 可选返回导航 */ }
)
```

其他宿主接入时也需先完成 SDK 初始化。模块 Manifest 已声明蓝牙和位置权限；主 app 已按 Android 版本请求蓝牙/位置权限及 Android 13+ 附近 Wi-Fi 权限。

`PermissionHelper` 已提供权限检查工具，但主界面的权限请求逻辑目前独立实现。权限拒绝、蓝牙关闭和后续恢复的完整交互仍需验证。

## 开发状态

### 已完成的代码

- [x] MVVM 分层、Compose 页面、设备列表与连接信息卡片。
- [x] 扫描/连接状态管理、错误展示和 UI 预览。
- [x] Insta360 Camera/Media SDK 2.1.5 依赖及 Application 初始化。
- [x] BLE 扫描、停止扫描、连接、断开与设备信息读取调用。
- [x] 主 app 页面接入、运行时权限请求和权限检查工具。
- [x] `DeviceInfoTest` 与模板单元测试（本模块共 2 个）。

### 待实现 / 待验证

- [ ] 手机与 Insta360 相机的扫描、连接、断开和信息读取验收。
- [ ] 权限拒绝、蓝牙关闭、扫描失败、连接中断与恢复场景。
- [ ] ViewModel、Repository 和 Compose 业务测试。
- [ ] 设备连接 E2E 测试及设备模拟器。
- [ ] 录制控制、预览流、时间同步与后端联调。

### 验证记录

2026-09-19 的既有 `testDebugUnitTest` XML 报告显示，本模块 2 个测试通过，主 app 另有 1 个模板测试通过。本次仅核对报告，未重跑 Android 构建或仪器测试。这些结果不能证明真实蓝牙链路已经通过。

现有 `ExampleInstrumentedTest` 只检查应用包名，当前仓库没有 `DeviceViewModelTest.kt`、`Insta360Simulator.kt` 或 `DeviceConnectionE2ETest.kt`。

从仓库根目录运行：

```bash
./gradlew :feature-device:testDebugUnitTest
# 需模拟器或手机；仅运行现有包名检查
./gradlew :feature-device:connectedDebugAndroidTest
```

单元测试报告：`feature-device/build/reports/tests/testDebugUnitTest/index.html`。

## 构建环境

当前配置为 compile SDK 35、最低 API 28；app 指定 Build Tools 34.0.0。Gradle Wrapper 9.5.0，AGP 9.3.2，Gradle Daemon 使用 JDK 21，JVM 编译目标为 17。

早期 WSL 构建曾因引用 Windows 版 Android SDK 而缺少 Linux `aapt`。在 WSL 中应使用 Linux 版 SDK，或在 Windows 使用 Android Studio / `gradlew.bat` 构建。工作区已有本地 Debug APK，早期构建错误不再直接列为当前阻塞。

## 下一步

1. 真机验证扫描 → 连接 → 信息读取 → 断开，并记录异常场景结果。
2. 补齐 ViewModel、Repository、UI/E2E 测试及所需依赖。
3. 接入录制/预览和后端任务、会话、候选 API，完成最小业务闭环。

## 参考

- [项目开发进度](../context.md)
- [当前测试交付核对](../TESTING_DELIVERY_SUMMARY.md)
- [Android Bluetooth LE](https://developer.android.com/guide/topics/connectivity/bluetooth/ble-overview)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
