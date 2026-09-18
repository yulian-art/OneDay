# 它的一天 OneDay · 项目上下文

## Current State
```yaml
phase: Phase 1 - 架构设计 (已完成)
current_tasks:
  - Phase 1: 架构设计 (已完成, Claude Opus 5)
completed_deliverables:
  - ARCHITECTURE.md: 系统架构设计
  - AI_PIPELINE.md: AI 流水线设计
  - API_SPEC.md: API 规范
  - DB_SCHEMA.sql: 数据库 Schema
blockers: 无
next_phase: Phase 2 - 模块分工
last_update: 2026-09-18 (Phase 1 完成)
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
- 2026-09-18: Phase 1 完成，Overseer (Claude Opus 5) 完成架构设计
  - 交付 ARCHITECTURE.md (系统架构)
  - 交付 AI_PIPELINE.md (AI 流水线)
  - 交付 API_SPEC.md (API 规范)
  - 交付 DB_SCHEMA.sql (数据库 Schema)
  - 核心设计：录像分析分离、三层AI、时间同步、纠正分类

## Next Steps (Phase 2)
1. 根据 COLLABORATION.md，Phase 2 应由 Overseer 完成模块分工
2. 输出 TASKS.md，分配具体任务给 Workers：
   - Android Worker (Claude Sonnet 5): 设备页、录制控制、预览显示
   - Backend Worker (DeepSeek V4-Pro): 后端 API、任务管理、模型编排
   - AI/CV Worker (Claude Opus 5): VLM 集成、复核逻辑
   - Testing Worker (Claude Sonnet 5): 单元测试、集成测试
