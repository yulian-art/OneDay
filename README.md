# 它的一天 OneDay

> AI 辅助的宠物行为记录与视频剪辑应用

[![测试状态](https://img.shields.io/badge/tests-passing-brightgreen)]()
[![架构文档](https://img.shields.io/badge/docs-complete-blue)]()
[![开发阶段](https://img.shields.io/badge/phase-2%20完成-yellow)]()

---

## 📖 项目概述

**OneDay** 是一款创新的宠物行为记录应用，通过结合 **Insta360 全景相机**和**多层 AI 视觉检测**，帮助用户轻松记录宠物日常生活中的关键时刻。

### 核心功能
- 🎥 **智能录制**: 使用 Insta360 相机全天候录制
- 🤖 **AI 检测**: 自动识别用户定义的行为（如"每次喝水"）
- ⚡ **实时候选**: 录制过程中实时推送候选事件
- 🎬 **一键剪辑**: 自动生成精彩片段的视频剪辑
- 📊 **数据统计**: 分析宠物行为频率和模式

### 技术亮点
- **三层 AI 架构**: 端侧检测 + 云端复核 + 原片验证
- **成本优化**: ~$0.01/会话（5分钟），相比传统方案降低 50 倍
- **渐进式确定性**: 明确告知用户每个阶段的确定性
- **时间同步**: 多时钟域精确映射，支持事件跳转

---

## 🏗️ 项目状态

### Phase 1: 架构设计 ✅ (已完成)
- [x] 系统架构设计
- [x] AI 流水线设计
- [x] API 规范定义
- [x] 数据库 Schema 设计

### Phase 2: Android 模块与测试 ✅ (已完成)
- [x] feature-device 模块实现（MVVM 架构）
- [x] Jetpack Compose UI（Material Design 3）
- [x] 单元测试框架（12个测试用例）
- [x] E2E 测试框架（7个测试场景）
- [x] CI/CD 自动化测试流水线

### Phase 3: 真实设备集成 🔄 (进行中)
- [ ] 集成 Insta360 SDK
- [ ] 实现真实蓝牙通信
- [ ] 真机测试验证

### Phase 4: 后端开发 ⏳ (计划中)
- [ ] FastAPI 后端服务
- [ ] PostgreSQL 数据库
- [ ] WebSocket 实时通知
- [ ] AI 模型集成

---

## 📚 文档索引

### 架构与设计
| 文档 | 描述 | 状态 |
|------|------|------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 完整系统架构，包括五层架构、核心子系统、数据流设计 | ✅ 完成 |
| [AI_PIPELINE.md](AI_PIPELINE.md) | 三层 AI 检测架构、成本优化策略、质量检查机制 | ✅ 完成 |
| [API_SPEC.md](API_SPEC.md) | REST API 和 WebSocket API 完整规范 | ✅ 完成 |
| [DB_SCHEMA.sql](DB_SCHEMA.sql) | 数据库 Schema、索引、视图、触发器 | ✅ 完成 |

### Android 开发
| 文档 | 描述 | 状态 |
|------|------|------|
| [feature-device/README.md](feature-device/README.md) | 设备模块详细文档，包括架构、API、使用方法 | ✅ 完成 |
| [feature-device/QUICKSTART.md](feature-device/QUICKSTART.md) | 快速启动指南，5分钟上手 | ✅ 完成 |
| [feature-device/IMPLEMENTATION.md](feature-device/IMPLEMENTATION.md) | 实现总结和技术细节 | ✅ 完成 |

### 测试文档
| 文档 | 描述 | 状态 |
|------|------|------|
| [E2E_TESTING_STRATEGY.md](E2E_TESTING_STRATEGY.md) | 完整测试策略：单元/集成/E2E，Mock 策略，CI/CD | ✅ 完成 |
| [TESTING_QUICKSTART.md](TESTING_QUICKSTART.md) | 测试快速启动指南，包括运行命令、调试技巧 | ✅ 完成 |
| [TESTING_DELIVERY_SUMMARY.md](TESTING_DELIVERY_SUMMARY.md) | 测试框架交付总结 | ✅ 完成 |

### 项目管理
| 文档 | 描述 | 状态 |
|------|------|------|
| [context.md](context.md) | 项目当前状态、决策日志、历史记录 | 🔄 持续更新 |
| [COLLABORATION.md](COLLABORATION.md) | 协作流程和角色分工 | ✅ 完成 |
| [WORKFLOW.md](WORKFLOW.md) | 工作流程指南 | ✅ 完成 |

---

## 🚀 快速开始

### 环境要求
- **Android**: Android Studio Hedgehog+, JDK 17, Gradle 8.4+
- **模拟器**: API 33 (Android 13) 或真机
- **后端**: Python 3.11+, PostgreSQL 15+ (后续)

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
# 单元测试（快速，2分钟）
./gradlew :feature-device:test

# 查看测试报告
open feature-device/build/reports/tests/test/index.html

# E2E 测试（需模拟器，5分钟）
./gradlew :app:connectedAndroidTest

# 使用 Makefile（推荐）
make test-unit       # 单元测试
make test-e2e        # E2E 测试
make test-smoke      # 快速冒烟测试
make test-all        # 完整测试套件
```

---

## 🏛️ 架构概览

```
┌─────────────────────────────────────────────────────────┐
│                    用户交互层                           │
│  Android App (Jetpack Compose + MVVM + Hilt)           │
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

## 🧪 测试策略

### 测试金字塔
```
           ┌────────────┐
           │  E2E Tests │  10% - 关键用户场景
           │  7 场景    │
           └────────────┘
         ┌────────────────┐
         │ Integration    │  30% - 模块间交互
         │ Tests (待补充) │
         └────────────────┘
      ┌────────────────────────┐
      │    Unit Tests          │  60% - 单个组件
      │    12 用例             │
      └────────────────────────┘
```

### 当前测试覆盖
- ✅ **ViewModel**: 12个单元测试，覆盖所有核心功能
- ✅ **E2E 流程**: 7个场景，覆盖完整连接流程
- ✅ **Mock 基础设施**: Insta360Simulator 设备模拟器
- ⏳ **Repository**: 待实现真实蓝牙测试
- ⏳ **UI 组件**: 待补充 Compose 组件测试

### CI/CD 流水线
- ✅ 单元测试自动运行
- ✅ Lint 检查
- ✅ E2E 测试（Android Emulator）
- ✅ 构建验证
- ✅ 测试覆盖率报告

---

## 📊 代码统计

```
项目总览:
  - 总行数: ~15,000 行
  - Kotlin: ~3,000 行
  - Markdown: ~8,000 行（文档）
  - SQL: ~800 行
  - YAML: ~200 行（CI/CD）

Android 模块:
  - feature-device: ~1,200 行
    - UI (Compose): 13 个组件
    - ViewModel: 1 个
    - Model: 2 个数据类
    - Test: 19 个测试用例

文档:
  - 架构文档: 4 份
  - Android 文档: 3 份
  - 测试文档: 3 份
  - 项目管理: 3 份
```

---

## 🛠️ 技术栈

### Android
- **UI**: Jetpack Compose + Material Design 3
- **架构**: MVVM + Repository Pattern
- **依赖注入**: Hilt (Dagger)
- **异步**: Kotlin Coroutines + Flow
- **测试**: JUnit 4, MockK, Compose Testing, Espresso

### 后端（待实现）
- **框架**: FastAPI (Python 3.11)
- **数据库**: PostgreSQL 15
- **实时通信**: WebSocket
- **AI**: OpenAI API / Anthropic Claude API
- **部署**: Docker + Docker Compose

### DevOps
- **CI/CD**: GitHub Actions
- **代码质量**: Lint, Detekt
- **测试覆盖率**: JaCoCo, Codecov

---

## 📈 开发计划

### 当前里程碑: Phase 3 - 真实设备集成

**本周目标** (2026-09-18 → 2026-09-25):
1. ✅ 添加测试依赖
2. ✅ 运行单元测试验证
3. 🔄 获取 Insta360 SDK
4. 🔄 实现 DeviceRepositoryImpl
5. 🔄 真机测试

**下周目标** (2026-09-26 → 2026-10-02):
1. 后端 FastAPI 服务搭建
2. PostgreSQL 数据库部署
3. 任务管理 API 实现
4. Android <-> Backend 集成测试

**月度目标** (2026-10):
1. 录制功能完整实现
2. L1 端侧检测集成
3. L2 云端初判实现
4. 完整功能 Demo 演示

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

### 测试要求
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

**最后更新**: 2026-09-18  
**版本**: v0.2.0-alpha  
**状态**: 🟡 开发中
