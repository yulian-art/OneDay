# 它的一天 OneDay

> AI 辅助的宠物行为记录与视频剪辑应用

[![架构文档](https://img.shields.io/badge/docs-complete-blue)]()
[![开发阶段](https://img.shields.io/badge/phase-设备联调与后端MVP-yellow)]()

---

## 📖 项目概述

**OneDay** 是一款创新的宠物行为记录应用，通过结合 **Insta360 全景相机**和**多层 AI 视觉检测**，帮助用户轻松记录宠物日常生活中的关键时刻。

### 产品目标（完整链路尚未实现）
- 🎥 **智能录制**: 使用 Insta360 相机全天候录制
- 🤖 **AI 检测**: 自动识别用户定义的行为（如"每次喝水"）
- ⚡ **实时候选**: 录制过程中实时推送候选事件
- 🎬 **一键剪辑**: 自动生成精彩片段的视频剪辑
- 📊 **数据统计**: 分析宠物行为频率和模式

### 架构设计目标
- **三层 AI 架构**: 端侧检测 + 云端复核 + 原片验证
- **成本优化**: 设计估算 ~$0.01/会话（5分钟），尚未通过真实模型和素材验证
- **渐进式确定性**: 明确告知用户每个阶段的确定性
- **时间同步**: 多时钟域精确映射，支持事件跳转

---

## 🏗️ 项目状态

截至 **2026-09-19**，项目已具备 Android 设备连接代码和后端本地 MVP，正在准备真机验收与前后端联调。详细证据、验证记录和优先级见 [context.md](context.md)。

### Phase 1: 架构设计 ✅（文档已交付）
- [x] 系统架构设计
- [x] AI 流水线设计
- [x] API 与数据库初始设计
- [ ] 根目录 `API_SPEC.md`、`DB_SCHEMA.sql` 与后端 MVP 同步

### Phase 2: Android 模块与测试 🔄（界面已实现，业务测试待补齐）
- [x] feature-device 模块实现（MVVM 架构）
- [x] Jetpack Compose UI（Material Design 3）
- [x] 主 app 接入设备页面与运行时权限请求
- [x] 3 个 JVM 单元测试：2 个模板测试、1 个设备信息模型测试
- [ ] ViewModel、Repository、Compose 交互与设备连接 E2E 测试

### Phase 3: 真实设备集成 🔄（SDK 已接入，真机待验证）
- [x] Insta360 Camera/Media SDK 2.1.5 依赖与初始化
- [x] BLE 扫描、连接、断开及型号/固件/电量读取代码
- [ ] 真机验证、断连恢复和权限异常流程
- [ ] 相机录制控制、预览流与同步事件采集

### Phase 4: 后端与 AI 🔄（本地 MVP 已实现）
- [x] FastAPI、开发 JWT 登录、资源所有者校验
- [x] 任务规则解析与版本管理、会话、候选、反馈及审计 API
- [x] SQLAlchemy、SQLite/Alembic 迁移、PostgreSQL 离线 SQL
- [x] VLM 兼容协议适配、Worker 队列、重试和反馈示例复用
- [x] 后端 API、Worker、迁移测试代码（31 个用例）
- [ ] Android 与后端联调、真实 VLM 和 PostgreSQL 实例验证
- [ ] WebSocket 实时通知
- [ ] L1 端侧检测、原片复核、素材上传、视频导出及生产部署

默认 VLM 关闭，候选保留为 `needs_review`；预览匹配最多到 `preliminary_match`，当前 `confirmed` 仅由人工确认产生。

---

## 📚 文档索引

### 架构与设计
| 文档 | 描述 | 状态 |
|------|------|------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 完整系统架构，包括五层架构、核心子系统、数据流设计 | ✅ 完成 |
| [AI_PIPELINE.md](AI_PIPELINE.md) | 三层 AI 检测架构、成本优化策略、质量检查机制 | ✅ 完成 |
| [API_SPEC.md](API_SPEC.md) | 初始 API 设计，部分端点与字段尚未同步到 MVP | 🔄 待同步 |
| [DB_SCHEMA.sql](DB_SCHEMA.sql) | 初始数据库设计，不作为当前建库入口 | 🔄 待同步 |

### 后端开发
| 文档 / 代码 | 描述 | 状态 |
|-------------|------|------|
| [backend/README.md](backend/README.md) | 本地启动、VLM 配置、实现范围与限制 | ✅ MVP 说明 |
| [backend/oneday/main.py](backend/oneday/main.py) / [schemas.py](backend/oneday/schemas.py) | 当前 API 路由与输入输出定义；运行后可访问 `/docs` | ✅ 已实现 |
| [backend/migrations/](backend/migrations/) | 11 张业务表的 Alembic 迁移，当前建库入口 | ✅ 已实现 |
| [backend/docs/schema-postgresql.sql](backend/docs/schema-postgresql.sql) | PostgreSQL 离线 SQL | ⏳ 待实例验证 |

### Android 开发
| 文档 | 描述 | 状态 |
|------|------|------|
| [feature-device/README.md](feature-device/README.md) | 设备模块详细文档，包括架构、API、使用方法 | ✅ 完成 |
| [feature-device/QUICKSTART.md](feature-device/QUICKSTART.md) | 早期集成示例，包含旧 Hilt/SDK 占位方案 | 🔄 待同步 |
| [feature-device/IMPLEMENTATION.md](feature-device/IMPLEMENTATION.md) | 早期实现记录，当前进度以模块 README 为准 | 📁 历史参考 |

### 测试文档
| 文档 | 描述 | 状态 |
|------|------|------|
| [E2E_TESTING_STRATEGY.md](E2E_TESTING_STRATEGY.md) | 测试策略与目标，非当前覆盖率报告 | 📝 方案 |
| [TESTING_QUICKSTART.md](TESTING_QUICKSTART.md) | 当前测试命令入口及早期扩展示例 | 🔄 部分示例待落地 |
| [TESTING_DELIVERY_SUMMARY.md](TESTING_DELIVERY_SUMMARY.md) | 当前测试交付核对与历史记录 | 🔄 已核对 |

### 项目管理
| 文档 | 描述 | 状态 |
|------|------|------|
| [context.md](context.md) | 项目当前状态、决策日志、历史记录 | 🔄 持续更新 |
| [COLLABORATION.md](COLLABORATION.md) | 协作流程和角色分工 | ✅ 完成 |
| [WORKFLOW.md](WORKFLOW.md) | 工作流程指南 | ✅ 完成 |

---

## 🚀 快速开始

### 环境要求
- **Android**: 使用兼容项目插件的 Android Studio；Gradle Wrapper 9.5.0、AGP 9.3.2、Kotlin 2.3.20；Gradle Daemon 使用 JDK 21，编译目标 JVM 17
- **Android SDK**: compile/target SDK 35，最低 API 28；app 指定 Build Tools 34.0.0；WSL 使用 Linux 版 SDK
- **设备测试**: Android 模拟器用于仪器测试；真实 BLE 验收需手机与 Insta360 相机
- **后端**: Python 3.11+，本地默认 SQLite；PostgreSQL 可配置，实例验证待完成

### 克隆项目
```bash
git clone https://github.com/yulian-art/OneDay.git
cd OneDay
```

### 运行 Android App
```bash
# 同步依赖
./gradlew --refresh-dependencies

# 构建 Debug APK
./gradlew assembleDebug

# 安装到设备
./gradlew installDebug

# 或直接从 Android Studio 运行
```

### 运行测试
```bash
# Android JVM 单元测试（当前共 3 个）
./gradlew :app:testDebugUnitTest :feature-device:testDebugUnitTest

# 查看测试报告
# app/build/reports/tests/testDebugUnitTest/index.html
# feature-device/build/reports/tests/testDebugUnitTest/index.html

# 现有仪器测试（仅包名检查，需模拟器或手机）
./gradlew :app:connectedDebugAndroidTest :feature-device:connectedDebugAndroidTest

# 后端测试（先按 backend/README.md 准备环境）
cd backend
./.venv/bin/python -m pytest -q
./.venv/bin/python -m ruff check .
```

仓库当前没有 Makefile，旧文档中的 `make test-*` 不可直接使用。后端启动及演示见 [backend/README.md](backend/README.md)。

---

## 🏛️ 架构概览

下图为目标架构；端侧检测、WebSocket、原片复核及统计分析等仍待实现。

```
┌─────────────────────────────────────────────────────────┐
│                    用户交互层                           │
│  Android App (Jetpack Compose + MVVM)                  │
└─────────────────┬───────────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────────────────────┐
│                  硬件设备层                             │
│  Insta360 相机 (BLE + SDK)                             │
│  ├─ 录制控制 (开始/停止)                               │
│  ├─ 预览流 (实时画面)                                  │
│  └─ 原片存储 (SD 卡)                                   │
└─────────────────┬───────────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────────────────────┐
│                    服务层                               │
│  FastAPI 后端 + WebSocket                              │
│  ├─ 任务管理 (自然语言 → 结构化)                       │
│  ├─ 会话管理 (录制生命周期)                            │
│  ├─ 事件检测 (候选生成与复核)                          │
│  └─ 用户纠正 (反馈学习)                                │
└─────────────────┬───────────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────────────────────┐
│                     AI 层                               │
│  三层检测架构                                           │
│  ├─ L1: 端侧检测 (MediaPipe, <50ms, 零成本)            │
│  ├─ L2: 云端初判 (VLM, <2s, 低成本)                    │
│  └─ L3: 原片复核 (VLM, <10s, 高成本)                   │
└─────────────────┬───────────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────────────────────┐
│                   存储层                                │
│  PostgreSQL + 时序数据                                  │
│  ├─ 用户与设备                                          │
│  ├─ 任务定义（多版本）                                  │
│  ├─ 会话与候选                                          │
│  ├─ 时间映射（多时钟域）                                │
│  └─ 统计分析                                            │
└─────────────────────────────────────────────────────────┘
```

---

## 🧪 当前测试基线

| 范围 | 当前代码 | 验证状态 |
|------|----------|----------|
| Android JVM | 2 个模板测试 + `DeviceInfoTest`，共 3 个 | 2026-09-19 既有本地报告全部通过；本次未重跑 |
| Android 仪器测试 | 2 个 `ExampleInstrumentedTest`，仅检查包名 | 本次未运行；设备连接 E2E 尚未实现 |
| 后端 | API、Worker、迁移 3 个测试文件，共 31 个用例 | 本次全部通过；2 条依赖弃用警告，详情见 [context.md](context.md) |
| 后端静态检查 | Ruff | 本次通过 |

当前没有 `DeviceViewModelTest.kt`、`Insta360Simulator.kt` 或 `DeviceConnectionE2ETest.kt`，也没有可核实的覆盖率报告。测试金字塔与覆盖率要求属于后续目标。CI/CD 自动检测流已移除，测试需手动执行。

## 📊 代码规模

截至本次核对，Git 跟踪的 Kotlin 文件 16 个、约 1,100 行；Python 文件 17 个、约 2,287 行（包含迁移、脚本和测试）。统计排除 SDK、构建产物及虚拟环境。

---

## 🛠️ 技术栈

### Android
- **UI**: Jetpack Compose + Material Design 3
- **架构**: MVVM + Repository Pattern
- **依赖提供**: ViewModel 构造参数传入 Repository（默认创建 `DeviceRepositoryImpl`，未接入 Hilt）
- **异步**: Kotlin Coroutines + Flow
- **设备 SDK**: Insta360 Camera/Media SDK 2.1.5
- **测试**: JUnit 4、AndroidX JUnit、Espresso；MockK/Compose Testing 等待补充

### 后端（本地 MVP）
- **框架**: FastAPI、Pydantic、Uvicorn（Python 3.11+）
- **数据库**: SQLAlchemy + Alembic；本地 SQLite，支持配置 PostgreSQL/psycopg
- **认证**: PyJWT；设备 ID 登录仅用于本地开发
- **AI**: 规则解析器 + Chat Completions 兼容 VLM 适配器，默认禁用模型调用
- **异步复核**: 数据库持久化任务队列 + 独立 Worker
- **测试**: pytest、HTTPX MockTransport、Ruff
- **待实现**: WebSocket、Docker 部署配置及生产身份服务

### DevOps
- **本地检查**: Android Gradle 测试/Lint 任务，后端 pytest/Ruff
- **自动化**: GitHub Actions 已移除；Detekt、JaCoCo、Codecov 当前未配置

---

## 📈 开发计划

### 当前里程碑：真机验收与 Android / 后端最小闭环

1. **P0 · 设备验收**：扫描 → 连接 → 读取信息 → 断开，补充权限拒绝、蓝牙关闭和连接中断测试。
2. **P0 · 测试与契约**：补齐 Android 业务测试，同步 API/SQL 设计文档与后端实现，补充后端环境配置模板。
3. **P0 · 前后端联调**：接入任务、会话、候选、复核状态与反馈，完成客户端到 Worker 的闭环。
4. **P1 · 录制与 AI**：实现录制、预览、时间同步、L1 检测；验证真实 VLM，补充原片复核。
5. **P1/P2 · 服务与交付**：验证 PostgreSQL，补充 WebSocket、素材上传/导出、生产认证和完整 Demo。

具体待办及验收边界见 [context.md](context.md)。原周计划已按实际交付调整，后端基础服务与任务 API 不再列为未开始。

---

## 🤝 贡献指南

### 开发流程
1. Fork 项目
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add some amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

### 代码规范
- **Kotlin**: 遵循 [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- **Commit**: 使用 [Conventional Commits](https://www.conventionalcommits.org/)
- **测试**: 所有新功能必须包含单元测试

### 测试目标（尚未全部达到）
- 单元测试覆盖率 > 80%
- 所有 E2E 测试通过
- Lint 检查无错误

---

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情

---

## 📞 联系方式

- **项目作者**: yulian-art
- **项目地址**: https://github.com/yulian-art/OneDay
- **问题反馈**: [GitHub Issues](https://github.com/yulian-art/OneDay/issues)

---

## 🙏 致谢

- **Insta360**: 提供强大的全景相机 SDK
- **Jetpack Compose**: 现代化的 Android UI 工具包
- **Claude AI (Anthropic)**: 项目架构设计与代码实现支持

---

**最后更新**: 2026-09-19

**版本**: v0.2.0-alpha

**状态**: 🟡 开发中
