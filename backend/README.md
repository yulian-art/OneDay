# OneDay 后端与 AI（本地 MVP）

负责范围：FastAPI、数据库迁移、任务解析、VLM 适配、候选事件状态机、反馈示例学习。
本地最快路径为 **Windows + Python 3.11/3.12 + SQLite**，无需 WSL、Android SDK、手机、Docker 或模型密钥。
Linux 使用相同 Python 工程；生产数据库可配置 PostgreSQL，不能将 Android Room 数据库直接作为本服务数据库。

## 启动

以下命令从 `backend` 目录运行。Windows：

```powershell
py -3.12 -m venv .venv
.venv\Scripts\python -m pip install -r requirements.lock
Copy-Item .env.example .env
# 将 .env 中 ONEDAY_JWT_SECRET 替换为随机值（至少 32 字符）。
.venv\Scripts\python -m alembic upgrade head
.venv\Scripts\python -m uvicorn oneday.main:app --host 127.0.0.1 --port 8000
```

第二个终端启动 Worker：

```powershell
.venv\Scripts\python -m oneday.worker
```

第三个终端运行完整无硬件演示：

```powershell
.venv\Scripts\python scripts/demo.py
```

Linux 将 `.venv\Scripts\python` 替换为 `.venv/bin/python`，创建环境使用 `python3 -m venv .venv`。
依赖锁包含开发测试工具；数据库与 `.env` 均不进入版本控制。
API 文档：<http://127.0.0.1:8000/docs>，健康检查：<http://127.0.0.1:8000/health>。
服务和 Worker 必须在相同目录启动、读取同一 `.env`，SQLite 路径相对于工作目录。

## 数据库

迁移是唯一升级入口：`python -m alembic upgrade head`。服务启动不会隐式创建或修改表。
首个迁移包含 11 张业务表及 Alembic 版本表；迁移脚本冻结结构，不引用运行时 ORM 模型。
`python scripts/export_schema.py` 生成根目录 `DB_SCHEMA.sql`（可执行 SQLite 空库基线）
和 `docs/schema-postgresql.sql`（PostgreSQL SQL）。旧设计文档保留在 `docs/`，不应直接执行。

PostgreSQL 示例配置：

```dotenv
ONEDAY_DATABASE_URL=postgresql+psycopg://oneday:password@127.0.0.1:5432/oneday
```

本次本地验证覆盖 SQLite 真实迁移、回滚、数据约束，以及 PostgreSQL 离线 SQL 生成。
未启动 PostgreSQL 实例验证，不宣称已完成生产部署。

## VLM 配置

默认 `ONEDAY_VLM_PROVIDER=disabled`，后台会保留候选并标记 `needs_review`，不会生成虚构识别结果。
要使用支持图像输入及 JSON 返回的 Chat Completions 兼容服务，在 `.env` 中设置：

```dotenv
ONEDAY_VLM_PROVIDER=openai_compatible
ONEDAY_VLM_BASE_URL=https://your-provider.example/v1
ONEDAY_VLM_MODEL=your-vision-model
ONEDAY_VLM_API_KEY=your-secret
```

重启 Worker 生效。密钥只在服务端使用；错误记录仅保存错误类别，不保存供应商响应或密钥。
所有帧限定 JPEG/PNG base64，最多 8 帧，单帧 base64 最长 262144 字符；不抓取客户端任意 URL。
隐私开关开启时，带图上传要求客户端完成去人处理并传 `privacy_redacted=true`。
后端当前只校验该声明，**没有实现去人算法或验证去人效果**。
接入真实服务需先确认用户素材可发送至该供应商。无密钥测试使用 HTTP MockTransport，仅验证适配协议。

## 任务与反馈

- 解析器是可审计的 `rules-v1`：喝水、玩具交接、接近停留、跳跃；不支持的动作组合、情绪/健康判断返回 422。
- 定义返回 `requires_confirmation=true`，由客户端让用户核对。不是任意自然语言理解模型。
- 初始会话绑定任务版本；修改标准创建新版本，旧版本可查询，不悄悄改写既有会话。
- `standard_error` 创建新定义并重新复核当前候选；响应明确 `affected_candidates=1`，不自动重算全部历史。
- `judgment_error` 保存当前版本正反例；同一候选修改标签时替换活动示例，反馈历史仍保留。
- 后续 VLM 请求使用同一任务版本最近最多 4 个示例，每例最多 2 帧。属于提示示例学习，不是权重训练。
- 预览匹配只到 `preliminary_match`；`confirmed` 当前只能由人工确认产生，不宣称原片验证成功。
- 高遮挡、低置信度、供应商故障均保留为 `needs_review`；负例也不自动删除或忽略。

## 队列与并发

候选和复核任务同事务入库。`eventID` 在用户范围唯一，完全相同的补传返回已有候选；同 ID 不同内容返回 409。
反馈使用 `request_id` 幂等。并发首次插入冲突可能返回 409；客户端应使用原始同 ID、同 payload 重试。
Worker 用条件更新领取任务，租约过期后可由新进程恢复；429/5xx/连接错误/无效模型 JSON 最多调用 3 次，指数退避。
401 等永久错误直接失败并转待确认。任务状态与错误可通过 API 查询。
候选 `revision` 防止晚到 AI 覆盖人工反馈；任务 `expected_version` 防止并发改写标准。
所有查询校验资源所有者。用户保留/忽略操作写审计日志；忽略是可恢复标记，不物理删除。

## 测试

```powershell
.venv\Scripts\python -m pytest -q
.venv\Scripts\python -m ruff check .
.venv\Scripts\python -m alembic check
```

覆盖接口端到端流程、重复与并发补传、租约恢复、双 Worker、过期 AI 结果、模型错误、隐私与用户隔离、迁移。

## 边界与协作

设备 ID 换 JWT 的登录仅供回环地址本地联调，不是生产身份验证。`production` 配置强制关闭它；正式部署前需接入真实身份服务、TLS、限流和素材授权策略。
未实现：相机控制、手机后台保活、素材上传/断点续传、视频抽帧/导出、原片 AI 复核、WebSocket、漫画生成、六格分享、Room 写入、双宠及门店。
这些仍由团队按职责集成；返回值 `generated=false` 明确表达尚无成品生成。
本变更仅在 `backend/**`、`API_SPEC.md` 和 `DB_SCHEMA.sql` 范围提交。
不要提交 `.env`、数据库、虚拟环境、APK 或本机 `local.properties`。
