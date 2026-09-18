-- 它的一天 OneDay · 数据库 Schema
-- 数据库: SQLite (本地) / PostgreSQL (云端备份)
-- 字符集: UTF-8
-- 时间戳: 统一使用 Unix timestamp (float, 秒)

-- ============================================================================
-- 1. 用户与设备表
-- ============================================================================

-- 用户表
CREATE TABLE users (
    user_id TEXT PRIMARY KEY,              -- UUID
    device_id TEXT NOT NULL UNIQUE,        -- Android 设备 ID
    created_at REAL NOT NULL,              -- Unix timestamp
    last_active_at REAL NOT NULL,
    app_version TEXT NOT NULL,             -- 例如 "1.0.0"
    settings TEXT,                         -- JSON: 用户偏好设置

    INDEX idx_device_id (device_id)
);

-- 设备表 (相机 + 确认夹)
CREATE TABLE devices (
    device_id TEXT PRIMARY KEY,            -- Android 设备 ID
    user_id TEXT NOT NULL,

    -- 相机信息
    camera_model TEXT,                     -- 例如 "X4"
    camera_serial TEXT,
    camera_firmware TEXT,
    camera_sdk_version TEXT,
    camera_status TEXT,                    -- "connected" | "disconnected"
    camera_last_sync REAL,

    -- 确认夹信息 (可选)
    clip_model TEXT,                       -- 例如 "nrf52840"
    clip_mac_address TEXT,
    clip_battery_level INTEGER,            -- 0-100
    clip_firmware TEXT,
    clip_status TEXT,                      -- "connected" | "disconnected"
    clip_last_sync REAL,

    created_at REAL NOT NULL,
    updated_at REAL NOT NULL,

    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id)
);

-- ============================================================================
-- 2. 任务表
-- ============================================================================

-- 任务定义表 (多版本)
CREATE TABLE tasks (
    task_id TEXT NOT NULL,                 -- 任务 UUID
    version INTEGER NOT NULL,              -- 版本号，从 1 开始
    user_id TEXT NOT NULL,

    -- 用户输入
    user_input TEXT NOT NULL,              -- 原始自然语言输入

    -- 解析后的任务定义 (JSON)
    event_type TEXT NOT NULL,              -- "return_and_deliver_toy" 等
    target_subjects TEXT NOT NULL,         -- JSON: {owner: {frame, bbox}, dog: {...}}
    required_stages TEXT NOT NULL,         -- JSON: ["接近", "交接", "交接后画面"]
    exclusion_conditions TEXT,             -- JSON: ["只放在脚边"]
    frame_requirements TEXT NOT NULL,      -- JSON: {handoff_area_visible: true, ...}

    -- 全景取景方向
    interaction_direction TEXT,            -- JSON: {yaw: 45.0, pitch: 0.0}

    -- 支持性检查
    supported BOOLEAN NOT NULL DEFAULT 1,
    unsupported_reasons TEXT,              -- JSON: ["情绪判断", "多主体"]

    -- 元数据
    created_at REAL NOT NULL,
    created_reason TEXT,                   -- "initial" | "user_correction" | "refinement"
    parent_version INTEGER,                -- 父版本号 (如果是修改而来)

    PRIMARY KEY (task_id, version),
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    INDEX idx_task_user (user_id, task_id),
    INDEX idx_created_at (created_at)
);

-- 任务示例表 (正反例)
CREATE TABLE task_examples (
    example_id TEXT PRIMARY KEY,           -- UUID
    task_id TEXT NOT NULL,
    task_version INTEGER NOT NULL,
    user_id TEXT NOT NULL,

    example_type TEXT NOT NULL,            -- "positive" | "negative"

    -- 示例内容
    frames TEXT NOT NULL,                  -- JSON: [{timestamp, image_base64, detections}]
    user_annotation TEXT NOT NULL,         -- 用户说明

    -- 关联的候选 (如果来自候选)
    candidate_id TEXT,

    -- 模型当时的判断 (用于追踪改进)
    model_prediction TEXT,                 -- JSON: {verdict, confidence, reason}

    created_at REAL NOT NULL,

    FOREIGN KEY (task_id, task_version) REFERENCES tasks(task_id, version) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    INDEX idx_task_version (task_id, task_version),
    INDEX idx_type (example_type)
);

-- ============================================================================
-- 3. 会话表
-- ============================================================================

-- 会话表 (一次录制)
CREATE TABLE sessions (
    session_id TEXT PRIMARY KEY,           -- UUID
    user_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    task_id TEXT NOT NULL,
    task_version INTEGER NOT NULL,

    -- 录制信息
    camera_recording_id TEXT,              -- 相机返回的录制 ID
    video_files TEXT,                      -- JSON: ["/path/video_001.mp4", ...]

    -- 状态
    status TEXT NOT NULL,                  -- "active" | "completed" | "cancelled"

    -- 时间
    created_at REAL NOT NULL,              -- 会话创建时间
    stopped_at REAL,                       -- 停止时间
    duration_seconds REAL,                 -- 实际录制时长

    -- 统计
    candidates_count INTEGER DEFAULT 0,
    confirmed_events INTEGER DEFAULT 0,

    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    FOREIGN KEY (device_id) REFERENCES devices(device_id),
    FOREIGN KEY (task_id, task_version) REFERENCES tasks(task_id, version),
    INDEX idx_user_sessions (user_id, created_at DESC),
    INDEX idx_status (status)
);

-- 时间同步表 (多时钟域映射)
CREATE TABLE time_mappings (
    sync_id TEXT PRIMARY KEY,              -- UUID
    session_id TEXT NOT NULL,

    -- 同步事件类型
    event_type TEXT NOT NULL,              -- "recording_start" | "recording_stop" | "manual_sync"

    -- 各时钟域的时间戳
    user_time REAL NOT NULL,               -- Android 系统时间 (Unix timestamp)
    preview_time REAL NOT NULL,            -- SDK 预览流时间
    button_time REAL,                      -- 确认夹 RTC 时间 (可选)
    file_time REAL,                        -- 视频文件时间戳 (从 0 开始)

    -- 关联的视频文件
    video_file TEXT,                       -- 文件路径

    -- 创建时间
    created_at REAL NOT NULL,

    FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE,
    INDEX idx_session_sync (session_id, created_at)
);

-- ============================================================================
-- 4. 候选事件表
-- ============================================================================

-- 候选事件表
CREATE TABLE candidates (
    candidate_id TEXT PRIMARY KEY,         -- UUID
    session_id TEXT NOT NULL,
    task_id TEXT NOT NULL,
    task_version INTEGER NOT NULL,

    -- 触发信息
    trigger TEXT NOT NULL,                 -- "approach_and_stay" | "handoff_geometry" | "periodic_sample"
    priority TEXT DEFAULT 'normal',        -- "low" | "normal" | "high"

    -- 时间范围 (预览时间)
    preview_start_time REAL NOT NULL,
    preview_end_time REAL NOT NULL,

    -- 时间范围 (文件时间，映射后)
    file_start_time REAL,
    file_end_time REAL,
    video_file TEXT,

    -- 状态
    status TEXT NOT NULL,                  -- "pending_review" | "preliminary_match" | "confirmed" | "needs_review" | "rejected"

    -- 采样帧 (用于初步复核)
    sampled_frames TEXT NOT NULL,          -- JSON: [{timestamp, image, detections}]

    -- 元数据
    metadata TEXT,                         -- JSON: {avg_distance, min_confidence, ...}

    -- 时间戳
    created_at REAL NOT NULL,
    reviewed_at REAL,                      -- L2 复核完成时间
    confirmed_at REAL,                     -- L3 原片复核完成时间

    FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE,
    FOREIGN KEY (task_id, task_version) REFERENCES tasks(task_id, version),
    INDEX idx_session_candidates (session_id, created_at DESC),
    INDEX idx_status (status),
    INDEX idx_trigger (trigger)
);

-- 复核结果表 (L2 初步 + L3 原片)
CREATE TABLE review_results (
    review_id TEXT PRIMARY KEY,            -- UUID
    candidate_id TEXT NOT NULL,
    review_level TEXT NOT NULL,            -- "L2_preview" | "L3_original"

    -- 模型信息
    model_name TEXT NOT NULL,              -- "qwen2-vl-7b" | "claude-opus-5"
    model_tier TEXT NOT NULL,              -- "fast" | "balanced" | "accurate"

    -- 复核结果
    stages_detected TEXT,                  -- JSON: ["接近", "停留"]
    exclusions_triggered TEXT,             -- JSON: []
    key_area_visible BOOLEAN,
    occlusion_level REAL,                  -- 0.0 - 1.0

    verdict TEXT NOT NULL,                 -- "符合" | "不符合" | "不确定" (L2) | "已确认" | "待确认" | "拒绝" (L3)
    confidence REAL NOT NULL,              -- 0.0 - 1.0
    reason TEXT NOT NULL,

    -- 证据帧 (L3 专用)
    evidence_frames TEXT,                  -- JSON: [{timestamp, observation, image_url}]
    issues TEXT,                           -- JSON: ["遮挡严重", "画面模糊"]

    -- 成本与性能
    latency_seconds REAL,
    cost_usd REAL,

    created_at REAL NOT NULL,

    FOREIGN KEY (candidate_id) REFERENCES candidates(candidate_id) ON DELETE CASCADE,
    INDEX idx_candidate_reviews (candidate_id, review_level)
);

-- ============================================================================
-- 5. 用户反馈表
-- ============================================================================

-- 用户反馈表
CREATE TABLE user_feedback (
    feedback_id TEXT PRIMARY KEY,          -- UUID
    candidate_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    session_id TEXT NOT NULL,

    -- 反馈内容
    user_verdict TEXT NOT NULL,            -- "correct" | "wrong" | "uncertain"
    correction_type TEXT,                  -- "standard_error" | "judgment_error"
    user_comment TEXT,

    -- 纠正行动
    action_taken TEXT NOT NULL,            -- "updated_task_definition" | "added_negative_example" | "added_positive_example"
    task_version_updated BOOLEAN DEFAULT 0,
    new_task_version INTEGER,

    -- 受影响的候选
    affected_candidates INTEGER DEFAULT 0, -- 需要重新评估的候选数
    reevaluation_triggered BOOLEAN DEFAULT 0,

    created_at REAL NOT NULL,

    FOREIGN KEY (candidate_id) REFERENCES candidates(candidate_id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE,
    INDEX idx_user_feedback (user_id, created_at DESC),
    INDEX idx_correction_type (correction_type)
);

-- ============================================================================
-- 6. 导出表
-- ============================================================================

-- 导出任务表
CREATE TABLE exports (
    export_id TEXT PRIMARY KEY,            -- UUID
    candidate_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    session_id TEXT NOT NULL,

    -- 源视频
    video_file TEXT NOT NULL,
    file_time_range TEXT NOT NULL,         -- JSON: [start, end]

    -- 导出格式
    export_format TEXT NOT NULL,           -- JSON: {type: "planar", resolution: "1920x1080", direction: {...}}

    -- 标注选项
    annotations TEXT,                      -- JSON: {include_timestamp: true, ...}

    -- 输出
    output_file TEXT,
    file_size_bytes INTEGER,
    duration_seconds REAL,

    -- 状态
    status TEXT NOT NULL,                  -- "processing" | "completed" | "failed"
    error_message TEXT,

    -- 时间
    created_at REAL NOT NULL,
    completed_at REAL,

    FOREIGN KEY (candidate_id) REFERENCES candidates(candidate_id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE,
    INDEX idx_user_exports (user_id, created_at DESC),
    INDEX idx_status_exports (status)
);

-- ============================================================================
-- 7. 统计与分析表
-- ============================================================================

-- 会话统计表
CREATE TABLE session_stats (
    stat_id TEXT PRIMARY KEY,              -- UUID
    session_id TEXT NOT NULL UNIQUE,

    -- 候选统计
    candidates_total INTEGER DEFAULT 0,
    candidates_confirmed INTEGER DEFAULT 0,
    candidates_preliminary INTEGER DEFAULT 0,
    candidates_needs_review INTEGER DEFAULT 0,
    candidates_rejected INTEGER DEFAULT 0,

    -- 触发器统计
    trigger_approach_stay INTEGER DEFAULT 0,
    trigger_handoff_geometry INTEGER DEFAULT 0,
    trigger_periodic_sample INTEGER DEFAULT 0,

    -- 性能指标
    avg_review_latency_seconds REAL,
    avg_original_review_latency_seconds REAL,
    total_cost_usd REAL DEFAULT 0.0,

    -- 用户操作
    user_corrections INTEGER DEFAULT 0,
    user_confirmations INTEGER DEFAULT 0,

    updated_at REAL NOT NULL,

    FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE
);

-- 任务效果追踪表 (按版本)
CREATE TABLE task_effectiveness (
    effectiveness_id TEXT PRIMARY KEY,     -- UUID
    task_id TEXT NOT NULL,
    task_version INTEGER NOT NULL,

    -- 使用统计
    sessions_count INTEGER DEFAULT 0,
    total_candidates INTEGER DEFAULT 0,

    -- 准确性指标
    false_positives INTEGER DEFAULT 0,     -- 系统认为符合，用户认为不符合
    false_negatives INTEGER DEFAULT 0,     -- 系统认为不符合，用户认为符合
    true_positives INTEGER DEFAULT 0,
    true_negatives INTEGER DEFAULT 0,

    -- 用户参与
    user_corrections INTEGER DEFAULT 0,
    positive_examples INTEGER DEFAULT 0,
    negative_examples INTEGER DEFAULT 0,

    -- 时间范围
    first_session_at REAL,
    last_session_at REAL,

    updated_at REAL NOT NULL,

    FOREIGN KEY (task_id, task_version) REFERENCES tasks(task_id, version) ON DELETE CASCADE,
    UNIQUE (task_id, task_version)
);

-- ============================================================================
-- 8. 系统日志表
-- ============================================================================

-- 系统日志表 (关键事件)
CREATE TABLE system_logs (
    log_id TEXT PRIMARY KEY,               -- UUID
    user_id TEXT,
    session_id TEXT,
    candidate_id TEXT,

    -- 日志级别
    level TEXT NOT NULL,                   -- "info" | "warning" | "error"

    -- 事件类型
    event_type TEXT NOT NULL,              -- "model_timeout" | "video_file_not_found" | "time_mapping_missing" | ...

    -- 详细信息
    message TEXT NOT NULL,
    details TEXT,                          -- JSON

    -- 错误追踪
    error_code TEXT,
    stack_trace TEXT,

    created_at REAL NOT NULL,

    INDEX idx_level (level),
    INDEX idx_event_type (event_type),
    INDEX idx_created_at (created_at DESC)
);

-- ============================================================================
-- 9. 视图 (便捷查询)
-- ============================================================================

-- 活跃会话视图
CREATE VIEW active_sessions AS
SELECT
    s.session_id,
    s.user_id,
    s.task_id,
    s.task_version,
    t.event_type,
    s.created_at,
    s.duration_seconds,
    COUNT(c.candidate_id) as candidates_count,
    SUM(CASE WHEN c.status = 'confirmed' THEN 1 ELSE 0 END) as confirmed_count
FROM sessions s
JOIN tasks t ON s.task_id = t.task_id AND s.task_version = t.version
LEFT JOIN candidates c ON s.session_id = c.session_id
WHERE s.status = 'active'
GROUP BY s.session_id;

-- 待处理候选视图
CREATE VIEW pending_candidates AS
SELECT
    c.candidate_id,
    c.session_id,
    c.trigger,
    c.preview_start_time,
    c.status,
    c.created_at,
    s.user_id,
    s.task_id,
    s.task_version
FROM candidates c
JOIN sessions s ON c.session_id = s.session_id
WHERE c.status IN ('pending_review', 'preliminary_match')
ORDER BY c.priority DESC, c.created_at ASC;

-- 用户任务概览视图
CREATE VIEW user_tasks_overview AS
SELECT
    t.task_id,
    t.version,
    t.user_id,
    t.event_type,
    t.user_input,
    t.created_at,
    COUNT(DISTINCT s.session_id) as sessions_count,
    te.false_positives,
    te.false_negatives,
    te.user_corrections
FROM tasks t
LEFT JOIN sessions s ON t.task_id = s.task_id AND t.version = s.task_version
LEFT JOIN task_effectiveness te ON t.task_id = te.task_id AND t.version = te.task_version
GROUP BY t.task_id, t.version
ORDER BY t.task_id, t.version DESC;

-- ============================================================================
-- 10. 触发器 (自动更新统计)
-- ============================================================================

-- 更新会话候选计数
CREATE TRIGGER update_session_candidate_count
AFTER INSERT ON candidates
BEGIN
    UPDATE sessions
    SET candidates_count = candidates_count + 1
    WHERE session_id = NEW.session_id;
END;

-- 更新会话确认事件计数
CREATE TRIGGER update_session_confirmed_count
AFTER UPDATE OF status ON candidates
WHEN NEW.status = 'confirmed' AND OLD.status != 'confirmed'
BEGIN
    UPDATE sessions
    SET confirmed_events = confirmed_events + 1
    WHERE session_id = NEW.session_id;
END;

-- 更新会话统计表
CREATE TRIGGER update_session_stats
AFTER UPDATE OF status ON candidates
BEGIN
    INSERT OR REPLACE INTO session_stats (
        stat_id,
        session_id,
        candidates_total,
        candidates_confirmed,
        candidates_preliminary,
        candidates_needs_review,
        candidates_rejected,
        updated_at
    )
    SELECT
        COALESCE(ss.stat_id, hex(randomblob(16))),
        NEW.session_id,
        COUNT(*),
        SUM(CASE WHEN status = 'confirmed' THEN 1 ELSE 0 END),
        SUM(CASE WHEN status = 'preliminary_match' THEN 1 ELSE 0 END),
        SUM(CASE WHEN status = 'needs_review' THEN 1 ELSE 0 END),
        SUM(CASE WHEN status = 'rejected' THEN 1 ELSE 0 END),
        strftime('%s', 'now')
    FROM candidates
    LEFT JOIN session_stats ss ON ss.session_id = NEW.session_id
    WHERE candidates.session_id = NEW.session_id;
END;

-- ============================================================================
-- 11. 索引优化
-- ============================================================================

-- 常用查询索引
CREATE INDEX idx_candidates_session_status ON candidates(session_id, status);
CREATE INDEX idx_candidates_time_range ON candidates(preview_start_time, preview_end_time);
CREATE INDEX idx_review_results_verdict ON review_results(verdict);
CREATE INDEX idx_feedback_action ON user_feedback(action_taken);
CREATE INDEX idx_exports_user_status ON exports(user_id, status);

-- ============================================================================
-- 12. 初始化数据
-- ============================================================================

-- 插入系统配置 (可选)
CREATE TABLE system_config (
    config_key TEXT PRIMARY KEY,
    config_value TEXT NOT NULL,
    description TEXT,
    updated_at REAL NOT NULL
);

INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('schema_version', '1.0.0', '数据库 Schema 版本', strftime('%s', 'now')),
('supported_event_types', '["approach_and_stay", "return_and_deliver_toy", "fetch_and_return"]', '支持的事件类型', strftime('%s', 'now')),
('model_tiers', '{"fast": "qwen2-vl-7b", "balanced": "claude-3.5-haiku", "accurate": "claude-opus-5"}', '模型分层配置', strftime('%s', 'now')),
('rate_limits', '{"create_task": 10, "create_session": 20, "create_candidate": 100}', 'API 速率限制', strftime('%s', 'now'));

-- ============================================================================
-- 13. 数据清理策略
-- ============================================================================

-- 定期清理过期日志 (保留 30 天)
-- 应用层实现: DELETE FROM system_logs WHERE created_at < (strftime('%s', 'now') - 2592000);

-- 清理已删除会话的孤立数据
-- 应用层实现: 使用 ON DELETE CASCADE 自动清理

-- ============================================================================
-- 总结
-- ============================================================================

-- 核心表：
--   • users, devices: 用户与设备管理
--   • tasks, task_examples: 任务定义与示例学习
--   • sessions, time_mappings: 会话与时间同步
--   • candidates, review_results: 事件检测与复核
--   • user_feedback: 用户纠正反馈
--   • exports: 视频导出
--   • session_stats, task_effectiveness: 统计分析
--   • system_logs: 系统日志

-- 设计原则：
--   1. 版本控制：任务支持多版本，保留历史
--   2. 时间映射：多时钟域同步，保证事件可追溯
--   3. 状态机：候选生命周期清晰
--   4. 审计追踪：所有关键操作记录日志
--   5. 性能优化：合理索引，支持高频查询
--   6. 数据完整性：外键约束 + 级联删除

-- 扩展性：
--   • 支持 SQLite (本地) 和 PostgreSQL (云端) 双模式
--   • JSON 字段便于灵活扩展
--   • 视图简化复杂查询
--   • 触发器自动维护统计数据
