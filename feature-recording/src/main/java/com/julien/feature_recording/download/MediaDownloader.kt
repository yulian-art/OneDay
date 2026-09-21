package com.julien.feature_recording.download

import com.julien.feature_recording.device.MediaFileApi
import com.julien.feature_recording.model.DownloadState
import com.julien.feature_recording.model.MediaFileItem
import com.julien.feature_recording.model.MediaFileKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import timber.log.Timber

/**
 * 原片下载。
 *
 * 方案文档「08」第 8 条「交付过慢」要求「分测下载、分析、导出及人工操作时间」，
 * 因此这里把进度做成可观测状态（含字节数），而不是只给一个转圈图标。
 *
 * 另有一条重要提醒：「区间导出是否需要完整下载」会影响实际等待时间。
 * 本类只负责整文件下载；若未来要做到区间导出免全量下载，
 * 应替换 [MediaFileApi] 的实现而不是改这里。
 */
class MediaDownloader(
    private val api: MediaFileApi,
    /** 下载落盘目录（通常是 App 的 external files dir）。 */
    private val targetDirProvider: () -> File,
) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val _files = MutableStateFlow<List<MediaFileItem>>(emptyList())
    val files: StateFlow<List<MediaFileItem>> = _files.asStateFlow()

    /** 拉取相机上的文件清单。 */
    suspend fun listFiles(kind: MediaFileKind = MediaFileKind.VIDEO): Result<List<MediaFileItem>> {
        _state.value = DownloadState.Listing
        return api.listFiles(kind)
            .onSuccess {
                _files.value = it
                _state.value = DownloadState.Idle
                Timber.i("listed %d media files", it.size)
            }
            .onFailure {
                _state.value = DownloadState.Failed(
                    fileUrl = "",
                    message = "获取文件列表失败: ${it.message ?: it.javaClass.simpleName}",
                    cause = it,
                )
            }
    }

    /**
     * 下载单个文件。
     *
     * 只有在能**确认文件完整**时才复用已有文件，避免重复拉取 ——
     * 文档要求「交付过慢」要改善，重复下载是典型的浪费。
     *
     * 注意不能只判断「文件非空」：下载中断留下的半个文件长度也大于 0，
     * 若当成完整文件复用，最终产出的是损坏视频。所以：
     * - 已知 [MediaFileItem.sizeBytes] 时，必须长度完全相等才算完整；
     * - 大小未知时**不复用**，宁可重下一次，也不冒交付坏文件的风险。
     */
    suspend fun download(item: MediaFileItem, overwrite: Boolean = false): Result<String> {
        val targetDir = targetDirProvider()
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            val message = "无法创建下载目录: ${targetDir.absolutePath}"
            _state.value = DownloadState.Failed(item.url, message)
            return Result.failure(java.io.IOException(message))
        }
        val target = File(targetDir, item.name)

        if (!overwrite && target.exists() && isComplete(target, item)) {
            Timber.i("reuse verified-complete file: %s", target.absolutePath)
            _state.value = DownloadState.Done(item.url, target.absolutePath)
            return Result.success(target.absolutePath)
        }
        if (!overwrite && target.exists() && !isComplete(target, item)) {
            Timber.w(
                "existing file %s is incomplete (size=%d, expected=%s) — re-downloading",
                target.absolutePath,
                target.length(),
                item.sizeBytes?.toString() ?: "unknown",
            )
        }

        _state.value = DownloadState.Downloading(
            fileUrl = item.url,
            bytesReceived = 0L,
            totalBytes = item.sizeBytes ?: 0L,
        )

        return api.download(item.url, target.absolutePath) { transferred, total ->
            _state.value = DownloadState.Downloading(
                fileUrl = item.url,
                bytesReceived = transferred,
                totalBytes = total,
            )
        }.fold(
            onSuccess = { path ->
                val downloaded = File(path)
                if (!isComplete(downloaded, item)) {
                    // SDK 报成功但文件大小对不上：不能当作完成交付
                    val message = "下载完成但文件大小不符（实际 ${downloaded.length()}，" +
                        "期望 ${item.sizeBytes ?: "未知"}），可能是传输中断"
                    Timber.e("download size mismatch: %s", message)
                    _state.value = DownloadState.Failed(item.url, message)
                    return@fold Result.failure(java.io.IOException(message))
                }
                _state.value = DownloadState.Done(item.url, path)
                Timber.i("downloaded %s -> %s", item.url, path)
                Result.success(path)
            },
            onFailure = { error ->
                _state.value = DownloadState.Failed(
                    fileUrl = item.url,
                    message = "下载失败: ${error.message ?: error.javaClass.simpleName}",
                    cause = error,
                )
                Result.failure(error)
            },
        )
    }

    /**
     * 判断本地文件是否可以认为已经完整。
     * 大小未知时保守地返回 false —— 宁可重下，不可交付坏文件。
     */
    private fun isComplete(file: File, item: MediaFileItem): Boolean {
        if (!file.exists()) return false
        val actual = file.length()
        if (actual <= 0L) return false
        val expected = item.sizeBytes
        return expected != null && expected > 0L && actual == expected
    }

    fun reset() {
        _state.value = DownloadState.Idle
    }

    fun clearError() {
        if (_state.value is DownloadState.Failed) _state.value = DownloadState.Idle
    }
}
