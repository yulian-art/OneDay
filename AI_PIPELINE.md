# 它的一天 OneDay · AI 流水线设计

## 1. 流水线概览

### 1.1 三层检测架构

```
输入：视频帧流 (30fps, 1920x1080)
    ↓
┌─────────────────────────────────────────────────────────┐
│ L1: 端侧轻量检测 (MediaPipe Object Detector)            │
│ 目标：快速过滤、生成候选触发                             │
│ 延迟：< 50ms/frame                                       │
│ 成本：零（本地推理）                                     │
└─────────────────────────────────────────────────────────┘
    ↓ (触发条件满足时)
┌─────────────────────────────────────────────────────────┐
│ L2: 云端初步复核 (VLM - 预览质量)                       │
│ 目标：理解任务条件、判断连续画面                         │
│ 延迟：< 2s (批量帧)                                      │
│ 成本：中（0.5-1 刀/千次）                                │
└─────────────────────────────────────────────────────────┘
    ↓ (初步符合时)
┌─────────────────────────────────────────────────────────┐
│ L3: 云端原片复核 (VLM - 高清质量)                       │
│ 目标：在原始素材上验证关键证据                           │
│ 延迟：< 10s (含下载时间)                                 │
│ 成本：高（1-2 刀/千次）                                  │
└─────────────────────────────────────────────────────────┘
    ↓
输出：已确认事件 + 证据帧 + 置信度
```

**设计理念**：
- **漏斗式过滤**：L1 高召回（不漏），L2 平衡，L3 高精确（不错）
- **成本优化**：99% 帧由端侧处理，仅 1% 触发云端
- **用户感知**：L1 即时反馈，L2 候选提示，L3 最终确认

---

## 2. L1: 端侧轻量检测

### 2.1 检测目标

| 类别 | MediaPipe 标签 | 置信度阈值 | 用途 |
|------|---------------|-----------|------|
| 人 | person | 0.6 | 定位主人位置 |
| 狗 | dog | 0.6 | 定位宠物位置 |
| 玩具 | sports ball / teddy bear | 0.4 | 辅助判断交接（可选） |

### 2.2 触发器设计

#### 触发器 1：接近与停留
```python
class ApproachAndStayTrigger:
    def __init__(self):
        self.distance_threshold = 1.5  # 米（根据画面校准）
        self.stay_duration = 2.0       # 秒
        self.history = deque(maxlen=60)  # 2秒历史 (30fps)
    
    def process(self, frame: Frame) -> Optional[Candidate]:
        owner_box = frame.get_detection("person")
        dog_box = frame.get_detection("dog")
        
        if not owner_box or not dog_box:
            return None
        
        # 计算中心距离
        distance = self._calc_distance(owner_box, dog_box)
        self.history.append({
            "time": frame.timestamp,
            "distance": distance,
            "owner_box": owner_box,
            "dog_box": dog_box
        })
        
        # 检查是否持续接近
        if len(self.history) < 60:
            return None
        
        recent = list(self.history)[-60:]
        if all(h["distance"] < self.distance_threshold for h in recent):
            return Candidate(
                trigger="approach_and_stay",
                start_time=recent[0]["time"],
                end_time=recent[-1]["time"],
                frames=self._sample_frames(recent, n=5)
            )
        
        return None
```

#### 触发器 2：交接动作（基于几何）
```python
class HandoffTrigger:
    def __init__(self):
        self.proximity_threshold = 0.3  # 归一化距离
        self.toy_required = False       # 第一版可选
    
    def process(self, frame: Frame) -> Optional[Candidate]:
        owner_box = frame.get_detection("person")
        dog_box = frame.get_detection("dog")
        toy_box = frame.get_detection("sports ball")
        
        if not owner_box or not dog_box:
            return None
        
        # 检查边界框重叠（粗略交接检测）
        overlap = self._calc_iou(owner_box, dog_box)
        
        if overlap > self.proximity_threshold:
            return Candidate(
                trigger="handoff_geometry",
                start_time=frame.timestamp - 1.0,  # 回溯1秒
                end_time=frame.timestamp + 1.0,    # 前瞻1秒
                frames=[frame],
                metadata={"overlap": overlap, "toy_visible": toy_box is not None}
            )
        
        return None
```

#### 触发器 3：周期抽样（防漏检）
```python
class PeriodicSamplingTrigger:
    def __init__(self):
        self.interval = 10.0  # 每10秒抽样一次
        self.last_sample = 0.0
    
    def process(self, frame: Frame) -> Optional[Candidate]:
        if frame.timestamp - self.last_sample >= self.interval:
            self.last_sample = frame.timestamp
            return Candidate(
                trigger="periodic_sample",
                start_time=frame.timestamp - 2.0,
                end_time=frame.timestamp + 2.0,
                frames=[frame],
                priority="low"  # 低优先级，排队复核
            )
        return None
```

### 2.3 输出格式

```json
{
  "candidate_id": "uuid",
  "trigger": "approach_and_stay",
  "preview_start_time": 123.45,
  "preview_end_time": 125.45,
  "sampled_frames": [
    {"timestamp": 123.45, "detections": [...]},
    {"timestamp": 123.85, "detections": [...]},
    {"timestamp": 124.25, "detections": [...]},
    {"timestamp": 124.65, "detections": [...]},
    {"timestamp": 125.05, "detections": [...]}
  ],
  "metadata": {
    "avg_distance": 1.2,
    "min_confidence": 0.65
  }
}
```

---

## 3. L2: 云端初步复核

### 3.1 模型选择

**推荐模型**：
- **主力**：Qwen2-VL (7B) - 性价比高，视频理解能力强
- **备选**：Claude 3.5 Haiku - 多模态理解，成本适中
- **高精度**：GPT-4V / Claude Opus 5 - 关键任务使用

### 3.2 输入构造

#### 帧采样策略
```python
def sample_frames_for_review(candidate: Candidate, max_frames: int = 8) -> List[Frame]:
    """
    采样策略：均匀分布 + 关键时刻加密
    """
    total_duration = candidate.end_time - candidate.start_time
    base_frames = np.linspace(
        candidate.start_time, 
        candidate.end_time, 
        num=max_frames - 2
    )
    
    # 加入起始和结束帧
    key_frames = [
        candidate.start_time,          # 起始状态
        *base_frames,                  # 均匀分布
        candidate.end_time             # 结束状态
    ]
    
    return [extract_frame(t) for t in key_frames]
```

#### Prompt 模板
```python
REVIEW_PROMPT_TEMPLATE = """
你是一个宠物互动分析专家。用户定义了以下任务：

**任务定义**：
{task_definition}

**必须包含的阶段**：
{required_stages}

**排除条件**：
{exclusion_conditions}

**已知正例**：
{positive_examples}

**已知反例**：
{negative_examples}

---

现在，请分析以下连续画面（时间跨度：{duration}秒）：

[帧1 - {t1}秒] [图片]
[帧2 - {t2}秒] [图片]
...
[帧8 - {t8}秒] [图片]

请回答：
1. 是否检测到任务中要求的所有阶段？
2. 是否触发了任何排除条件？
3. 关键区域（如交接区域）是否可见且未遮挡？
4. 综合判断：符合 / 不符合 / 不确定（说明原因）

请以 JSON 格式输出：
{
  "stages_detected": ["接近", "停留"],
  "exclusions_triggered": [],
  "key_area_visible": true,
  "occlusion_level": 0.1,
  "verdict": "符合" | "不符合" | "不确定",
  "confidence": 0.85,
  "reason": "狗成功接近主人并停留超过2秒，交接区域清晰可见"
}
"""
```

### 3.3 结构化输出

```python
from pydantic import BaseModel, Field

class ReviewResult(BaseModel):
    stages_detected: List[str] = Field(description="检测到的阶段")
    exclusions_triggered: List[str] = Field(default_factory=list)
    key_area_visible: bool = Field(description="关键区域是否可见")
    occlusion_level: float = Field(ge=0.0, le=1.0, description="遮挡程度")
    verdict: Literal["符合", "不符合", "不确定"]
    confidence: float = Field(ge=0.0, le=1.0)
    reason: str = Field(min_length=10, max_length=200)
```

### 3.4 状态转换逻辑

```python
def update_candidate_status(candidate: Candidate, review: ReviewResult) -> str:
    """
    根据复核结果更新候选状态
    """
    if review.verdict == "不符合":
        return "rejected"
    
    elif review.verdict == "符合":
        if review.confidence >= 0.8 and review.key_area_visible:
            return "preliminary_match"  # 进入原片复核
        else:
            return "needs_review"       # 置信度不足，等待用户确认
    
    elif review.verdict == "不确定":
        return "needs_review"
    
    else:
        raise ValueError(f"Unknown verdict: {review.verdict}")
```

---

## 4. L3: 云端原片复核

### 4.1 高清帧提取

```python
async def extract_hd_frames(
    video_file: str, 
    time_range: Tuple[float, float],
    num_frames: int = 12
) -> List[Frame]:
    """
    从原片提取高清关键帧
    """
    # 使用 Insta360 Media SDK 的 timestampList 抽帧
    timestamps = np.linspace(time_range[0], time_range[1], num_frames)
    
    frames = []
    for ts in timestamps:
        # 抽取全景帧
        pano_frame = insta360_sdk.extract_frame(video_file, timestamp=ts)
        
        # 转换为平面视图（锁定互动区域）
        planar_frame = insta360_sdk.convert_to_planar(
            pano_frame, 
            direction=candidate.interaction_direction
        )
        
        frames.append(Frame(
            timestamp=ts,
            image=planar_frame,
            resolution="1920x1080",
            source="original_file"
        ))
    
    return frames
```

### 4.2 增强 Prompt（原片专用）

```python
HD_REVIEW_PROMPT_TEMPLATE = """
这是从原始高清视频提取的帧序列（预览初步判断为"符合"）。

请进行最终复核，重点关注：
1. **交接细节**：玩具是否真的从狗的嘴传递到主人手中？
2. **排除条件**：是否出现"只放在脚边"的情况？
3. **画面质量**：关键时刻是否清晰？是否有严重遮挡/模糊？
4. **连续性验证**：动作是否连贯？是否有跳帧或异常？

[12张高清连续帧]

最终判断（更严格）：
- "已确认"：有明确证据支持任务完成
- "待确认"：存在疑点，需要用户人工确认
- "拒绝"：明确不符合任务要求

JSON 输出（必须包含 evidence_frames）：
{
  "verdict": "已确认" | "待确认" | "拒绝",
  "confidence": 0.92,
  "evidence_frames": [3, 5, 7],  # 关键证据帧索引
  "key_observations": [
    "帧5：狗嘴中的玩具清晰可见",
    "帧7：主人手部接触玩具",
    "帧9：玩具完全转移到主人手中"
  ],
  "issues": []  # 如有问题，列出
}
"""
```

### 4.3 证据帧标注

```python
def annotate_evidence_frames(
    frames: List[Frame], 
    evidence_indices: List[int],
    observations: List[str]
) -> List[AnnotatedFrame]:
    """
    在证据帧上标注关键区域和观察结果
    """
    annotated = []
    for idx in evidence_indices:
        frame = frames[idx]
        
        # 使用 OpenCV 标注
        img = frame.image.copy()
        cv2.putText(
            img, 
            observations[idx], 
            (50, 50), 
            cv2.FONT_HERSHEY_SIMPLEX, 
            1, (0, 255, 0), 2
        )
        
        annotated.append(AnnotatedFrame(
            original=frame,
            annotated=img,
            observation=observations[idx]
        ))
    
    return annotated
```

---

## 5. 任务理解模块

### 5.1 任务解析 Prompt

```python
TASK_PARSING_PROMPT = """
用户描述："{user_input}"

请将其转换为结构化任务定义：

{
  "event_type": "return_and_deliver_toy",  // 标准事件类型
  "target_subjects": {
    "owner": "用户会在录制前确认",
    "dog": "用户会在录制前确认"
  },
  "required_stages": [
    "狗携带玩具接近主人",
    "玩具从狗转移到主人手中",
    "交接后主人和狗的互动画面"
  ],
  "exclusion_conditions": [
    "只把玩具放在主人脚边",
    "玩具掉落在地上"
  ],
  "frame_requirements": {
    "handoff_area_visible": true,
    "min_duration": 2.0,
    "max_occlusion": 0.3
  },
  "supported": true,  // 是否在支持范围内
  "unsupported_reasons": []  // 如不支持，说明原因
}

如果任务超出第一版支持范围（多主体、情绪判断、任意动作），
请设置 supported=false 并说明原因。
"""
```

### 5.2 范围校验

```python
SUPPORTED_EVENT_TYPES = {
    "approach_and_stay": {
        "description": "接近并停留",
        "min_stages": 2,
        "max_subjects": 2
    },
    "return_and_deliver_toy": {
        "description": "送回玩具并交接",
        "min_stages": 3,
        "max_subjects": 2,
        "requires_object": True
    },
    "fetch_and_return": {
        "description": "捡回物品",
        "min_stages": 3,
        "max_subjects": 2
    }
}

def validate_task_support(task_def: dict) -> Tuple[bool, List[str]]:
    """
    校验任务是否在支持范围内
    """
    issues = []
    
    # 检查事件类型
    if task_def["event_type"] not in SUPPORTED_EVENT_TYPES:
        issues.append(f"不支持的事件类型：{task_def['event_type']}")
    
    # 检查主体数量
    if len(task_def["target_subjects"]) > 2:
        issues.append("第一版仅支持一人一狗")
    
    # 检查特殊要求
    if "emotion" in str(task_def).lower():
        issues.append("暂不支持情绪判断")
    
    if "multiple_angles" in task_def.get("frame_requirements", {}):
        issues.append("第一版仅支持固定机位")
    
    return len(issues) == 0, issues
```

---

## 6. 示例学习模块

### 6.1 示例存储格式

```python
class Example(BaseModel):
    example_id: str
    task_version: int          # 绑定到任务版本
    example_type: Literal["positive", "negative"]
    frames: List[Frame]        # 3-5 张关键帧
    user_annotation: str       # 用户说明
    model_prediction: str      # 模型当时的判断（用于追踪改进）
    created_at: datetime
```

### 6.2 示例检索与组织

```python
def organize_examples_for_prompt(
    task_version: int,
    max_positives: int = 2,
    max_negatives: int = 2
) -> dict:
    """
    为模型 prompt 组织示例
    """
    examples = db.query(Example).filter(
        Example.task_version == task_version
    ).all()
    
    positives = [e for e in examples if e.example_type == "positive"][:max_positives]
    negatives = [e for e in examples if e.example_type == "negative"][:max_negatives]
    
    return {
        "positive_examples": [
            {
                "frames": e.frames,
                "annotation": e.user_annotation
            }
            for e in positives
        ],
        "negative_examples": [
            {
                "frames": e.frames,
                "annotation": e.user_annotation,
                "why_wrong": "用户明确标记为不符合"
            }
            for e in negatives
        ]
    }
```

### 6.3 示例效果追踪

```python
def track_example_impact(
    task_version: int,
    before_examples: List[Example],
    after_examples: List[Example],
    test_set: List[Candidate]
) -> dict:
    """
    评估示例对模型判断的影响
    """
    before_results = []
    after_results = []
    
    for candidate in test_set:
        # 模拟"添加示例前"的判断
        before_verdict = review_with_examples(candidate, before_examples)
        before_results.append(before_verdict)
        
        # "添加示例后"的判断
        after_verdict = review_with_examples(candidate, after_examples)
        after_results.append(after_verdict)
    
    # 计算变化
    improvements = sum(1 for b, a in zip(before_results, after_results) 
                      if b == "错误" and a == "正确")
    regressions = sum(1 for b, a in zip(before_results, after_results) 
                     if b == "正确" and a == "错误")
    
    return {
        "improvements": improvements,
        "regressions": regressions,
        "net_gain": improvements - regressions
    }
```

---

## 7. 质量检查模块

### 7.1 画面质量检查

```python
class FrameQualityChecker:
    def check(self, frame: Frame) -> QualityReport:
        """
        检查画面质量
        """
        img = frame.image
        
        # 1. 模糊检测（Laplacian 方差）
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        blur_score = cv2.Laplacian(gray, cv2.CV_64F).var()
        is_blurry = blur_score < 100
        
        # 2. 亮度检查
        brightness = np.mean(gray)
        is_too_dark = brightness < 50
        is_too_bright = brightness > 200
        
        # 3. 遮挡估计（基于检测框完整性）
        occlusion = self._estimate_occlusion(frame.detections)
        
        return QualityReport(
            is_blurry=is_blurry,
            blur_score=blur_score,
            is_too_dark=is_too_dark,
            is_too_bright=is_too_bright,
            occlusion_level=occlusion,
            usable=not (is_blurry or is_too_dark or is_too_bright or occlusion > 0.5)
        )
    
    def _estimate_occlusion(self, detections: List[Detection]) -> float:
        """
        估计关键主体的遮挡程度
        """
        owner = next((d for d in detections if d.label == "person"), None)
        dog = next((d for d in detections if d.label == "dog"), None)
        
        if not owner or not dog:
            return 1.0  # 主体缺失 = 完全遮挡
        
        # 检查边界框是否被裁切
        owner_clipped = self._is_clipped(owner.bbox)
        dog_clipped = self._is_clipped(dog.bbox)
        
        # 检查重叠（可能相互遮挡）
        overlap = self._calc_overlap(owner.bbox, dog.bbox)
        
        occlusion = 0.0
        if owner_clipped: occlusion += 0.3
        if dog_clipped: occlusion += 0.3
        if overlap > 0.5: occlusion += 0.2
        
        return min(occlusion, 1.0)
```

### 7.2 连续性检查

```python
def check_temporal_consistency(frames: List[Frame]) -> ConsistencyReport:
    """
    检查帧序列的时间连续性
    """
    issues = []
    
    # 1. 检查时间戳单调性
    timestamps = [f.timestamp for f in frames]
    if not all(timestamps[i] < timestamps[i+1] for i in range(len(timestamps)-1)):
        issues.append("时间戳非单调递增")
    
    # 2. 检查帧间隔
    intervals = [timestamps[i+1] - timestamps[i] for i in range(len(timestamps)-1)]
    avg_interval = np.mean(intervals)
    if any(abs(iv - avg_interval) > 0.5 for iv in intervals):
        issues.append("帧间隔不均匀，可能有跳帧")
    
    # 3. 检查主体位置连续性
    for i in range(len(frames) - 1):
        owner_pos_1 = frames[i].get_detection("person").center if frames[i].get_detection("person") else None
        owner_pos_2 = frames[i+1].get_detection("person").center if frames[i+1].get_detection("person") else None
        
        if owner_pos_1 and owner_pos_2:
            distance = np.linalg.norm(np.array(owner_pos_1) - np.array(owner_pos_2))
            if distance > 0.3:  # 归一化距离
                issues.append(f"帧{i}到{i+1}主体位置跳变过大")
    
    return ConsistencyReport(
        is_consistent=len(issues) == 0,
        issues=issues
    )
```

---

## 8. 成本优化策略

### 8.1 智能批处理

```python
class BatchOptimizer:
    def __init__(self):
        self.batch_window = 5.0  # 5秒内的候选合并处理
        self.pending = []
    
    async def add_candidate(self, candidate: Candidate):
        self.pending.append(candidate)
        
        # 检查是否应该触发批处理
        if len(self.pending) >= 5 or self._should_flush():
            await self.flush()
    
    async def flush(self):
        if not self.pending:
            return
        
        # 合并相邻候选（减少重复帧）
        merged = self._merge_overlapping(self.pending)
        
        # 批量调用模型
        results = await batch_review(merged)
        
        self.pending.clear()
        return results
```

### 8.2 帧复用

```python
class FrameCache:
    def __init__(self, ttl: int = 60):
        self.cache = {}
        self.ttl = ttl
    
    def get_or_extract(self, video_file: str, timestamp: float) -> Frame:
        key = f"{video_file}:{timestamp:.2f}"
        
        if key in self.cache:
            return self.cache[key]
        
        # 提取新帧
        frame = extract_frame(video_file, timestamp)
        self.cache[key] = frame
        
        # 清理过期缓存
        self._cleanup()
        
        return frame
```

### 8.3 分级模型策略

```python
MODEL_TIERS = {
    "fast": {
        "model": "qwen2-vl-7b",
        "cost_per_1k": 0.1,
        "latency": "1s",
        "use_for": ["periodic_sample", "low_priority"]
    },
    "balanced": {
        "model": "claude-3.5-haiku",
        "cost_per_1k": 0.5,
        "latency": "2s",
        "use_for": ["approach_and_stay", "preliminary_review"]
    },
    "accurate": {
        "model": "claude-opus-5",
        "cost_per_1k": 1.5,
        "latency": "5s",
        "use_for": ["handoff_detection", "final_review", "with_corrections"]
    }
}

def select_model_tier(candidate: Candidate, context: dict) -> str:
    # 低优先级 → 快速模型
    if candidate.priority == "low":
        return "fast"
    
    # 有用户纠正示例 → 高精度模型
    if context.get("has_corrections"):
        return "accurate"
    
    # 原片复核 → 高精度模型
    if candidate.status == "preliminary_match":
        return "accurate"
    
    # 默认平衡模型
    return "balanced"
```

---

## 9. 性能监控

### 9.1 指标定义

```python
class PipelineMetrics:
    def __init__(self):
        self.l1_latency = []          # L1 检测延迟
        self.l2_latency = []          # L2 复核延迟
        self.l3_latency = []          # L3 原片复核延迟
        self.false_positive_rate = 0.0  # 误报率
        self.false_negative_rate = 0.0  # 漏报率
        self.cost_per_session = 0.0     # 每会话成本
    
    def report(self) -> dict:
        return {
            "avg_l1_latency_ms": np.mean(self.l1_latency) * 1000,
            "avg_l2_latency_s": np.mean(self.l2_latency),
            "avg_l3_latency_s": np.mean(self.l3_latency),
            "false_positive_rate": self.false_positive_rate,
            "false_negative_rate": self.false_negative_rate,
            "cost_per_session_usd": self.cost_per_session
        }
```

### 9.2 A/B 测试框架

```python
class ABTestManager:
    def assign_variant(self, session_id: str) -> str:
        """
        为会话分配 A/B 变体
        """
        hash_val = int(hashlib.md5(session_id.encode()).hexdigest(), 16)
        return "A" if hash_val % 2 == 0 else "B"
    
    def compare_variants(self, metric: str) -> dict:
        """
        对比两个变体的效果
        
        示例变体：
        - A: 使用 Qwen2-VL (快速、低成本)
        - B: 使用 Claude Opus (高精度、高成本)
        """
        a_sessions = db.query(Session).filter(Session.variant == "A").all()
        b_sessions = db.query(Session).filter(Session.variant == "B").all()
        
        a_metric = np.mean([s.metrics[metric] for s in a_sessions])
        b_metric = np.mean([s.metrics[metric] for s in b_sessions])
        
        return {
            "variant_a": a_metric,
            "variant_b": b_metric,
            "improvement": (b_metric - a_metric) / a_metric * 100
        }
```

---

## 10. 失败模式与降级

| 失败场景 | 检测方法 | 降级策略 |
|---------|---------|---------|
| 模型 API 超时 | 3次重试失败 | 标记"待确认"，等待人工 |
| 模型返回格式错误 | JSON 解析失败 | 使用规则提取关键字段 |
| 画面质量差 | 模糊/遮挡检测 | 标记"待确认"，提示用户 |
| 主体检测失败 | 连续10帧无检测 | 暂停分析，保留录制 |
| 成本超限 | 实时成本追踪 | 降级到快速模型 |
| 示例冲突 | 正反例矛盾 | 提示用户解决冲突 |

---

## 总结

本 AI 流水线设计通过三层架构实现了**成本、延迟、准确率**的平衡：

1. **L1 端侧轻量检测**：高召回、零成本、即时反馈
2. **L2 云端初步复核**：理解任务、平衡成本、快速判断
3. **L3 云端原片复核**：高精度验证、证据提取、最终确认

**核心优势**：
- 99% 帧在本地处理，仅 1% 上云
- 渐进式确定性，避免误导用户
- 支持示例学习和纠正反馈
- 完整的质量检查和降级机制

**成本估算**（5分钟会话）：
- L1: $0（本地）
- L2: ~10 次调用 × $0.0005 = $0.005
- L3: ~3 次调用 × $0.0015 = $0.0045
- **总计**: ~$0.01/会话

**下一步**：完成 API_SPEC.md、DB_SCHEMA.sql
