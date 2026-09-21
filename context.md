# 它的一天 OneDay · 项目上下文

## Current State
```yaml
phase: Phase 3 设备联调准备 + Phase 4 后端本地 MVP（并行推进）
current_tasks:
  - 验证 Insta360 真机扫描、连接、断开及权限异常流程
  - Android 接入后端任务、会话、候选与反馈 API
  - 补齐 Android ViewModel、Repository 和设备连接 E2E 测试
  - 同步 API_SPEC.md、DB_SCHEMA.sql 与后端实际实现
completed_deliverables:
  - 架构与 AI 流水线设计文档（设计目标不等于已实现能力）
  - Android 设备管理 Compose UI、MVVM、StateFlow
  - Insta360 Camera/Media SDK 2.1.5 依赖和 Application 初始化
  - DeviceRepositoryImpl 的 BLE 扫描、连接、断开及设备信息读取代码
  - MainActivity 设备页面接入、运行时权限请求及 PermissionHelper
  - FastAPI 本地 MVP、开发 JWT 登录、资源所有者校验
  - 任务规则解析与版本管理、会话与时间映射、候选与反馈 API
  - SQLAlchemy 模型、11 张业务表、Alembic 初始迁移及 PostgreSQL 离线 SQL
  - VLM 兼容协议适配、持久化 Worker 队列、重试与租约恢复
  - 反馈正反例复用、人工确认与审计日志
validation:
  - Android 既有本地报告为 3 个 JVM 单元测试通过（本次未重新运行）
  - Android 有本地 Debug APK；真机与仪器测试未验证
  - 后端 31 个测试用例本次全部通过（含 SQLite 迁移与 PostgreSQL 离线 SQL）
  - 后端 Ruff 静态检查通过
open_items:
  - ViewModel 测试、Insta360Simulator、设备连接 E2E 实现当前均缺失
  - MockK、Coroutines Test、Turbine、Compose UI Test 等依赖尚未配置
  - 真实 VLM 服务、PostgreSQL 实例及 Android 与后端联调尚未验证
  - 根目录 API/SQL 文档仍为旧设计；backend/.env.example 当前缺失
next_milestone: 手机与相机实测 + Android 与后端最小业务闭环
last_update: 2026-09-19（按当前仓库代码与本地验证证据核对）
```

## 实现与验证基线（2026-09-19）

本次以提交 `4087347` 及工作区文件为依据。阶段并行推进，不再以“Phase 2 全部完成、后端尚未开始”描述当前进展。

| 范围 | 已有实现 / 证据 | 尚未完成或未验证 |
|------|-----------------|------------------|
| Android 设备连接 | `DeviceModule.initialize()` 初始化两个 SDK；`DeviceRepositoryImpl` 调用真实 BLE API；主界面已接入 | 手机与相机实测、断连恢复、权限拒绝后的完整交互；无录制/预览实现 |
| Android 测试 | `app` 的示例测试 1 个；`feature-device` 的示例测试与 `DeviceInfoTest` 各 1 个 | ViewModel、Repository、Compose 交互及完整 E2E；两个仪器测试仅检查应用包名 |
| 后端业务 | 开发登录、设备同步、任务创建/更新/历史版本、会话开始/停止、候选查询/补传/反馈/保留/忽略、复核任务与审计查询 | Android HTTP 接入、WebSocket、生产身份认证、素材上传与视频导出 |
| 数据库 | `backend/migrations/versions/0001_backend_initial_backend_schema.py` 定义 11 张业务表；附 PostgreSQL SQL | PostgreSQL 实例迁移和并发验证；根目录 `DB_SCHEMA.sql` 尚未同步为生成基线 |
| AI 与反馈 | `rules-v1` 支持喝水、玩具交接、接近停留、跳跃；VLM 适配器与 Worker；同版本最多 4 个正反例参与提示 | 真实模型效果/成本验证、L1 端侧检测、原片复核；默认 VLM 关闭，候选转 `needs_review` |

后端预览匹配最多进入 `preliminary_match`，`confirmed` 目前仅来自人工确认。标准纠正只重评当前候选（`affected_candidates=1`）；正反例复用属于提示示例学习。后端校验客户端去人声明，尚未实现或验证去人算法。

### 本次验证记录

- 已核对 Android 既有 XML 报告：2026-09-19 15:11（Asia/Shanghai）共 3 个 JVM 测试通过，0 失败；本次未重跑 Gradle，不能据此认定真机功能通过。
- 工作区存在 `app/build/outputs/apk/debug/app-debug.apk`，仅说明已有本地构建产物。
- 在 `backend` 目录执行 `./.venv/bin/python -m pytest --collect-only -q -p no:cacheprovider`：收集 31 个用例。
- 在 `backend` 目录执行 `./.venv/bin/python -m ruff check . --no-cache`：通过。
- 后端 `pytest -q -p no:cacheprovider -o faulthandler_timeout=30`：**31 passed, 2 warnings，1.39s**；警告来自 Starlette/HTTPX 和 AnyIO 的弃用提示。测试覆盖 API、Worker、SQLite 迁移/回滚与 PostgreSQL 离线 SQL，未调用真实 VLM 或 PostgreSQL 实例。
- 环境说明：本次使用已有 Python 3.14.4 虚拟环境；受限沙箱内 TestClient 启动等待超时，获准在沙箱外重跑后通过。
- GitHub Actions 自动检测流已移除；未发现 Makefile，使用下方实际命令手动验证。

## Phase 1 交付物总结

以下为设计阶段交付范围。完整 API、数据表、性能和成本指标属于设计目标；当前实现范围以上方基线及 `backend/oneday/`、Alembic 迁移为准。

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
- 2026-09-19: 根据当前代码更新开发进度
  - SDK 2.1.5、BLE Repository、Application 初始化及运行时权限请求已落地，进入真机验收阶段。
  - 后端本地 MVP 已提交：FastAPI、数据库迁移、规则解析、VLM 适配、Worker、反馈示例学习及测试。
  - 本次后端 31 个测试全部通过，Ruff 通过；Android 核对既有 3 个单元测试通过报告，未重跑真机或 Gradle 测试。
  - 修正“12 个 ViewModel 测试 + 7 个 E2E 场景已交付”的旧记录；当前仓库没有对应实现。
  - 区分设计文档、已有代码、本地报告和本次验证，补充 API/SQL 文档不同步及联调待办。

- 2026-09-19: 移除 CI/CD 自动检测流
  - 删除 GitHub Actions 测试工作流
  - 保留测试代码、测试策略和本地运行方式

- 2026-09-18 16:45: 端到端测试框架搭建完成 (Claude Opus 5)
  - **2026-09-19 核对说明**：以下保留为历史记录；所列 ViewModel 测试、Simulator、E2E 文件当前不在仓库中，不能作为现有交付依据。
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
    - GitHub Actions 自动化测试流水线（已于 2026-09-19 移除）
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

## Next Steps（设备验收与前后端联调）

### P0：最小闭环
1. **真机验收**：用 Android 手机与 Insta360 相机验证扫描、连接、信息读取、断开及重复连接，覆盖权限拒绝、蓝牙关闭和连接中断。
2. **补齐 Android 测试**：为当前 `DeviceViewModel` 和 Repository 接口增加测试与所需依赖，补充设备连接 UI/E2E。当前通过构造参数提供 Repository，未接入 Hilt。
3. **同步联调契约**：核对 `backend/oneday/main.py` 与 `schemas.py`，更新根目录 API/SQL 文档，补齐 `.env.example`；建库始终使用 Alembic。
4. **Android 接入后端**：实现任务确认、会话、候选提交、状态查询和反馈页面/网络调用，跑通客户端到 Worker 的闭环。

### P1：录制与 AI 验证
1. **录制和预览**：接入相机录制控制、预览流、同步事件及原片定位，验证停止录制后的补传。
2. **AI 流水线**：实现 L1 检测，验证真实 VLM 的质量、延迟和成本，再实现原片复核；保留不确定结果供人工处理。
3. **服务完善**：验证 PostgreSQL 在线迁移与并发，补充 WebSocket、素材上传及导出。

### P2：上线准备
1. 生产身份认证、TLS、限流与素材授权策略。
2. 手机后台保活、长时间录制、功耗和恢复能力验证。
3. 产品完整 Demo、统计分析、性能测试与部署方案。

---

## 测试框架使用指南

### 快速开始
```bash
# 仓库根目录：Android JVM 单元测试
./gradlew :app:testDebugUnitTest :feature-device:testDebugUnitTest

# 仓库根目录：现有仪器测试（仅应用包名检查，需模拟器或手机）
./gradlew :app:connectedDebugAndroidTest :feature-device:connectedDebugAndroidTest

# 后端（先按 backend/README.md 准备虚拟环境与依赖）
cd backend
./.venv/bin/python -m pytest -q
./.venv/bin/python -m ruff check .
```

Windows 使用 `gradlew.bat` 和 `.venv\Scripts\python`。旧测试文档中的 Makefile、Hilt 测试模块和模拟器示例均不代表当前已配置能力。

### 文档索引
- **完整测试策略**: [E2E_TESTING_STRATEGY.md](E2E_TESTING_STRATEGY.md)
- **快速启动指南**: [TESTING_QUICKSTART.md](TESTING_QUICKSTART.md)
- **交付总结**: [TESTING_DELIVERY_SUMMARY.md](TESTING_DELIVERY_SUMMARY.md)

### CI/CD 状态
- ℹ️ 未启用自动化检测流
- 📝 测试需在本地手动运行
