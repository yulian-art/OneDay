# feature-recording

录制与媒体功能模块：**录制控制、预览流、播放器、下载与导出**。

依赖 `core-media` 承担时间映射/分片合并/解码等基础设施，本模块只做
SDK 适配、状态编排与界面。

---

## 分层

```
ui/            RecordingScreen（Compose）
viewmodel/     RecordingViewModel —— 只做编排与状态汇聚
├── recording/ RecordingController   —— 录制链路（唯一入口）
├── preview/   PreviewStreamController —— 分析链路（与录制完全隔离）
├── download/  MediaDownloader
├── export/    MediaExporter
├── player/    PanoramaPlayerController
└── device/    SDK 适配层（窄接口 + Insta 实现）
```

**device/ 是唯一接触 Insta360 SDK 的地方。** 每个能力都定义了一个窄接口
（`RecordingDeviceApi` / `PreviewStreamApi` / `MediaFileApi` / `MediaWorkApi`），
Insta 实现只是薄薄一层适配。这样状态机可以在 JVM 上用假实现穷举验证，
不需要真机 —— 对「真机测试成本高」的黑客松项目很关键。

---

## 1. 录制控制（`recording/`）

### 命令 ≠ 事实

方案文档「04 系统结构与运行状态」：

> | 正在录制 | 设备已确认录制状态 |

`startCapture()` 返回成功**只说明命令被接受**。所以状态机把两者分开：

```kotlin
Idle ──start()──▶ Arming ──onCaptureWorking──▶ Recording
                    │                            │
                    │ onCaptureError             │ stop()
                    ▼                            ▼
                  Error ◀───────────────  Stopping
                    ▲                            │
                    └── onCaptureFinished ◀──────┘
```

- `Arming`：已下令，等设备确认。**此时界面上绝不能说「正在录制」**。
- `Recording`：只有走到这里才是设备确认过的事实。
- 界面上直接显示「已下令，等待相机确认…」而不是「正在录制」。

`Recording.commandLatencyUs`（命令→确认的延迟）被记录下来，因为它本身就是
「响应性」的验收数据。

### 两个真实场景的处理

**① 相机自己按了录制键。** 我们没发命令却收到 `onCaptureWorking`。
状态机把它当作外部发起的录制**并建段**，而不是忽略 ——
否则那段时间轴就没有归属，事件会全部错配。

**② 状态错配。** `reconcile()` 与设备对账：
- 设备在录但我们以为没录 → 采纳并建段；
- 设备停了但我们以为在录（漏了 `onCaptureFinished`）→ 收尾并关闭段。

这是文档验收项「断连、停录、重启后是否丢事件或错配」的直接实现。

### 错误必须显式

`onCaptureError` 转成 `Error` 并保留 `wasDeviceConfirmed`，
因为界面需要据此说不同的话：
- 出错前已在录 → 「原片可能不完整，请在相机上核实」
- 出错前没在录 → 「未产生素材」

停录命令失败尤其危险（用户以为停了但相机还在录），所以单独把
`wasDeviceConfirmed = true` 标出来。

---

## 2. 预览流（`preview/`）

管道：**分片 → 合并 → 识别编码 → 解码**

```kotlin
api.startStream()
  ↓ onStreamDataNotify(data, timestamp, type)   ← 这是分片，不是帧！
FrameAssembler.add(EncodedFragment(...))        ← 按 timestamp 合并
  ↓ AssembledAccessUnit                          ← 这才是能送解码器的完整帧
VideoCodecFormat.decide(votes)                   ← H.264 还是 H.265？
  ↓
FrameDecoder.submit(unit)                        ← 时间戳原样透传
```

### 三个必须处理的点

**① 编码格式运行时判定。** 文档：「编码格式也需按实际机型、固件判断」。
所以先收若干帧投票，确定后才建解码器，并立刻 `requestStreamIframe()` 索取关键帧 ——
没有关键帧解码器只能吐花屏。这段「探测期」在界面上如实显示为「正在探测编码格式」。

**② 丢帧后必须重新等关键帧。** 队列溢出时丢**整帧**（绝不丢分片），
然后进入等关键帧状态，并主动向相机请求 I 帧。这是「卡一下再恢复」而不是
「一路花屏到底」的区别。

**③ 与录制链路完全隔离。** 本控制器不引用 `RecordingController` 的任何东西，
所有异常都被吞掉并反映到 `PreviewStreamState.Failed`。
文档：「分析失败不应影响原始记录」。

### 界面暴露的验收数据

| 指标 | 含义 |
|---|---|
| `fragmentsIn → accessUnitsOut` | 平均每帧分片数。**> 1 就证明合并层是必需的** |
| `outOfOrderFragments` | 迟到分片数 |
| `framesForcedIncomplete` | 超时/超限强制发出的帧 |
| `decodedFrames` | 成功解码帧数 |

---

## 3. 打点（可见同步事件）

```kotlin
vm.markSyncEvent("clap")
// 立刻记录：PHONE_MONOTONIC（现在）+ PREVIEW_STREAM（最近一帧时间戳）
// 但原片位置此刻无法知道 → 计入 pendingOriginalReadings

vm.completeSyncMarkerOriginal(markerId, originalUs)
// 分析链路在原片中看到这次拍手后补齐 → preview→original 映射才成立
```

界面上显示「已打点 N 次，其中 M 次尚未定位到原片」，
并注明这是如实状态而非故障。**不假装打点立刻生效。**

---

## 4. 下载（`download/`）

- 目标文件已存在且非空时**直接复用**，避免重复拉取（文档「交付过慢」）。
- 总长度未知时 `DownloadState.fraction` 返回 `null`，界面显示不确定进度条，
  **不显示假百分比**。
- SDK 给进度回调的两个 `Long` 语义由 SDK 决定，本层只透传，不推断单位。

## 5. 导出（`export/`）

两条路径：

| 用途 | SDK 调用 | 关键参数 |
|---|---|---|
| 导出视频 | `ExporterManager.exportVideo` | `VideoExportParams` 的 `fov/yaw/pitch/distance` |
| 按时间戳抽帧 | `ExporterManager.exportVideoToImage` | `ImageExportParams` 的 `timestampList` + `index` |

- **只有 `onSuccess` 才算完成**。文档：「模型返回成功，也不等于视频导出成功」。
  命令发出 ≠ 文件已生成，界面上明确写出这一点。
- **取景显式给定**，不用 SDK 默认值。文档「08」第 7 条：
  「取景与证据冲突 —— 突出狗却裁掉主人手部」「不确定时保留较宽视角」，
  所以 `PanoramaFraming` 默认 90° 较宽视角，由调用方按阶段覆盖。

## 6. 播放器（`player/`）

SDK 的播放器本体就是 View（`InstaVideoPlayerView` 实现 `VideoPlayer`），
全景拼接是私有实现，自建 Surface 渲染拿不到拼接结果。
所以控制器只做**控制**，渲染交给 SDK 的 View，Compose 侧用 `AndroidView` 承载。

**刻意没做的事**：`BasePlayer.setConstraint(int,int,float×6)` 的 8 个参数语义
无法从公开签名确定，因此回放侧不猜测它，只暴露确定无疑的能力
（三种视图模式切换、手势、屏幕比例、播放控制）。
导出侧的取景走 `VideoExportParams` 那条语义明确的路径。

---

## 接入方式

`OneDayApplication` 中：

```kotlin
RecordingModule.initialize(this, BuildConfig.DEBUG)
```

页面：

```kotlin
RecordingModule.getRecordingScreen(onNavigateBack = { ... })
```

每次进入页面调用 `RecordingModule.newSession()` 获取干净的会话
（时间轴必须是会话级的，因为每个原片文件的时间轴都从 0 开始）。

`newSession()` 在模块未初始化时**返回 null 而不是抛异常** ——
忘记调用 `initialize()` 是很容易犯的错，不应该以崩溃收场。
页面会显示一条可读提示（见 `RecordingScreen` 的 `NotInitializedScreen`）：

```kotlin
val session = RecordingModule.newSession()
if (session == null) { /* 提示先调用 initialize() */ }
```

---

## 测试

```
./gradlew :feature-recording:testDebugUnitTest
```

`RecordingControllerTest` 用假设备覆盖：命令≠事实、外部录制采纳、
状态对账、错误语义、分段独立、打点约束、幂等、注销后不响应。

全部纯 JVM，不需要真机。

---

## 尚未在真机验证的部分

诚实起见列出（方案文档也要求区分「有依据」与「已实测」）：

- 预览流是否真的分片、`avgFragmentsPerFrame` 实际值 —— 需真机读统计
- 相机实际输出 H.264 还是 H.265
- `onCaptureTimeChanged` 的时间单位（因此只保留原始值，不换算）
- 下载进度回调两个参数的顺序与单位
- 区间导出是否需要完整下载
- `InstaCapturePlayerView` 是否需要 `setLifecycle` 才能正常渲染
