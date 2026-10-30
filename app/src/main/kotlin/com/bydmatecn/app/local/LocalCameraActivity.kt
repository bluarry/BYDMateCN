package com.bydmatecn.app.local

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.bydmatecn.app.camera.AvmCameraProbe
import com.bydmatecn.app.data.camera.CameraStateMonitor
import com.bydmatecn.app.data.vehicle.VehicleApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Manual parked-only preview. No boot start, microphone, cloud or guessed vehicle writes. */
@AndroidEntryPoint
class LocalCameraActivity : ComponentActivity(), TextureView.SurfaceTextureListener, LocalComposeHost {
    override val ui = LocalUiState()
    internal var panelVisible by androidx.compose.runtime.mutableStateOf(true)
    @Inject lateinit var vehicle: VehicleApi
    @Inject lateinit var factoryCamera: CameraStateMonitor
    internal val probe = AvmCameraProbe(eventSink = { LocalFileLog.get(this).record("avm", it) })
    internal lateinit var texture: TextureView
    internal lateinit var secondTexture: TextureView
    internal var secondSurface: Surface? = null
    internal var eventMode = false
    internal var previousDoors: List<Int?>? = null
    internal var lastEventMs = -30_000L
    internal var lastFrameMs = 0L
    internal lateinit var videos: LocalVideoStore
    internal var driving = false
    internal var dual by androidx.compose.runtime.mutableStateOf(false)
    internal var viewConfig = MirrorViewConfig()
    internal val status = LocalText("AVM 通道 1～4 已通过实车预览；可按画面标定通道方位。") { LocalFileLog.get(this).record("camera", it) }
    internal lateinit var store: LocalCaptureStore
    internal var surface: Surface? = null
    internal var open = false
    internal var recording = false
    internal var videoRecording = false
    internal var videoSession = 0
    internal val videoWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    internal val videoFrameBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    internal lateinit var videoRecorder: LocalVideoRecorder
    internal var segmentStarted = 0L // worker-confined
    internal var saving = false
    internal var resumed = false
    internal var opening = false
    internal var pendingPreviewRestart = false
    internal var generation = 0
    internal var index = 2
    internal val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status.text = if (granted) "相机权限已授予，请再点击打开" else "相机权限被拒绝"
    }
    internal val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    store.withCaptures { frames ->
                        contentResolver.openOutputStream(uri)?.use { output ->
                            ZipOutputStream(output).use { zip ->
                                frames.forEach { file ->
                                    zip.putNextEntry(ZipEntry(file.name))
                                    file.inputStream().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
                        } ?: error("无法打开导出文件")
                    }
                }
            }
            status.text = result.fold({ "已导出 JPEG 帧序列" }, { "导出失败：${it.message}" })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalFileLog.get(this).record("LocalCamera", "page opened")
        store = LocalCaptureStore(java.io.File(filesDir, "local-captures"))
        videos = LocalVideoStore(java.io.File(filesDir, "local-videos"))
        videoRecorder = LocalVideoRecorder(videos)
        val preferences = getSharedPreferences("local-mirror", Context.MODE_PRIVATE)
        dual = preferences.getBoolean("dual", false)
        index = preferences.getInt("index", 2).takeIf { it in 1..4 } ?: 2
        viewConfig = MirrorViewConfig(
            preferences.getBoolean("flip", false),
            preferences.getInt("zoom", 100).coerceIn(100, 200),
            preferences.getInt("rotation", 0).takeIf { it in setOf(0, 90, 180, 270) } ?: 0,
        )

        createCameraViews()
    }

    // Independent of TrackingService: a dormant monitor must not be interpreted as "camera clear".

    override fun onResume() {
        super.onResume(); resumed = true
        LocalMediaCommands.camera = { command ->
            when (command) {
                "snapshot" -> if (open && !saving) { capture(); true } else false
                "record_start" -> if (open && !videoRecording) { startVideo(); true } else false
                "record_stop" -> { stopVideo(); true }
                else -> false
            }
        }
    }
    override fun onPause() { LocalMediaCommands.camera = null; resumed = false; closePreview("页面不可见，释放相机"); super.onPause() }
    override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(value)
        applyViewConfig()
        restartPreviewIfReady()
    }
    override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) { applyViewConfig() }
    override fun onSurfaceTextureUpdated(value: SurfaceTexture) { lastFrameMs = android.os.SystemClock.elapsedRealtime() }
    override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
        closePreview("Surface 销毁")
        surface?.release(); surface = null
        return true
    }

    internal var pendingEventReason: String? = null // worker-confined
    override fun onDestroy() {
        stopVideo()
        videoWorker.execute { videoRecorder.abort() }
        videoWorker.shutdown()
        super.onDestroy()
    }
}
