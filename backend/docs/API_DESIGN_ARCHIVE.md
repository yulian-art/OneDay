# 它的一天 OneDay · API 规范

## 1. API 概览

### 1.1 架构风格
- **协议**: HTTP/2, WebSocket (实时通知)
- **格式**: JSON
- **认证**: Bearer Token (JWT)
- **版本**: `/api/v1`

### 1.2 服务端点

| 服务 | 端口 | 用途 |
|------|------|------|
| REST API | 8000 | 设备同步、任务管理、事件查询 |
| WebSocket | 8001 | 实时候选通知、状态更新 |
| Model Service | 内部 | AI 模型调用（不对外暴露）|

---

## 2. 认证与授权

### 2.1 获取 Token

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "device_id": "android-abc123",
  "device_name": "Pixel 6",
  "app_version": "1.0.0"
}
```

**响应**:
```json
{
  "access_token": "eyJhbGc...",
  "token_type": "Bearer",
  "expires_in": 86400,
  "user_id": "user-uuid"
}
```

### 2.2 Token 使用

```http
GET /api/v1/sessions
Authorization: Bearer eyJhbGc...
```

---

## 3. 设备管理 API

### 3.1 同步设备信息

```http
POST /api/v1/devices/sync
Authorization: Bearer {token}
Content-Type: application/json

{
  "device_id": "android-abc123",
  "camera_info": {
    "model": "X4",
    "serial": "1234567890",
    "firmware": "v2.1.5",
    "sdk_version": "2.1.5"
  },
  "clip_info": {
    "model": "nrf52840",
    "mac_address": "AA:BB:CC:DD:EE:FF",
    "battery_level": 85,
    "firmware": "v1.0.0"
  },
  "timestamp": 1726666800.0
}
```

**响应**:
```json
{
  "device_id": "android-abc123",
  "camera_status": "connected",
  "clip_status": "connected",
  "server_time": 1726666800.123,
  "time_offset_ms": 123
}
```

### 3.2 查询设备状态

```http
GET /api/v1/devices/{device_id}
Authorization: Bearer {token}
```

**响应**:
```json
{
  "device_id": "android-abc123",
  "camera_info": {...},
  "clip_info": {...},
  "last_sync": 1726666800.0,
  "status": "online"
}
```

---

## 4. 任务管理 API

### 4.1 创建任务

```http
POST /api/v1/tasks
Authorization: Bearer {token}
Content-Type: application/json

{
  "user_input": "狗返回主人并将玩具交到手里",
  "target_subjects": {
    "owner": {
      "confirmed_frame": "base64_image",
      "bbox": [0.3, 0.2, 0.5, 0.8]
    },
    "dog": {
      "confirmed_frame": "base64_image",
      "bbox": [0.5, 0.4, 0.3, 0.5]
    }
  },
  "interaction_direction": {
    "yaw": 45.0,
    "pitch": 0.0
  }
}
```

**响应**:
```json
{
  "task_id": "task-uuid",
  "version": 1,
  "parsed_definition": {
    "event_type": "return_and_deliver_toy",
    "required_stages": [
      "狗携带玩具接近主人",
      "玩具从狗转移到主人手中",
      "交接后主人和狗的互动画面"
    ],
    "exclusion_conditions": ["只把玩具放在主人脚边"],
    "frame_requirements": {
      "handoff_area_visible": true,
      "min_duration": 2.0,
      "max_occlusion": 0.3
    }
  },
  "supported": true,
  "created_at": "2026-09-18T10:00:00Z"
}
```

**错误响应 (不支持的任务)**:
```json
{
  "error": "unsupported_task",
  "message": "第一版暂不支持情绪判断",
  "supported": false,
  "suggested_alternatives": [
    "接近并停留",
    "玩具交接"
  ]
}
```

### 4.2 修改任务

```http
PUT /api/v1/tasks/{task_id}
Authorization: Bearer {token}
Content-Type: application/json

{
  "user_input": "狗返回主人并将玩具交到手里，不能只放在脚边",
  "reason": "用户纠正：之前漏了'不能只放在脚边'的条件"
}
```

**响应**:
```json
{
  "task_id": "task-uuid",
  "version": 2,  // 版本号递增
  "parsed_definition": {...},
  "changes": {
    "added_exclusions": ["只把玩具放在主人脚边"],
    "removed_exclusions": []
  },
  "created_at": "2026-09-18T10:05:00Z"
}
```

### 4.3 查询任务

```http
GET /api/v1/tasks/{task_id}?version=2
Authorization: Bearer {token}
```

**响应**:
```json
{
  "task_id": "task-uuid",
  "version": 2,
  "parsed_definition": {...},
  "positive_examples": [
    {
      "example_id": "ex-uuid-1",
      "frames": [...],
      "user_annotation": "这是正确的交接"
    }
  ],
  "negative_examples": [
    {
      "example_id": "ex-uuid-2",
      "frames": [...],
      "user_annotation": "只放在脚边，不算"
    }
  ],
  "created_at": "2026-09-18T10:05:00Z"
}
```

### 4.4 添加示例

```http
POST /api/v1/tasks/{task_id}/examples
Authorization: Bearer {token}
Content-Type: application/json

{
  "example_type": "positive",  // or "negative"
  "candidate_id": "candidate-uuid",  // 关联的候选
  "user_annotation": "这是正确的交接示例",
  "key_frames": [
    {"timestamp": 123.45, "image": "base64..."},
    {"timestamp": 124.67, "image": "base64..."},
    {"timestamp": 125.89, "image": "base64..."}
  ]
}
```

**响应**:
```json
{
  "example_id": "ex-uuid",
  "task_id": "task-uuid",
  "task_version": 2,
  "example_type": "positive",
  "created_at": "2026-09-18T10:10:00Z"
}
```

---

## 5. 会话管理 API

### 5.1 创建会话

```http
POST /api/v1/sessions
Authorization: Bearer {token}
Content-Type: application/json

{
  "task_id": "task-uuid",
  "task_version": 2,
  "device_id": "android-abc123",
  "camera_recording_id": "recording-123",
  "sync_event": {
    "user_time": 1726666800.0,
    "preview_time": 100.0,
    "button_time": 50000  // nullable
  }
}
```

**响应**:
```json
{
  "session_id": "session-uuid",
  "task_id": "task-uuid",
  "task_version": 2,
  "status": "active",
  "created_at": "2026-09-18T10:15:00Z",
  "websocket_url": "ws://api.example.com:8001/ws/sessions/session-uuid"
}
```

### 5.2 停止会话

```http
POST /api/v1/sessions/{session_id}/stop
Authorization: Bearer {token}
Content-Type: application/json

{
  "stop_time": 1726667000.0,
  "video_file": {
    "path": "/sdcard/DCIM/video_001.mp4",
    "file_time_start": 0.0,
    "file_time_end": 200.0
  }
}
```

**响应**:
```json
{
  "session_id": "session-uuid",
  "status": "completed",
  "duration_seconds": 200.0,
  "candidates_count": 5,
  "confirmed_events": 2,
  "stopped_at": "2026-09-18T10:18:20Z"
}
```

### 5.3 查询会话

```http
GET /api/v1/sessions/{session_id}
Authorization: Bearer {token}
```

**响应**:
```json
{
  "session_id": "session-uuid",
  "task_id": "task-uuid",
  "task_version": 2,
  "status": "completed",
  "duration_seconds": 200.0,
  "created_at": "2026-09-18T10:15:00Z",
  "stopped_at": "2026-09-18T10:18:20Z",
  "time_mappings": [
    {
      "sync_id": "sync-uuid",
      "user_time": 1726666800.0,
      "preview_time": 100.0,
      "file_time": 0.0,
      "video_file": "/sdcard/DCIM/video_001.mp4"
    }
  ]
}
```

---

## 6. 事件检测 API

### 6.1 提交候选事件

```http
POST /api/v1/sessions/{session_id}/candidates
Authorization: Bearer {token}
Content-Type: application/json

{
  "trigger": "approach_and_stay",
  "preview_start_time": 123.45,
  "preview_end_time": 125.45,
  "sampled_frames": [
    {
      "timestamp": 123.45,
      "image": "base64_compressed_jpg",
      "detections": [
        {"label": "person", "bbox": [0.3, 0.2, 0.5, 0.8], "confidence": 0.92},
        {"label": "dog", "bbox": [0.5, 0.4, 0.3, 0.5], "confidence": 0.88}
      ]
    },
    // ... 更多帧
  ],
  "metadata": {
    "avg_distance": 1.2,
    "min_confidence": 0.85
  }
}
```

**响应**:
```json
{
  "candidate_id": "candidate-uuid",
  "session_id": "session-uuid",
  "status": "pending_review",
  "created_at": "2026-09-18T10:16:05Z",
  "eta_seconds": 2
}
```

### 6.2 查询候选状态

```http
GET /api/v1/candidates/{candidate_id}
Authorization: Bearer {token}
```

**响应**:
```json
{
  "candidate_id": "candidate-uuid",
  "session_id": "session-uuid",
  "status": "preliminary_match",
  "trigger": "approach_and_stay",
  "preview_time_range": [123.45, 125.45],
  "file_time_range": [23.45, 25.45],
  "review_result": {
    "stages_detected": ["接近", "停留"],
    "verdict": "符合",
    "confidence": 0.87,
    "reason": "狗成功接近主人并停留超过2秒"
  },
  "created_at": "2026-09-18T10:16:05Z",
  "reviewed_at": "2026-09-18T10:16:07Z"
}
```

### 6.3 批量查询候选

```http
GET /api/v1/sessions/{session_id}/candidates?status=confirmed&limit=20&offset=0
Authorization: Bearer {token}
```

**响应**:
```json
{
  "session_id": "session-uuid",
  "candidates": [
    {
      "candidate_id": "candidate-uuid-1",
      "status": "confirmed",
      "file_time_range": [23.45, 25.45],
      "confidence": 0.92,
      "evidence_frames": [24.0, 24.5, 25.0]
    },
    // ... 更多候选
  ],
  "total": 2,
  "limit": 20,
  "offset": 0
}
```

---

## 7. 用户反馈 API

### 7.1 提交纠正

```http
POST /api/v1/candidates/{candidate_id}/feedback
Authorization: Bearer {token}
Content-Type: application/json

{
  "user_verdict": "wrong",  // "correct" | "wrong" | "uncertain"
  "correction_type": "judgment_error",  // "standard_error" | "judgment_error"
  "user_comment": "画面遮挡太严重，实际没有完成交接",
  "corrected_task": null  // 仅在 standard_error 时提供
}
```

**响应**:
```json
{
  "feedback_id": "feedback-uuid",
  "candidate_id": "candidate-uuid",
  "correction_type": "judgment_error",
  "action_taken": "added_negative_example",
  "task_version_updated": false,
  "processed_at": "2026-09-18T10:17:00Z"
}
```

**纠正类型说明**:
- `standard_error`: 任务定义理解错误 → 更新任务版本
- `judgment_error`: 画面判断错误 → 添加反例

### 7.2 标准纠正（修改任务）

```http
POST /api/v1/candidates/{candidate_id}/feedback
Authorization: Bearer {token}
Content-Type: application/json

{
  "user_verdict": "wrong",
  "correction_type": "standard_error",
  "user_comment": "只放在脚边也应该算符合",
  "corrected_task": {
    "user_input": "狗返回主人并将玩具交给主人，放在脚边或交到手里都算",
    "removed_exclusions": ["只把玩具放在主人脚边"]
  }
}
```

**响应**:
```json
{
  "feedback_id": "feedback-uuid",
  "candidate_id": "candidate-uuid",
  "correction_type": "standard_error",
  "action_taken": "updated_task_definition",
  "task_version_updated": true,
  "new_task_version": 3,
  "reevaluation_triggered": true,
  "affected_candidates": 12,  // 需要重新评估的候选数
  "processed_at": "2026-09-18T10:17:00Z"
}
```

---

## 8. 原片复核 API

### 8.1 请求原片复核

```http
POST /api/v1/candidates/{candidate_id}/review-original
Authorization: Bearer {token}
Content-Type: application/json

{
  "video_file": "/sdcard/DCIM/video_001.mp4",
  "file_time_range": [23.45, 25.45],
  "interaction_direction": {
    "yaw": 45.0,
    "pitch": 0.0
  }
}
```

**响应**:
```json
{
  "candidate_id": "candidate-uuid",
  "review_job_id": "job-uuid",
  "status": "extracting_frames",
  "eta_seconds": 10
}
```

### 8.2 查询复核结果

```http
GET /api/v1/candidates/{candidate_id}/review-original/{job_id}
Authorization: Bearer {token}
```

**响应**:
```json
{
  "candidate_id": "candidate-uuid",
  "review_job_id": "job-uuid",
  "status": "completed",
  "verdict": "confirmed",
  "confidence": 0.94,
  "evidence_frames": [
    {
      "timestamp": 24.0,
      "file_time": 24.0,
      "observation": "狗嘴中的玩具清晰可见",
      "image_url": "/api/v1/frames/frame-uuid-1"
    },
    {
      "timestamp": 24.5,
      "file_time": 24.5,
      "observation": "主人手部接触玩具",
      "image_url": "/api/v1/frames/frame-uuid-2"
    }
  ],
  "issues": [],
  "completed_at": "2026-09-18T10:16:17Z"
}
```

---

## 9. 导出 API

### 9.1 请求导出

```http
POST /api/v1/exports
Authorization: Bearer {token}
Content-Type: application/json

{
  "candidate_id": "candidate-uuid",
  "video_file": "/sdcard/DCIM/video_001.mp4",
  "file_time_range": [23.45, 25.45],
  "export_format": {
    "type": "planar",  // "planar" | "360"
    "resolution": "1920x1080",
    "direction": {
      "yaw": 45.0,
      "pitch": 0.0
    }
  },
  "annotations": {
    "include_timestamp": true,
    "include_task_name": true,
    "include_confidence": false
  }
}
```

**响应**:
```json
{
  "export_id": "export-uuid",
  "status": "processing",
  "eta_seconds": 30
}
```

### 9.2 查询导出状态

```http
GET /api/v1/exports/{export_id}
Authorization: Bearer {token}
```

**响应**:
```json
{
  "export_id": "export-uuid",
  "status": "completed",
  "output_file": "/sdcard/OneDay/exports/event_20260918_101605.mp4",
  "file_size_bytes": 5242880,
  "duration_seconds": 2.0,
  "completed_at": "2026-09-18T10:16:47Z",
  "download_url": "/api/v1/exports/export-uuid/download"
}
```

---

## 10. WebSocket 实时通知

### 10.1 连接

```javascript
const ws = new WebSocket('ws://api.example.com:8001/ws/sessions/session-uuid?token=eyJhbGc...');

ws.onopen = () => {
  console.log('WebSocket connected');
};
```

### 10.2 消息类型

#### 候选生成通知
```json
{
  "type": "candidate_created",
  "data": {
    "candidate_id": "candidate-uuid",
    "trigger": "approach_and_stay",
    "preview_time_range": [123.45, 125.45],
    "status": "pending_review"
  },
  "timestamp": "2026-09-18T10:16:05Z"
}
```

#### 复核完成通知
```json
{
  "type": "review_completed",
  "data": {
    "candidate_id": "candidate-uuid",
    "status": "preliminary_match",
    "verdict": "符合",
    "confidence": 0.87
  },
  "timestamp": "2026-09-18T10:16:07Z"
}
```

#### 原片复核通知
```json
{
  "type": "original_review_completed",
  "data": {
    "candidate_id": "candidate-uuid",
    "status": "confirmed",
    "confidence": 0.94,
    "evidence_frames": [...]
  },
  "timestamp": "2026-09-18T10:16:17Z"
}
```

#### 导出完成通知
```json
{
  "type": "export_completed",
  "data": {
    "export_id": "export-uuid",
    "output_file": "/sdcard/OneDay/exports/event_20260918_101605.mp4"
  },
  "timestamp": "2026-09-18T10:16:47Z"
}
```

#### 错误通知
```json
{
  "type": "error",
  "data": {
    "error_code": "model_timeout",
    "message": "模型 API 超时，候选已标记为待确认",
    "candidate_id": "candidate-uuid"
  },
  "timestamp": "2026-09-18T10:16:10Z"
}
```

---

## 11. 时间同步 API

### 11.1 记录同步事件

```http
POST /api/v1/sessions/{session_id}/sync-events
Authorization: Bearer {token}
Content-Type: application/json

{
  "event_type": "recording_start",
  "user_time": 1726666800.0,
  "preview_time": 100.0,
  "button_time": 50000,
  "video_file": "/sdcard/DCIM/video_001.mp4"
}
```

**响应**:
```json
{
  "sync_id": "sync-uuid",
  "session_id": "session-uuid",
  "mapping": {
    "preview_to_file_offset": -100.0,
    "user_to_preview_offset": 1726666700.0
  },
  "created_at": "2026-09-18T10:15:00Z"
}
```

### 11.2 查询时间映射

```http
GET /api/v1/sessions/{session_id}/time-mappings
Authorization: Bearer {token}
```

**响应**:
```json
{
  "session_id": "session-uuid",
  "mappings": [
    {
      "sync_id": "sync-uuid",
      "event_type": "recording_start",
      "user_time": 1726666800.0,
      "preview_time": 100.0,
      "file_time": 0.0,
      "video_file": "/sdcard/DCIM/video_001.mp4"
    }
  ]
}
```

### 11.3 转换时间戳

```http
POST /api/v1/sessions/{session_id}/convert-time
Authorization: Bearer {token}
Content-Type: application/json

{
  "from_domain": "preview",
  "to_domain": "file",
  "timestamp": 125.45
}
```

**响应**:
```json
{
  "from": {
    "domain": "preview",
    "timestamp": 125.45
  },
  "to": {
    "domain": "file",
    "timestamp": 25.45
  },
  "sync_id": "sync-uuid"
}
```

---

## 12. 统计与分析 API

### 12.1 会话统计

```http
GET /api/v1/sessions/{session_id}/stats
Authorization: Bearer {token}
```

**响应**:
```json
{
  "session_id": "session-uuid",
  "duration_seconds": 200.0,
  "candidates_total": 8,
  "candidates_by_status": {
    "confirmed": 2,
    "preliminary_match": 1,
    "needs_review": 3,
    "rejected": 2
  },
  "triggers_count": {
    "approach_and_stay": 5,
    "handoff_geometry": 2,
    "periodic_sample": 1
  },
  "avg_review_latency_seconds": 1.8,
  "cost_usd": 0.012
}
```

### 12.2 任务效果追踪

```http
GET /api/v1/tasks/{task_id}/effectiveness
Authorization: Bearer {token}
```

**响应**:
```json
{
  "task_id": "task-uuid",
  "versions": [
    {
      "version": 1,
      "sessions_count": 5,
      "false_positives": 8,
      "false_negatives": 2,
      "user_corrections": 10
    },
    {
      "version": 2,
      "sessions_count": 3,
      "false_positives": 3,
      "false_negatives": 1,
      "user_corrections": 4
    }
  ],
  "improvement": {
    "false_positive_reduction": 62.5,
    "false_negative_reduction": 50.0
  }
}
```

---

## 13. 错误码

| 错误码 | HTTP 状态 | 说明 |
|-------|----------|------|
| `invalid_token` | 401 | Token 无效或过期 |
| `device_not_found` | 404 | 设备未找到 |
| `task_not_supported` | 400 | 任务超出支持范围 |
| `session_not_active` | 400 | 会话未激活 |
| `candidate_not_found` | 404 | 候选事件未找到 |
| `model_timeout` | 503 | 模型 API 超时 |
| `video_file_not_found` | 404 | 视频文件未找到 |
| `time_mapping_missing` | 400 | 时间映射缺失 |
| `export_failed` | 500 | 导出失败 |
| `rate_limit_exceeded` | 429 | 请求频率超限 |

**错误响应格式**:
```json
{
  "error": "model_timeout",
  "message": "模型 API 在 30 秒内未响应",
  "details": {
    "candidate_id": "candidate-uuid",
    "retry_count": 3
  },
  "timestamp": "2026-09-18T10:16:10Z"
}
```

---

## 14. 速率限制

| 端点 | 限制 | 窗口 |
|-----|------|------|
| POST /api/v1/tasks | 10 次 | 1 分钟 |
| POST /api/v1/sessions | 20 次 | 1 小时 |
| POST /api/v1/candidates | 100 次 | 1 分钟 |
| POST /api/v1/feedback | 50 次 | 1 分钟 |
| GET /api/v1/* | 1000 次 | 1 分钟 |

**超限响应**:
```http
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1726666860
Retry-After: 60

{
  "error": "rate_limit_exceeded",
  "message": "请求频率超限，请在 60 秒后重试"
}
```

---

## 15. 数据模型总结

### 核心实体关系

```
User
  ├── Device
  ├── Task (多版本)
  │     ├── Example (正反例)
  │     └── Session (多次会话)
  │           ├── TimeMapping (时间同步)
  │           ├── Candidate (候选事件)
  │           │     ├── ReviewResult
  │           │     ├── Feedback
  │           │     └── Export
  │           └── Stats
```

---

## 总结

本 API 规范设计遵循 RESTful 原则，通过清晰的资源层次和状态转换，支持：

1. **设备同步**：相机、手机、确认夹的时间对齐
2. **任务管理**：创建、修改、版本控制
3. **事件检测**：候选生成、复核、状态转换
4. **用户反馈**：纠正分类、示例积累
5. **实时通知**：WebSocket 推送候选和状态更新
6. **时间映射**：多时钟域转换
7. **导出控制**：全景取景、格式选择

**下一步**：完成 DB_SCHEMA.sql
