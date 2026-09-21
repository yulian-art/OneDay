# OneDay 测试快速启动指南

## 当前可用命令（2026-09-19）

当前仓库包含 3 个 Android JVM 测试、2 个仅检查包名的仪器测试，以及 31 个后端测试用例。验证结果见 [TESTING_DELIVERY_SUMMARY.md](TESTING_DELIVERY_SUMMARY.md) 顶部。

从仓库根目录运行 Android 测试：

```bash
./gradlew :app:testDebugUnitTest :feature-device:testDebugUnitTest
# 需模拟器或手机；尚无设备连接业务 E2E
./gradlew :app:connectedDebugAndroidTest :feature-device:connectedDebugAndroidTest
```

报告分别位于 `app/build/reports/tests/testDebugUnitTest/` 和 `feature-device/build/reports/tests/testDebugUnitTest/`。

后端先按 [backend/README.md](backend/README.md) 准备虚拟环境，再从仓库根目录运行：

```bash
cd backend
./.venv/bin/python -m pytest -q
./.venv/bin/python -m ruff check .
```

Windows 使用 `gradlew.bat` 和 `.venv\Scripts\python`。后端测试使用临时数据库和模型替身，不需要相机或模型密钥。

## 早期测试扩展方案（以下内容尚未全部落地）

下文保留为设计参考：`DeviceViewModelTest.kt`、`Insta360Simulator.kt`、`DeviceConnectionE2ETest.kt` 和 Makefile 当前不存在；Hilt、MockK、Coroutines Test、Turbine、Compose UI Test 等示例也未配置。不要将下文“应该看到”或完成标记作为当前交付依据。

## 🚀 立即开始

### 第一步：验证测试文件
```bash
cd /home/julien/OneDay

# 检查测试文件是否创建
ls -la feature-device/src/test/java/com/julien/feature_device/viewmodel/
ls -la feature-device/src/test/java/com/julien/feature_device/test/
ls -la app/src/androidTest/java/com/julien/oneday/e2e/
```

应该看到：
```
✅ DeviceViewModelTest.kt          # ViewModel 单元测试
✅ Insta360Simulator.kt            # 设备模拟器
✅ DeviceConnectionE2ETest.kt      # E2E 测试
```

---

## 📦 测试依赖配置

### feature-device/build.gradle.kts

在现有配置基础上添加测试依赖：

```kotlin
dependencies {
    // === 测试依赖 ===
    
    // JUnit 5
    testImplementation("junit:junit:4.13.2")
    
    // MockK (Kotlin Mocking)
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("io.mockk:mockk-android:1.13.8")
    
    // Coroutines Test
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    
    // Turbine (Flow 测试)
    testImplementation("app.cash.turbine:turbine:1.0.0")
    
    // === Android Instrumented 测试 ===
    
    // Compose Testing
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.5.4")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.5.4")
    
    // Hilt Testing
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.48")
    kaptAndroidTest("com.google.dagger:hilt-android-compiler:2.48")
    
    // Espresso
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
```

---

## 🧪 运行测试

### 1. 单元测试（本地 JVM）

```bash
# 运行所有单元测试
./gradlew :feature-device:test

# 运行特定测试类
./gradlew :feature-device:test --tests DeviceViewModelTest

# 运行特定测试方法
./gradlew :feature-device:test --tests "DeviceViewModelTest.startScan should update isScanning to true"

# 生成测试报告（带 HTML）
./gradlew :feature-device:test --info
# 报告位置: feature-device/build/reports/tests/test/index.html
```

**预期输出**：
```
> Task :feature-device:test

DeviceViewModelTest > initial state should be correct PASSED
DeviceViewModelTest > startScan should update isScanning to true PASSED
DeviceViewModelTest > stopScan should update isScanning to false PASSED
DeviceViewModelTest > observeDevices should update availableDevices list PASSED
DeviceViewModelTest > connectDevice success should update connectedDevice PASSED
DeviceViewModelTest > connectDevice failure should set error message PASSED
DeviceViewModelTest > disconnectDevice should clear connectedDevice PASSED
DeviceViewModelTest > clearError should clear error message PASSED

BUILD SUCCESSFUL in 15s
```

---

### 2. Instrumented 测试（真机/模拟器）

```bash
# 启动 Android 模拟器（如果使用模拟器）
# 或连接真机

# 检查设备连接
adb devices

# 运行所有 instrumented 测试
./gradlew :app:connectedAndroidTest

# 运行特定 E2E 测试
./gradlew :app:connectedAndroidTest \
  --tests "DeviceConnectionE2ETest.completeDeviceConnectionFlow_shouldSucceed"

# 生成测试报告
# 报告位置: app/build/reports/androidTests/connected/index.html
```

**预期输出**：
```
> Task :app:connectedDebugAndroidTest

DeviceConnectionE2ETest > completeDeviceConnectionFlow_shouldSucceed PASSED
DeviceConnectionE2ETest > scanWithNoDevices_shouldShowEmptyState PASSED
DeviceConnectionE2ETest > connectionFailure_shouldShowError PASSED
DeviceConnectionE2ETest > dismissErrorBanner_shouldHideError PASSED
DeviceConnectionE2ETest > stopScan_shouldStopScanning PASSED
DeviceConnectionE2ETest > backNavigation_shouldReturnToPreviousScreen PASSED

BUILD SUCCESSFUL in 2m 15s
```

---

### 3. 快速测试套件

创建自定义 Gradle 任务简化测试执行：

```bash
# 在项目根目录创建 Makefile
cat > Makefile << 'EOF'
.PHONY: test-unit test-integration test-e2e test-all

# 单元测试（快速）
test-unit:
	./gradlew :feature-device:test :app:test

# 集成测试（需要模拟器）
test-integration:
	./gradlew :feature-device:connectedAndroidTest

# E2E 测试（需要模拟器）
test-e2e:
	./gradlew :app:connectedAndroidTest

# 完整测试套件
test-all: test-unit test-integration test-e2e
	@echo "✅ All tests passed!"

# 快速冒烟测试（仅关键路径）
test-smoke:
	./gradlew :feature-device:test --tests DeviceViewModelTest
	./gradlew :app:connectedAndroidTest \
		--tests "DeviceConnectionE2ETest.completeDeviceConnectionFlow_shouldSucceed"
EOF
```

使用：
```bash
make test-unit       # ~2 分钟
make test-e2e        # ~5 分钟
make test-smoke      # ~3 分钟（最快）
make test-all        # ~10 分钟（完整）
```

---

## 🎯 测试场景清单

### ✅ 已实现

#### 单元测试（DeviceViewModelTest）
- [x] 初始状态验证
- [x] 开始扫描更新状态
- [x] 停止扫描更新状态
- [x] 观察设备列表更新
- [x] 连接设备成功流程
- [x] 连接设备失败处理
- [x] 断开设备流程
- [x] 清除错误消息
- [x] 多次扫描循环
- [x] 连接状态变化

#### E2E 测试（DeviceConnectionE2ETest）
- [x] 完整设备连接流程
- [x] 扫描无设备显示空状态
- [x] 连接失败显示错误
- [x] 关闭错误横幅
- [x] 停止扫描
- [x] 返回导航
- [x] 多设备切换连接

### ⏳ 待实现

#### 集成测试
- [ ] DeviceRepository 真实蓝牙测试
- [ ] Compose UI 组件测试
- [ ] Navigation 流程测试

#### 性能测试
- [ ] 长时间扫描测试
- [ ] 频繁连接断开测试
- [ ] 电量监控测试

#### 边界情况
- [ ] 蓝牙权限拒绝测试
- [ ] 设备电量低测试
- [ ] 固件版本不兼容测试

---

## 🛠️ 调试测试

### 1. 查看测试日志

```bash
# 运行测试并显示详细日志
./gradlew :feature-device:test --info

# 仅显示失败的测试
./gradlew :feature-device:test --continue

# 重新运行失败的测试
./gradlew :feature-device:test --rerun-tasks
```

### 2. 使用 Android Studio

1. 打开 `DeviceViewModelTest.kt`
2. 点击类名或方法名旁的绿色 ▶️ 图标
3. 选择 "Run 'DeviceViewModelTest'"
4. 查看底部测试结果面板

### 3. 调试 E2E 测试

```kotlin
// 在测试中添加断点
@Test
fun completeDeviceConnectionFlow_shouldSucceed() = runTest {
    composeTestRule.onNodeWithText("设备管理").performClick()
    
    // 添加延迟以观察 UI 变化
    Thread.sleep(2000)  // ⚠️ 仅用于调试，不要提交
    
    composeTestRule.onNodeWithText("开始扫描").performClick()
}
```

### 4. 截图调试

```kotlin
@Test
fun someTest() {
    // 执行操作
    composeTestRule.onNodeWithText("设备管理").performClick()
    
    // 截图
    composeTestRule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
        val file = File(context.getExternalFilesDir(null), "screenshot.png")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
```

---

## 📊 测试覆盖率

### 生成覆盖率报告

```kotlin
// feature-device/build.gradle.kts
android {
    buildTypes {
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
    }
}
```

运行：
```bash
# 生成单元测试覆盖率
./gradlew :feature-device:testDebugUnitTestCoverage

# 查看报告
open feature-device/build/reports/coverage/test/debug/index.html
```

**目标覆盖率**：
- ViewModel: > 95%
- Repository Interface: 100%
- Model: 100%
- UI (Composable): > 80%

---

## 🔧 常见问题

### Q1: MockK 找不到类

**错误**：
```
java.lang.NoClassDefFoundError: io/mockk/MockKGateway
```

**解决**：
```kotlin
// 确保添加了测试依赖
testImplementation("io.mockk:mockk:1.13.8")
testImplementation("io.mockk:mockk-android:1.13.8")
```

### Q2: Coroutines 测试失败

**错误**：
```
java.lang.IllegalStateException: Module with the Main dispatcher had failed to initialize
```

**解决**：
```kotlin
@Before
fun setup() {
    Dispatchers.setMain(testDispatcher)  // ✅ 设置测试调度器
}

@After
fun tearDown() {
    Dispatchers.resetMain()  // ✅ 重置调度器
}
```

### Q3: E2E 测试超时

**错误**：
```
androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 1000 ms
```

**解决**：
```kotlin
// 增加超时时间
composeTestRule.waitUntil(timeoutMillis = 5000) {  // 默认 1000ms
    composeTestRule
        .onAllNodesWithText("已连接")
        .fetchSemanticsNodes().isNotEmpty()
}
```

### Q4: Hilt 注入失败

**错误**：
```
java.lang.IllegalStateException: Hilt test, ... must use @HiltAndroidTest
```

**解决**：
```kotlin
@HiltAndroidTest  // ✅ 必须添加此注解
@RunWith(AndroidJUnit4::class)
class DeviceConnectionE2ETest {
    
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)  // ✅ Hilt 规则必须先执行
    
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()
    
    @Before
    fun setup() {
        hiltRule.inject()  // ✅ 注入依赖
    }
}
```

---

## 📚 参考资料

### 官方文档
- [Jetpack Compose Testing](https://developer.android.com/jetpack/compose/testing)
- [Coroutines Test](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/)
- [MockK Documentation](https://mockk.io/)
- [Hilt Testing](https://developer.android.com/training/dependency-injection/hilt-testing)

### 内部文档
- [E2E_TESTING_STRATEGY.md](./E2E_TESTING_STRATEGY.md) - 完整测试策略
- [feature-device/README.md](./feature-device/README.md) - 模块文档
- [ARCHITECTURE.md](./ARCHITECTURE.md) - 系统架构

---

## 🎯 下一步行动

### 立即可做
1. ✅ **运行单元测试**: `make test-unit`
2. ✅ **查看测试报告**: 打开 HTML 报告
3. ✅ **启动模拟器**: 准备 E2E 测试

### 本周任务
1. 📝 **添加 DeviceRepository 集成测试**
2. 📝 **完善 UI 组件测试**
3. 📝 **完善本地测试脚本**

### 长期目标
1. 📝 **性能测试框架**
2. 📝 **视觉回归测试**
3. 📝 **真机设备测试池**

---

**创建时间**: 2026-09-18  
**状态**: ✅ 基础测试框架完成  
**下一个里程碑**: 真实蓝牙集成测试
