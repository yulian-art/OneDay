# OneDay 端到端测试策略

> 2026-09-19 状态核对：本文描述目标测试体系，示例不代表当前已实现能力或实测覆盖率。Android 当前仅有 3 个 JVM 测试与 2 个包名仪器测试，设备连接 E2E 和模拟器待实现；后端本次 31 个测试通过。当前交付与缺口见 [TESTING_DELIVERY_SUMMARY.md](TESTING_DELIVERY_SUMMARY.md) 顶部。

## 1. 测试架构概览

### 1.1 系统架构回顾
```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│ Android App │────→│ Insta360相机 │────→│  视频流     │
└─────────────┘     └──────────────┘     └─────────────┘
      ↓                                          ↓
      ↓                                    ┌──────────┐
      └────────────────────────────────────→│ 后端 API │
                                            └──────────┘
                                                  ↓
                                            ┌──────────┐
                                            │ AI 模型  │
                                            │ L1/L2/L3 │
                                            └──────────┘
```

### 1.2 测试金字塔

```
           ┌────────────┐
           │  E2E Tests │  ← 10% (关键用户场景)
           └────────────┘
         ┌────────────────┐
         │ Integration Tests│  ← 30% (模块间交互)
         └────────────────┘
      ┌────────────────────────┐
      │     Unit Tests         │  ← 60% (单个组件)
      └────────────────────────┘
```

**比例建议**:
- **Unit Tests**: 60% - 快速、隔离、覆盖核心逻辑
- **Integration Tests**: 30% - 模块间交互、API契约
- **E2E Tests**: 10% - 完整用户流程、关键路径

---

## 2. 测试层次划分

### 2.1 Level 1: 单元测试 (Unit Tests)

#### Android 端
```kotlin
// feature-device/src/test/java/
├── model/
│   ├── DeviceInfoTest.kt           // 数据类验证
│   └── ConnectionStatusTest.kt      // 枚举逻辑
│
├── viewmodel/
│   ├── DeviceViewModelTest.kt      // ViewModel 业务逻辑
│   │   - startScan 应更新 isScanning 状态
│   │   - connectDevice 成功应更新 connectedDevice
│   │   - 连接错误应设置 errorMessage
│   └── DeviceUiStateTest.kt         // 状态转换测试
│
└── repository/
    └── DeviceRepositoryTest.kt      // Repository Mock 测试
```

**工具栈**:
- JUnit 5
- MockK (Kotlin mocking)
- Turbine (Flow 测试)
- Coroutines Test

**示例**:
```kotlin
@Test
fun `startScan should emit scanning state`() = runTest {
    // Given
    val repository = mockk<DeviceRepository>()
    coEvery { repository.startScan() } just Runs
    val viewModel = DeviceViewModel(repository)
    
    // When
    viewModel.startScan()
    
    // Then
    viewModel.uiState.test {
        assertEquals(true, awaitItem().isScanning)
    }
}
```

#### 后端 API 测试
```python
# tests/unit/
├── test_task_parser.py              # 任务解析逻辑
├── test_event_detector.py           # 事件检测状态机
├── test_time_mapper.py              # 时间同步映射
└── test_feedback_classifier.py      # 纠正分类逻辑
```

**工具栈**:
- pytest
- pytest-asyncio
- unittest.mock

---

### 2.2 Level 2: 集成测试 (Integration Tests)

#### 2.2.1 Android Instrumented Tests

```kotlin
// app/src/androidTest/java/com/julien/oneday/
├── ui/
│   ├── DeviceScreenTest.kt          // Compose UI 测试
│   │   - 点击扫描按钮应触发扫描
│   │   - 设备列表应显示扫描结果
│   │   - 点击设备应触发连接
│   │
│   ├── RecordingScreenTest.kt       // 录制界面测试
│   └── CandidateListTest.kt         // 候选列表测试
│
├── repository/
│   └── DeviceRepositoryImplTest.kt  // 真实蓝牙测试（需真机）
│
└── navigation/
    └── NavGraphTest.kt               // 导航流程测试
```

**工具栈**:
- Espresso
- Compose Testing
- Hilt Testing
- AndroidX Test

**示例**:
```kotlin
@Test
fun clickScanButton_shouldStartScanning() {
    composeTestRule.setContent {
        DeviceScreen(onNavigateBack = {})
    }
    
    // 点击扫描按钮
    composeTestRule
        .onNodeWithText("开始扫描")
        .performClick()
    
    // 验证扫描指示器显示
    composeTestRule
        .onNodeWithText("扫描中...")
        .assertIsDisplayed()
}
```

#### 2.2.2 API 集成测试

```python
# tests/integration/
├── test_api_endpoints.py            # API 端点测试
│   - POST /api/v1/tasks 创建任务
│   - POST /api/v1/sessions 创建会话
│   - POST /api/v1/candidates 提交候选
│   - POST /api/v1/feedback 用户纠正
│
├── test_websocket_flow.py           # WebSocket 实时通知
└── test_database_operations.py      # 数据库操作验证
```

**工具栈**:
- pytest
- httpx (异步 HTTP 客户端)
- TestClient (FastAPI)
- pytest-postgresql

**示例**:
```python
@pytest.mark.asyncio
async def test_create_session_flow(client, test_user):
    # 1. 创建任务
    task_response = await client.post("/api/v1/tasks", json={
        "description": "每次喝水",
        "examples": []
    })
    task_id = task_response.json()["task_id"]
    
    # 2. 创建会话
    session_response = await client.post("/api/v1/sessions", json={
        "task_id": task_id,
        "recording_id": "rec-123"
    })
    
    assert session_response.status_code == 201
    assert "session_id" in session_response.json()
```

---

### 2.3 Level 3: 端到端测试 (E2E Tests)

#### 2.3.1 关键用户流程

```
测试场景 1: 首次使用完整流程
  1. 启动 App
  2. 连接 Insta360 设备
  3. 创建任务 "每次喝水"
  4. 开始录制
  5. 实时接收候选通知
  6. 确认候选
  7. 查看已确认事件列表
  8. 导出视频剪辑

测试场景 2: 用户纠正流程
  1. 查看候选列表
  2. 标记误判候选为"不符合"
  3. 选择纠正类型（标准理解错误 / 画面判断错误）
  4. 系统重新评估相关候选
  5. 验证纠正效果

测试场景 3: 设备异常处理
  1. 录制过程中断开蓝牙
  2. 验证重连机制
  3. 验证录制文件完整性
  4. 验证时间同步映射

测试场景 4: 长会话处理
  1. 录制 30 分钟视频
  2. 验证分段处理
  3. 验证多个候选生成
  4. 验证原片复核流程
```

#### 2.3.2 E2E 测试架构

```
┌──────────────────────────────────────────────┐
│            E2E Test Orchestrator             │
└──────────────────────────────────────────────┘
    ↓              ↓              ↓
┌─────────┐  ┌──────────┐  ┌──────────────┐
│ Android │  │ Mock     │  │ Test Backend │
│ Emulator│  │ Insta360 │  │ + Mock AI    │
│ + App   │  │ Simulator│  │              │
└─────────┘  └──────────┘  └──────────────┘
```

---

## 3. Mock 策略

### 3.1 Insta360 设备 Mock

**问题**: 真实设备不稳定、成本高、CI 环境不可用

**解决方案**: 创建 Insta360 SDK Simulator

```kotlin
// test-utils/src/main/java/com/julien/test/InstaSimulator.kt
class Insta360Simulator : DeviceRepository {
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private var isRecording = false
    
    override fun startScan() {
        // 模拟扫描延迟
        delay(1000)
        _devices.value = listOf(
            DeviceInfo(
                id = "sim-x3-001",
                name = "Insta360 X3 (Simulator)",
                model = "X3",
                firmwareVersion = "v1.2.3",
                batteryLevel = 85,
                status = ConnectionStatus.DISCONNECTED
            )
        )
    }
    
    override suspend fun connectDevice(deviceId: String): Result<Unit> {
        delay(500) // 模拟连接延迟
        return Result.success(Unit)
    }
    
    fun simulateRecording(videoFile: File) {
        // 提供预先录制的测试视频
        isRecording = true
        // 模拟录制流
    }
    
    fun simulateBatteryDrain() {
        // 模拟电量变化
    }
    
    fun simulateDisconnection() {
        // 模拟蓝牙断开
    }
}
```

**使用场景**:
- 单元测试和集成测试默认使用
- E2E 测试中的大部分场景
- CI/CD 环境

---

### 3.2 AI 模型 Mock

**问题**: 真实 AI 调用成本高、延迟大、不确定性

**解决方案**: 三层 Mock 策略

```python
# tests/mocks/model_mock.py
class ModelMockL1:
    """端侧检测 Mock - 确定性响应"""
    def detect_frame(self, frame):
        # 基于帧内容的哈希返回固定结果
        if self._contains_dog_near_bowl(frame):
            return {"trigger": True, "confidence": 0.85}
        return {"trigger": False}
    
class ModelMockL2:
    """云端初步复核 Mock - 可配置场景"""
    def __init__(self, scenario="happy_path"):
        self.scenario = scenario
    
    def review_candidate(self, frames):
        if self.scenario == "happy_path":
            return {"符合": True, "confidence": 0.92}
        elif self.scenario == "uncertain":
            return {"符合": False, "reason": "遮挡"}
        elif self.scenario == "latency":
            time.sleep(5)  # 模拟网络延迟
            return {"符合": True}
    
class ModelMockL3:
    """原片复核 Mock - 可录制和回放"""
    def __init__(self, mode="replay"):
        self.mode = mode
        self.recorded_responses = {}
    
    def review_original(self, video_segment):
        if self.mode == "replay":
            # 从预先录制的响应中读取
            segment_hash = hashlib.md5(video_segment).hexdigest()
            return self.recorded_responses.get(segment_hash)
        elif self.mode == "record":
            # 调用真实 API 并记录响应
            response = self._call_real_api(video_segment)
            self.recorded_responses[segment_hash] = response
            return response
```

**策略**:
1. **Record Mode**: 首次运行时调用真实 API，记录响应
2. **Replay Mode**: 后续运行使用录制的响应（确定性、零成本）
3. **Scenario Mode**: 针对特定场景（错误、超时、边界情况）

---

### 3.3 测试数据准备

```
tests/fixtures/
├── videos/
│   ├── dog_drinking_water.mp4      # 标准场景
│   ├── dog_drinking_obscured.mp4   # 遮挡场景
│   ├── false_positive_case.mp4     # 误判场景
│   └── long_session_30min.mp4      # 长会话
│
├── tasks/
│   ├── task_drinking.json          # 任务定义
│   ├── task_with_examples.json     # 带示例的任务
│   └── task_multi_subject.json     # 多主体任务
│
└── expected_results/
    ├── candidates_drinking.json     # 预期候选列表
    └── time_mappings.json           # 预期时间映射
```

---

## 4. E2E 测试实现

### 4.1 测试框架选择

**Android 端**:
- **UI Automator**: 跨应用交互（如权限弹窗）
- **Maestro**: 简单声明式测试（推荐用于快速冒烟测试）
- **Appium**: 完整的跨平台方案（如需 iOS 扩展）

**后端测试**:
- **pytest-bdd**: 行为驱动测试（BDD）
- **Locust**: 性能和负载测试

**推荐组合**: UI Automator + pytest + Docker Compose

---

### 4.2 E2E 测试脚本示例

#### 场景 1: 完整录制流程

```kotlin
// e2e-tests/src/androidTest/java/E2ERecordingFlowTest.kt
@RunWith(AndroidJUnit4::class)
class E2ERecordingFlowTest {
    
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()
    
    @Inject
    lateinit var mockDevice: Insta360Simulator
    
    @Inject
    lateinit var testBackend: TestBackendClient
    
    @Test
    fun completeRecordingFlow_shouldGenerateCandidates() = runTest {
        // === Phase 1: 设备连接 ===
        composeTestRule.onNodeWithText("设备管理").performClick()
        composeTestRule.onNodeWithText("开始扫描").performClick()
        
        // 等待模拟设备出现
        composeTestRule.waitUntil(5000) {
            composeTestRule
                .onAllNodesWithText("Insta360 X3 (Simulator)")
                .fetchSemanticsNodes().isNotEmpty()
        }
        
        // 连接设备
        composeTestRule.onNodeWithText("连接").performClick()
        
        // 验证连接成功
        composeTestRule
            .onNodeWithText("已连接")
            .assertIsDisplayed()
        
        // === Phase 2: 创建任务 ===
        composeTestRule.onNodeWithText("任务管理").performClick()
        composeTestRule.onNodeWithText("创建新任务").performClick()
        composeTestRule
            .onNodeWithContentDescription("任务描述")
            .performTextInput("每次喝水")
        composeTestRule.onNodeWithText("保存").performClick()
        
        // 验证任务创建成功
        val taskId = testBackend.getLatestTask().task_id
        assertNotNull(taskId)
        
        // === Phase 3: 开始录制 ===
        composeTestRule.onNodeWithText("开始录制").performClick()
        
        // 模拟录制 5 分钟的视频
        mockDevice.simulateRecording(
            videoFile = File("tests/fixtures/videos/dog_drinking_water.mp4")
        )
        
        // 验证会话创建
        advanceTimeBy(1000)
        val session = testBackend.getLatestSession()
        assertEquals(taskId, session.task_id)
        
        // === Phase 4: 接收候选通知 ===
        val candidates = mutableListOf<Candidate>()
        val job = launch {
            testBackend.observeCandidates(session.session_id).collect {
                candidates.add(it)
            }
        }
        
        // 等待候选生成（模拟 AI 检测）
        advanceTimeBy(10000)
        
        // 验证至少生成 1 个候选
        assertTrue(candidates.isNotEmpty())
        assertEquals("pending", candidates.first().status)
        
        // === Phase 5: 确认候选 ===
        composeTestRule.onNodeWithText("查看候选").performClick()
        composeTestRule.onNodeWithText("确认").performClick()
        
        // 验证候选状态更新
        advanceTimeBy(2000)
        val confirmedCandidate = testBackend.getCandidate(candidates.first().id)
        assertEquals("confirmed", confirmedCandidate.status)
        
        // === Phase 6: 停止录制 ===
        composeTestRule.onNodeWithText("停止录制").performClick()
        
        // 验证会话结束
        val finalSession = testBackend.getSession(session.session_id)
        assertNotNull(finalSession.end_time)
        
        job.cancel()
    }
}
```

#### 场景 2: 用户纠正流程

```python
# e2e-tests/test_feedback_flow.py
@pytest.mark.e2e
@pytest.mark.asyncio
async def test_user_correction_updates_candidates(
    test_client, 
    mock_model_l2,
    test_video_segment
):
    # === Phase 1: 创建任务和会话 ===
    task = await test_client.post("/api/v1/tasks", json={
        "description": "每次把玩具放在脚边"
    })
    task_id = task.json()["task_id"]
    
    session = await test_client.post("/api/v1/sessions", json={
        "task_id": task_id,
        "recording_id": "rec-test-001"
    })
    session_id = session.json()["session_id"]
    
    # === Phase 2: 提交候选（初始误判） ===
    # 模拟 AI 误判：实际符合但标记为不符合
    mock_model_l2.set_scenario("false_negative")
    
    candidate = await test_client.post("/api/v1/candidates", json={
        "session_id": session_id,
        "preview_timestamp": 125.5,
        "frames": [test_video_segment]
    })
    candidate_id = candidate.json()["candidate_id"]
    
    # 验证初始状态为不符合
    candidate_data = await test_client.get(f"/api/v1/candidates/{candidate_id}")
    assert candidate_data.json()["符合"] == False
    
    # === Phase 3: 用户纠正 ===
    feedback = await test_client.post("/api/v1/feedback", json={
        "candidate_id": candidate_id,
        "correction_type": "judgment_error",  # 画面判断错误
        "correct_label": True,  # 实际符合
        "comment": "狗确实把玩具放在了脚边，只是角度遮挡"
    })
    
    assert feedback.status_code == 200
    
    # === Phase 4: 验证效果 ===
    # 4.1 反例应被记录
    examples = await test_client.get(f"/api/v1/tasks/{task_id}/examples")
    negative_examples = [e for e in examples.json() if e["label"] == False]
    assert len(negative_examples) > 0
    
    # 4.2 相似候选应被重新评估（使用新的反例上下文）
    mock_model_l2.set_scenario("with_counter_example")
    
    # 提交相似的候选
    similar_candidate = await test_client.post("/api/v1/candidates", json={
        "session_id": session_id,
        "preview_timestamp": 180.0,
        "frames": [test_video_segment]  # 相同场景
    })
    
    # 验证新候选被正确判断
    similar_data = await test_client.get(
        f"/api/v1/candidates/{similar_candidate.json()['candidate_id']}"
    )
    assert similar_data.json()["符合"] == True  # 纠正后判断正确
```

---

## 5. CI/CD 集成

### 5.1 GitHub Actions 工作流

```yaml
# .github/workflows/e2e-tests.yml
name: E2E Tests

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  android-e2e:
    runs-on: macos-latest  # 需要硬件加速
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
      
      - name: Start Android Emulator
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 33
          target: google_apis
          arch: x86_64
          script: |
            adb wait-for-device
            adb shell settings put global window_animation_scale 0
            adb shell settings put global transition_animation_scale 0
            adb shell settings put global animator_duration_scale 0
      
      - name: Run E2E Tests
        run: ./gradlew :app:connectedAndroidTest
      
      - name: Upload Test Reports
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: android-test-reports
          path: app/build/reports/androidTests/
  
  backend-e2e:
    runs-on: ubuntu-latest
    
    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_PASSWORD: test
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up Python 3.11
        uses: actions/setup-python@v4
        with:
          python-version: '3.11'
      
      - name: Install dependencies
        run: |
          pip install -r requirements.txt
          pip install pytest pytest-asyncio httpx
      
      - name: Run Backend E2E Tests
        run: |
          pytest tests/e2e/ -v --tb=short
        env:
          DATABASE_URL: postgresql://postgres:test@localhost/test_db
          MODEL_MOCK_MODE: replay
      
      - name: Upload Coverage
        uses: codecov/codecov-action@v3
        with:
          files: ./coverage.xml
```

---

## 6. 测试数据管理

### 6.1 测试数据隔离

```python
# tests/conftest.py
import pytest
from sqlalchemy import create_engine
from app.database import Base

@pytest.fixture(scope="function")
async def test_db():
    """每个测试使用独立的数据库实例"""
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    
    yield engine
    
    Base.metadata.drop_all(engine)
    engine.dispose()

@pytest.fixture
async def test_user(test_db):
    """创建测试用户"""
    user = User(
        id="test-user-001",
        device_id="android-test",
        created_at=datetime.now()
    )
    test_db.add(user)
    await test_db.commit()
    return user
```

### 6.2 测试数据版本控制

```
tests/fixtures/
├── v1.0/
│   ├── videos/              # 初始版本测试视频
│   └── expected/            # 预期结果
│
├── v1.1/
│   ├── videos/              # 新功能测试视频
│   └── expected/            # 更新后的预期结果
│
└── .gitattributes           # Git LFS 管理大文件
```

---

## 7. 性能和负载测试

### 7.1 压力测试场景

```python
# tests/performance/locustfile.py
from locust import HttpUser, task, between

class OneWolfUser(HttpUser):
    wait_time = between(1, 3)
    
    def on_start(self):
        """登录并创建任务"""
        response = self.client.post("/api/v1/auth/login", json={
            "device_id": f"load-test-{self.environment.runner.user_count}"
        })
        self.token = response.json()["access_token"]
        self.headers = {"Authorization": f"Bearer {self.token}"}
        
        # 创建任务
        task_response = self.client.post("/api/v1/tasks", 
            json={"description": "每次喝水"},
            headers=self.headers
        )
        self.task_id = task_response.json()["task_id"]
    
    @task(3)
    def create_session(self):
        """创建会话（高频操作）"""
        self.client.post("/api/v1/sessions", 
            json={
                "task_id": self.task_id,
                "recording_id": f"rec-{random.randint(1000, 9999)}"
            },
            headers=self.headers
        )
    
    @task(10)
    def submit_candidate(self):
        """提交候选（最高频操作）"""
        self.client.post("/api/v1/candidates",
            json={
                "session_id": self.session_id,
                "preview_timestamp": random.uniform(0, 300),
                "frames": ["base64_frame_data"]
            },
            headers=self.headers
        )
    
    @task(2)
    def get_candidates(self):
        """查询候选列表"""
        self.client.get(f"/api/v1/sessions/{self.session_id}/candidates",
            headers=self.headers
        )
```

**运行**:
```bash
locust -f tests/performance/locustfile.py --host=http://localhost:8000
```

**目标**:
- **吞吐量**: 100 req/s (候选提交)
- **响应时间**: P95 < 500ms (候选提交), P95 < 2s (AI 初步复核)
- **并发用户**: 50 用户同时录制

---

## 8. 测试执行计划

### 8.1 开发阶段测试

```bash
# 每次 commit 前
make test-unit          # 单元测试 (~2 分钟)
make test-lint          # 代码检查

# 每次 PR 前
make test-integration   # 集成测试 (~10 分钟)
make test-e2e-quick     # 快速 E2E (~5 分钟, 仅关键路径)

# 发布前
make test-e2e-full      # 完整 E2E (~30 分钟)
make test-performance   # 性能测试 (~15 分钟)
```

### 8.2 测试覆盖率目标

| 模块 | 单元测试 | 集成测试 | E2E 测试 |
|------|---------|---------|---------|
| Model (数据类) | 100% | - | - |
| Repository | 90% | 80% | - |
| ViewModel | 95% | - | 关键路径 |
| UI (Composable) | - | 80% | 关键路径 |
| API 端点 | 100% | 100% | 关键路径 |
| AI 流水线 | 90% | 80% | 全覆盖 |

---

## 9. 常见问题和解决方案

### Q1: E2E 测试运行太慢
**问题**: 完整 E2E 套件需要 30+ 分钟

**解决方案**:
1. **并行执行**: 使用 `pytest-xdist` 并行运行测试
2. **分层运行**: 区分冒烟测试 (5分钟) vs 完整测试 (30分钟)
3. **Mock 优化**: 使用录制/回放模式，避免真实 AI 调用

### Q2: 真机设备测试不稳定
**问题**: Insta360 设备蓝牙连接不可靠

**解决方案**:
1. **优先使用 Simulator**: 90% 测试用 Mock 设备
2. **真机测试隔离**: 仅在夜间 CI 中运行真机测试
3. **重试机制**: 真机测试失败自动重试 2 次

### Q3: AI 模型响应不确定
**问题**: 相同输入，不同响应

**解决方案**:
1. **录制模式**: 首次运行记录真实响应
2. **确定性验证**: 使用录制响应进行回归测试
3. **范围断言**: 使用置信度范围而非精确值

---

## 10. 下一步行动

### 立即可做 (P0)
1. ✅ **创建测试目录结构**
2. ✅ **编写 DeviceViewModel 单元测试**
3. ✅ **创建 Insta360Simulator Mock**
4. ✅ **编写首个 E2E 测试脚本**

### 短期 (P1)
1. 📝 **实现 API 集成测试**
2. 📝 **完善本地测试脚本**
3. 📝 **准备测试数据 fixtures**
4. 📝 **Mock AI 模型响应**

### 长期 (P2)
1. 📝 **性能和负载测试**
2. 📝 **真机设备测试自动化**
3. 📝 **视觉回归测试（截图对比）**
4. 📝 **混沌工程测试**

---

**文档版本**: v1.0  
**创建时间**: 2026-09-18  
**维护者**: QA Team  
**更新频率**: 每个 Sprint
