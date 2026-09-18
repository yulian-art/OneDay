package com.julien.oneday.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.julien.feature_device.test.Insta360Simulator
import com.julien.oneday.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * E2E Test: 完整的设备连接和录制流程
 *
 * 测试场景:
 * 1. 启动 App
 * 2. 导航到设备管理页面
 * 3. 扫描设备
 * 4. 连接设备
 * 5. 验证连接状态
 * 6. 开始录制（如果实现）
 *
 * 运行: ./gradlew :app:connectedAndroidTest
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DeviceConnectionE2ETest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var deviceSimulator: Insta360Simulator

    @Before
    fun setup() {
        hiltRule.inject()
        deviceSimulator.reset()
        // 配置模拟器：快速响应以加速测试
        deviceSimulator.scanDelayMs = 500
        deviceSimulator.connectDelayMs = 300
    }

    @Test
    fun completeDeviceConnectionFlow_shouldSucceed() = runTest {
        // === Phase 1: 导航到设备管理页面 ===
        composeTestRule
            .onNodeWithText("设备管理")
            .assertExists("设备管理按钮应该存在")
            .performClick()

        // 验证进入设备页面
        composeTestRule
            .onNodeWithText("设备管理", substring = true)
            .assertIsDisplayed()

        // === Phase 2: 开始扫描设备 ===
        composeTestRule
            .onNodeWithText("开始扫描")
            .assertExists("扫描按钮应该存在")
            .performClick()

        // 验证扫描状态
        composeTestRule
            .onNodeWithText("扫描中...")
            .assertIsDisplayed()

        // 等待模拟设备出现（最多等待 3 秒）
        composeTestRule.waitUntil(timeoutMillis = 3000) {
            composeTestRule
                .onAllNodesWithText("Insta360 X3 (Simulator)", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 验证设备列表显示
        composeTestRule
            .onNodeWithText("Insta360 X3 (Simulator)", substring = true)
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText("Insta360 GO 3 (Simulator)", substring = true)
            .assertIsDisplayed()

        // === Phase 3: 连接设备 ===
        // 找到第一个设备的连接按钮并点击
        composeTestRule
            .onAllNodesWithText("连接")
            .onFirst()
            .performClick()

        // 等待连接完成（最多等待 2 秒）
        composeTestRule.waitUntil(timeoutMillis = 2000) {
            composeTestRule
                .onAllNodesWithText("已连接", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // === Phase 4: 验证连接成功 ===
        // 应该显示已连接设备卡片
        composeTestRule
            .onNodeWithText("已连接设备")
            .assertIsDisplayed()

        // 验证设备详情
        composeTestRule
            .onNodeWithText("Insta360 X3 (Simulator)", substring = true)
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText("型号: X3", substring = true)
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText("固件: v1.2.3", substring = true)
            .assertIsDisplayed()

        // 验证连接状态芯片
        composeTestRule
            .onNodeWithText("已连接")
            .assertIsDisplayed()

        // === Phase 5: 断开设备 ===
        composeTestRule
            .onNodeWithText("断开连接")
            .performClick()

        // 验证断开成功
        composeTestRule.waitUntil(timeoutMillis = 1000) {
            composeTestRule
                .onAllNodesWithText("已连接设备")
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun scanWithNoDevices_shouldShowEmptyState() = runTest {
        // Given - 移除所有模拟设备
        deviceSimulator.reset()

        // When - 导航并扫描
        composeTestRule.onNodeWithText("设备管理").performClick()
        composeTestRule.onNodeWithText("开始扫描").performClick()

        // 等待扫描完成
        composeTestRule.waitUntil(timeoutMillis = 2000) {
            composeTestRule
                .onAllNodesWithText("扫描中...")
                .fetchSemanticsNodes().isEmpty()
        }

        // Then - 应该显示空状态
        composeTestRule
            .onNodeWithText("未找到设备")
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText("点击上方按钮开始扫描", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun connectionFailure_shouldShowError() = runTest {
        // Given - 配置模拟器返回连接失败
        deviceSimulator.shouldFailConnection = true

        // When - 扫描并尝试连接
        composeTestRule.onNodeWithText("设备管理").performClick()
        composeTestRule.onNodeWithText("开始扫描").performClick()

        composeTestRule.waitUntil(timeoutMillis = 3000) {
            composeTestRule
                .onAllNodesWithText("连接")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule
            .onAllNodesWithText("连接")
            .onFirst()
            .performClick()

        // Then - 应该显示错误信息
        composeTestRule.waitUntil(timeoutMillis = 2000) {
            composeTestRule
                .onAllNodesWithText("连接失败", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 错误横幅应该可见
        composeTestRule
            .onNode(hasTestTag("error_banner"))
            .assertExists()
    }

    @Test
    fun dismissErrorBanner_shouldHideError() = runTest {
        // Given - 触发错误
        deviceSimulator.shouldFailConnection = true
        composeTestRule.onNodeWithText("设备管理").performClick()
        composeTestRule.onNodeWithText("开始扫描").performClick()

        composeTestRule.waitUntil(timeoutMillis = 3000) {
            composeTestRule
                .onAllNodesWithText("连接")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onAllNodesWithText("连接").onFirst().performClick()

        composeTestRule.waitUntil(timeoutMillis = 2000) {
            composeTestRule
                .onAllNodesWithText("连接失败", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // When - 关闭错误横幅
        composeTestRule
            .onNode(hasContentDescription("关闭错误"))
            .performClick()

        // Then - 错误横幅应该消失
        composeTestRule
            .onNode(hasTestTag("error_banner"))
            .assertDoesNotExist()
    }

    @Test
    fun stopScan_shouldStopScanning() = runTest {
        // When - 开始扫描后立即停止
        composeTestRule.onNodeWithText("设备管理").performClick()
        composeTestRule.onNodeWithText("开始扫描").performClick()

        // 验证扫描中
        composeTestRule
            .onNodeWithText("扫描中...")
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText("停止扫描")
            .performClick()

        // Then - 扫描应该停止
        composeTestRule
            .onNodeWithText("扫描中...")
            .assertDoesNotExist()

        composeTestRule
            .onNodeWithText("开始扫描")
            .assertIsDisplayed()
    }

    @Test
    fun backNavigation_shouldReturnToPreviousScreen() = runTest {
        // When - 进入设备页面后返回
        composeTestRule.onNodeWithText("设备管理").performClick()

        // 验证在设备页面
        composeTestRule
            .onNodeWithText("开始扫描")
            .assertIsDisplayed()

        // 点击返回按钮
        composeTestRule
            .onNode(hasContentDescription("返回"))
            .performClick()

        // Then - 应该返回到主页
        composeTestRule
            .onNodeWithText("设备管理")
            .assertIsDisplayed()
    }

    @Test
    fun multipleDevices_shouldAllowSelectingDifferentDevice() = runTest {
        // When - 扫描并连接第一个设备
        composeTestRule.onNodeWithText("设备管理").performClick()
        composeTestRule.onNodeWithText("开始扫描").performClick()

        composeTestRule.waitUntil(timeoutMillis = 3000) {
            composeTestRule
                .onAllNodesWithText("连接")
                .fetchSemanticsNodes().size >= 2
        }

        // 连接第一个设备
        composeTestRule
            .onAllNodesWithText("连接")
            .onFirst()
            .performClick()

        composeTestRule.waitUntil(timeoutMillis = 2000) {
            composeTestRule
                .onAllNodesWithText("已连接")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 断开第一个设备
        composeTestRule.onNodeWithText("断开连接").performClick()

        composeTestRule.waitUntil(timeoutMillis = 1000) {
            composeTestRule
                .onAllNodesWithText("断开连接")
                .fetchSemanticsNodes().isEmpty()
        }

        // 连接第二个设备
        composeTestRule
            .onAllNodesWithText("连接")[1]
            .performClick()

        // Then - 第二个设备应该连接成功
        composeTestRule.waitUntil(timeoutMillis = 2000) {
            composeTestRule
                .onAllNodesWithText("Insta360 GO 3 (Simulator)", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
