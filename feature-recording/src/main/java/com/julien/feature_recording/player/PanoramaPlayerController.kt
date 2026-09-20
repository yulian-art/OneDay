package com.julien.feature_recording.player

import com.arashivision.sdk.media.api.listener.VideoStatusListener
import com.arashivision.sdk.media.api.params.VideoPlayerParams
import com.arashivision.sdk.media.api.player.VideoPlayer
import com.arashivision.sdk.media.api.work.WorkWrapper
import com.julien.feature_recording.model.PanoramaViewMode
import com.julien.feature_recording.model.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 全景回放控制器。
 *
 * ### 为什么绑定的是 View 而不是自建渲染器
 * SDK 的播放器本体就是 View：`InstaVideoPlayerView` 实现 [VideoPlayer]，
 * `InstaCapturePlayerView` 实现 PreviewPlayer。全景拼接/投影是私有实现，
 * 自建 Surface 渲染拿不到拼接结果。所以本类只做**控制**，
 * 渲染交给 SDK 的 View，由 Compose 侧用 AndroidView 承载。
 *
 * ### 观看方向的边界
 * [BasePlayer] 暴露了 fov/yaw/pitch 的**读取**，但写入语义
 * （`setConstraint` 的 8 个参数）无法从公开签名确定。为避免猜错，
 * 回放侧只暴露确定无疑的能力：三种视图模式切换 + 手势。
 * **导出**侧的取景则用 `VideoExportParams` 的 fov/yaw/pitch 显式指定 ——
 * 那条路径的字段语义是明确的。
 *
 * 方案文档「08」第 7 条要求「按阶段构图，不确定时保留较宽视角」，
 * 因此导出取景由调用方显式给出，绝不用默认值静默裁切。
 */
class PanoramaPlayerController {

    private var player: VideoPlayer? = null

    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** 与 SDK View 绑定。Compose 侧在 AndroidView 工厂里调用。 */
    fun bind(player: VideoPlayer) {
        if (this.player === player) return
        this.player = player
        runCatching { player.setVideoStatusListener(statusListener) }
            .onFailure { Timber.w(it, "setVideoStatusListener failed") }
        runCatching { player.setGestureEnabled(true) }
        Timber.d("PanoramaPlayerController bound")
    }

    fun unbind() {
        player = null
        _state.value = PlayerState.Idle
    }

    /**
     * 准备播放一个媒体工程。
     *
     * 只设置确定语义的字段；分辨率等交由 View 自身尺寸决定。
     */
    fun prepare(work: WorkWrapper) {
        val p = player ?: run {
            _state.value = PlayerState.Failed("播放器尚未绑定")
            return
        }
        _state.value = PlayerState.Preparing
        runCatching {
            p.prepare(VideoPlayerParams(work))
        }.onFailure { error ->
            Timber.e(error, "player.prepare failed")
            _state.value = PlayerState.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    fun play() {
        runCatching { player?.play() }
            .onFailure { Timber.w(it, "player.play failed") }
    }

    fun pause() {
        runCatching { player?.pause() }
            .onFailure { Timber.w(it, "player.pause failed") }
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying()) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        runCatching { player?.seekTo(positionMs) }
            .onFailure { Timber.w(it, "seekTo failed") }
    }

    /** 切换全景 / 鱼眼 / 平面透视。 */
    fun setViewMode(mode: PanoramaViewMode) {
        val p = player ?: return
        runCatching {
            when (mode) {
                PanoramaViewMode.NORMAL -> p.switchNormalMode()
                PanoramaViewMode.FISHEYE -> p.switchFisheyeMode()
                PanoramaViewMode.PERSPECTIVE -> p.switchPerspectiveMode()
            }
        }.onFailure { Timber.w(it, "switch view mode failed") }
    }

    fun setGesturesEnabled(enabled: Boolean) {
        runCatching { player?.setGestureEnabled(enabled) }
            .onFailure { Timber.w(it, "setGestureEnabled failed") }
    }

    fun setScreenRatio(width: Int, height: Int) {
        runCatching { player?.setScreenRatio(width, height) }
            .onFailure { Timber.w(it, "setScreenRatio failed") }
    }

    /** 当前观看参数。用于把「用户觉得合适的视角」带到导出。 */
    fun currentViewing(): ViewingSnapshot? {
        val p = player ?: return null
        return runCatching {
            ViewingSnapshot(
                fov = p.getFov(),
                yaw = p.getYaw(),
                pitch = p.getPitch(),
                distance = p.getDistance(),
            )
        }.getOrNull()
    }

    fun release() {
        player = null
        _state.value = PlayerState.Idle
    }

    private val statusListener = object : VideoStatusListener {

        override fun onProgressChanged(position: Long, length: Long) {
            _state.value = PlayerState.Playing(position, length)
        }

        override fun onPlayStateChanged(isPlaying: Boolean) {
            val current = _state.value
            _state.value = if (isPlaying) {
                val pos = (current as? PlayerState.Paused)?.positionMs ?: 0L
                PlayerState.Playing(pos, (current as? PlayerState.Ready)?.durationMs ?: 0L)
            } else {
                PlayerState.Paused(
                    (current as? PlayerState.Playing)?.positionMs ?: 0L,
                )
            }
        }

        override fun onSeekComplete() {
            Timber.d("seek complete")
        }

        override fun onComplete() {
            _state.value = PlayerState.Ended
        }
    }
}

/** 一次观看视角快照。 */
data class ViewingSnapshot(
    val fov: Float,
    val yaw: Float,
    val pitch: Float,
    val distance: Float,
)
