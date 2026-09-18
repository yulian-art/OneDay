# 它的一天 OneDay · 多模型协作规则

## 角色定义
- **Overseer（Claude Opus）**：方向、决策、审查。不读大量文件，不迭代细节。
- **Worker（DeepSeek V4-Pro）**：执行、迭代、细节实现。读文件、写代码、修 bug。

## 切换触发条件
- Worker 遇到架构分叉 → 升级给 Overseer
- Overseer 完成规划 → 降级给 Worker 执行
- 阶段边界 → Overseer 必须审查

## 上下文写入格式
- 每次 Worker 完成一个模块，在 context.md 的 Current State 中更新
- 所有决策记录追加到 history log