package com.julien.feature_recording.device

import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.core.model.ConnectType
import com.arashivision.sdk.camera.core.model.file.MediaFileType
import com.julien.feature_recording.model.MediaFileItem
import com.julien.feature_recording.model.MediaFileKind
import timber.log.Timber

/**
 * 相机文件能力（窄接口）。
 *
 * 方案文档「05」特别提醒：
 * 「连接能力与文件传输能力要分别核验。不能因为某通道能控制相机，
 *   就默认它能下载视频」。
 * 因此下载被单独建模成一个接口，便于在真机上单独验证。
 */
interface MediaFileApi {

    suspend fun listFiles(kind: MediaFileKind): Result<List<MediaFileItem>>

    /**
     * 下载一个媒体文件。
     *
     * @param onProgress 进度回调。两个参数按 SDK 语义为
     *        `(已传输, 总大小)`；**单位与顺序均由 SDK 决定**，
     *        因此实现方不得据此推断单位，只做透传。
     * @return 本地文件路径
     */
    suspend fun download(
        url: String,
        savePath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<String>
}

/** [MediaFileApi] 的 Insta360 实现。 */
class InstaMediaFileApi(
    private val camera: CameraDevice = CameraDevice.get(ConnectType.BLE),
) : MediaFileApi {

    override suspend fun listFiles(kind: MediaFileKind): Result<List<MediaFileItem>> = runCatching {
        val sdkType = MediaFileType.valueOf(kind.sdkName)
        // 第二个参数是「是否升序」；默认按时间升序，方便按拍摄顺序展示
        val urls = camera.file.listMediaFiles(sdkType, true).getOrThrow()
        urls.map { url ->
            MediaFileItem(
                url = url,
                name = url.substringAfterLast('/'),
                kind = kind,
            )
        }
    }.onFailure { Timber.e(it, "listFiles failed for %s", kind) }

    override suspend fun download(
        url: String,
        savePath: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<String> = runCatching {
        camera.file.downloadMediaFile(url, savePath) { transferred, total ->
            runCatching { onProgress(transferred, total) }
        }.getOrThrow()
    }.onFailure { Timber.e(it, "download failed: %s", url) }
}
