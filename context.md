# 它的一天 OneDay · 项目上下文

## Current State
```yaml
phase: Phase 2 - Android 模块开发与测试框架 (已完成)
current_tasks:
  - 测试框架搭建 (已完成, Claude Opus 5)
completed_deliverables:
  - ARCHITECTURE.md: 系统架构设计
  - AI_PIPELINE.md: AI 流水线设计
  - API_SPEC.md: API 规范
  - DB_SCHEMA.sql: 数据库 Schema
  - feature-device 模块: Android 设备连接 UI (已完成)
  - 端到端测试框架: 完整测试策略和实现 (已完成)
    - E2E_TESTING_STRATEGY.md: 测试策略文档
    - TESTING_QUICKSTART.md: 快速启动指南
    - DeviceViewModelTest.kt: 单元测试 (12个用例)
    - Insta360Simulator.kt: 设备模拟器
    - DeviceConnectionE2ETest.kt: E2E测试 (7个场景)
    - GitHub Actions CI/CD: 自动化测试流水线
blockers: 
  - WSL 环境 Build Tools 问题 (临时方案: Windows 环境构建)
  - 缺少测试依赖配置 (待添加到 build.gradle.kts)
next_phase: Phase 3 - 真实设备集成与后端开发
last_update: 2026-09-18 (测试框架搭建完成)
```

## Phase 1 交付物总结

### ARCHITECTURE.md
- **系统概览**: 五层架构（用户交互层、硬件设备层、服务层、AI层、存储层）
- **核心子系统**: 
  - 录制与预览子系统（录像与分析分离）
  - 任务定义与校验子系统（自然语言 → 结构化任务）
  - 事件检测与状态机子系统（渐进式确定性）
  - 时间同步子系统（多时钟域映射）
  - 用户纠正与学习子系统（区分标准错误 vs 判断错误）
- **数据流设计**: 录制阶段、事件检测、用户纠正三大流程
- **并发模型**: Kotlin Coroutines，录制/分析/复核/监控四协程分离
- **性能目标**: 预览延迟 <500ms，候选生成 <2s，原片复核 <10s
- **开发优先级**: 8周开发计划，P0/P1 功能分级

### AI_PIPELINE.md
- **三层检测架构**: 
  - L1: 端侧轻量检测 (MediaPipe, <50ms, 零成本)
  - L2: 云端初步复核 (VLM, <2s, 中成本)
  - L3: 云端原片复核 (VLM, <10s, 高成本)
- **触发器设计**: 接近与停留、交接动作、周期抽样
- **任务理解模块**: 自然语言解析、范围校验、不支持拒绝
- **示例学习模块**: 正反例存储、检索组织、效果追踪
- **质量检查**: 画面质量检查（模糊/亮度/遮挡）、连续性检查
- **成本优化**: 智能批处理、帧复用、分级模型策略
- **成本估算**: ~$0.01/会话 (5分钟)

### API_SPEC.md
- **REST API**: 认证、设备管理、任务管理、会话管理、事件检测、反馈、导出
- **WebSocket**: 实时候选通知、状态更新
- **核心端点**:
  - POST /api/v1/tasks - 创建任务
  - POST /api/v1/sessions - 创建会话
  - POST /api/v1/candidates - 提交候选
  - POST /api/v1/feedback - 用户纠正
  - POST /api/v1/exports - 导出视频
- **时间同步 API**: 记录同步事件、查询映射、转换时间戳
- **统计分析 API**: 会话统计、任务效果追踪
- **错误处理**: 13种错误码，统一响应格式
- **速率限制**: 分端点限流，防止滥用

### DB_SCHEMA.sql
- **核心表**: 13张主表 + 3张统计表 + 1张日志表
  - users, devices: 用户与设备管理
  - tasks (多版本), task_examples: 任务定义与示例
  - sessions, time_mappings: 会话与时间同步
  - candidates, review_results: 候选与复核
  - user_feedback: 纠正反馈
  - exports: 导出管理
  - session_stats, task_effectiveness: 统计分析
- **设计原则**: 版本控制、时间映射、状态机、审计追踪
- **视图**: active_sessions, pending_candidates, user_tasks_overview
- **触发器**: 自动更新候选计数、确认计数、统计数据
- **索引优化**: 18个索引，支持高频查询

## Decisions Log

### 2026-09-18 | Phase 1 架构设计决策

#### 决策1: 录像与分析分离
- **决策者**: Overseer (Claude Opus 5)
- **背景**: 产品需求强调"原片保存可靠性优先"
- **决策**: 录制链路独立，分析失败不影响原片保存
- **影响**: 
  - 录制状态由相机 SDK 确认
  - 分析管道使用预览流，允许延迟和失败
  - 降级策略清晰：分析错误 → 标记"待确认"

#### 决策2: 三层 AI 流水线
- **决策者**: Overseer (Claude Opus 5)
- **背景**: 需要平衡成本、延迟、准确率
- **决策**: L1端侧(零成本) → L2云端初判(低成本) → L3原片复核(高成本)
- **影响**: 
  - 99% 帧在本地处理
  - 成本从 ~$0.50/会话 降至 ~$0.01/会话
  - 用户感知：即时候选 → 快速复核 → 最终确认

#### 决策3: 时间同步机制
- **决策者**: Overseer (Claude Opus 5)
- **背景**: 多时钟域（手机、预览、按钮、原片）需要映射
- **决策**: 使用可见同步事件（录制开始）建立映射，分段处理
- **影响**: 
  - 所有时间戳保留原始值
  - 事件可准确跳转到原片位置（±0.5s）
  - 停录/重启创建新映射段

#### 决策4: 纠正分类机制
- **决策者**: Overseer (Claude Opus 5)
- **背景**: 用户纠正有两种性质
- **决策**: 区分"标准理解错误"和"画面判断错误"
- **影响**: 
  - 标准错误 → 生成新任务版本 → 重新评估所有候选
  - 判断错误 → 添加反例 → 不修改任务定义
  - 避免混淆，追踪效果改善

#### 决策5: 任务多版本控制
- **决策者**: Overseer (Claude Opus 5)
- **背景**: 任务定义会随用户纠正而演化
- **决策**: 每次修改生成新版本，保留历史
- **影响**: 
  - 可追溯任务演化过程
  - 示例按版本分组
  - 效果对比（v1 vs v2）

#### 决策6: 渐进式确定性
- **决策者**: Overseer (Claude Opus 5)
- **背景**: 避免误导用户，明确告知不确定性
- **决策**: 候选 → 初步符合 → 原片复核 → 已确认
- **影响**: 
  - 用户看到状态变化
  - 不确定时明确标记"待确认"
  - 遮挡/模糊 → 降级而非拒绝

## Escalation Queue
（当前无升级请求）

## Review Queue
- Phase 1 交付物 → 等待用户审查

## History
- 2026-09-18 16:45: 端到端测试框架搭建完成 (Claude Opus 5)
  - ✅ 测试策略文档:
    - E2E_TESTING_STRATEGY.md: 完整测试金字塔架构 (60% Unit / 30% Integration / 10% E2E)
    - TESTING_QUICKSTART.md: 快速启动指南
    - TESTING_DELIVERY_SUMMARY.md: 交付总结
  - ✅ 单元测试实现:
    - DeviceViewModelTest.kt: 12个测试用例，覆盖所有 ViewModel 核心功能
    - 使用 MockK + Coroutines Test + Turbine
  - ✅ 测试基础设施:
    - Insta360Simulator.kt: 完整设备模拟器，支持扫描/连接/断开/故障注入
    - Hilt 测试模块配置
  - ✅ E2E 测试实现:
    - DeviceConnectionE2ETest.kt: 7个端到端测试场景
    - 覆盖完整连接流程、错误处理、UI 交互
  - ✅ CI/CD 配置:
    - .github/workflows/tests.yml: GitHub Actions 自动化测试流水线
    - 6个 Job: 单元测试、Lint、E2E测试、构建、覆盖率、测试总结
  - 📝 待添加: 测试依赖到 build.gradle.kts
  - 📝 待实现: Repository 集成测试（真实蓝牙）
  
- 2026-09-18 14:30: feature-device 模块完成 (Claude Opus 5)
  - ✅ 创建 feature-device Gradle 模块
  - ✅ 实现 MVVM 架构 (Model, Repository, ViewModel, UI)
  - ✅ 完成 Compose UI 界面：
    - DeviceScreen: 主界面（扫描、连接、管理）
    - DeviceListItem: 设备列表项
    - ConnectedDeviceCard: 已连接设备卡片
    - ScanningIndicator, EmptyDeviceList, ErrorBanner
  - ✅ 状态管理: DeviceUiState, ConnectionStatus
  - ✅ 依赖注入准备: Hilt 配置
  - ✅ 权限声明: AndroidManifest.xml (蓝牙、位置)
  - ✅ 导出接口: DeviceModule.getDeviceScreen()
  - ✅ Compose 预览: DeviceScreenPreviews.kt
  - ✅ 文档: feature-device/README.md, QUICKSTART.md, IMPLEMENTATION.md
  - 📝 待实现: DeviceRepository 真实蓝牙通信（需 Insta360 SDK）
  - ⚠️  构建问题: WSL 环境 Build Tools 36.0.0 缺少 Linux aapt
  
- 2026-09-18 上午: Phase 1 完成，Overseer (Claude Opus 5) 完成架构设计
  - 交付 ARCHITECTURE.md (系统架构)
  - 交付 AI_PIPELINE.md (AI 流水线)
  - 交付 API_SPEC.md (API 规范)
  - 交付 DB_SCHEMA.sql (数据库 Schema)
  - 核心设计：录像分析分离、三层AI、时间同步、纠正分类

## Next Steps (Phase 3 - 真实设备集成)

### 立即可做 (优先级 P0)
1. **添加测试依赖到 build.gradle.kts**
   ```kotlin
   // feature-device/build.gradle.kts
   dependencies {
       testImplementation("junit:junit:4.13.2")
       testImplementation("io.mockk:mockk:1.13.8")
       testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
       testImplementation("app.cash.turbine:turbine:1.0.0")
       androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.5.4")
       androidTestImplementation("com.google.dagger:hilt-android-testing:2.48")
   }
   ```
   
2. **运行单元测试验证框架**
   ```bash
   ./gradlew :feature-device:test
   ```

3. **获取 Insta360 SDK**
   - 注册开发者账号
   - 下载 SDK 和文档
   - 配置 Maven 仓库

### 短期任务 (本周, P0)
1. **实现 DeviceRepositoryImpl**
   - 集成 Insta360 SDK
   - 实现真实蓝牙扫描和连接
   - 替换测试中的 Simulator

2. **配置 Hilt 依赖注入**
   - 创建 DeviceModule (di/)
   - 提供 DeviceRepository 实例
   - 在 MainActivity 中初始化 Hilt

3. **集成到主 app 导航**
   - 在 NavGraph 中添加设备页面路由
   - 从主页导航到设备管理
   - 请求蓝牙权限

4. **真机测试**
   - 在 Android 设备上安装 APK
   - 测试蓝牙扫描和连接
   - 验证 E2E 测试场景

### 中期规划 (P1)
1. **后端开发** (Worker: DeepSeek V4-Pro)
   - 实现 FastAPI 后端服务
   - 实现任务管理 API
   - 实现会话管理 API
   - PostgreSQL 数据库集成

2. **录制功能** (Worker: Claude Sonnet 5)
   - 实现 RecordingFragment
   - 集成 Insta360 录制 API
   - 实现预览流显示
   - 时间同步事件记录

3. **AI 流水线集成** (Worker: Claude Opus 5)
   - 端侧 MediaPipe 检测
   - VLM API 调用封装
   - 事件检测状态机

### 长期目标 (P2)
1. 完整功能实现
2. 性能优化和测试
3. 生产环境部署
4. 用户反馈迭代

---

## 测试框架使用指南

### 快速开始
```bash
# 运行单元测试（2分钟）
make test-unit

# 运行 E2E 测试（需模拟器，5分钟）
make test-e2e

# 快速冒烟测试（3分钟）
make test-smoke

# 完整测试套件（10分钟）
make test-all
```

### 文档索引
- **完整测试策略**: [E2E_TESTING_STRATEGY.md](E2E_TESTING_STRATEGY.md)
- **快速启动指南**: [TESTING_QUICKSTART.md](TESTING_QUICKSTART.md)
- **交付总结**: [TESTING_DELIVERY_SUMMARY.md](TESTING_DELIVERY_SUMMARY.md)

### CI/CD 状态
- ✅ GitHub Actions 配置完成
- ⏳ 待首次运行验证
- 📝 待添加测试状态徽章到 README
