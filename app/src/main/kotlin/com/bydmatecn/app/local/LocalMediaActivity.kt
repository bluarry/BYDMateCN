package com.bydmatecn.app.local

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Offline in-app video browser. Dashcam and sentry recordings are intentionally isolated. */
@dagger.hilt.android.AndroidEntryPoint
class LocalMediaActivity : ComponentActivity(), LocalComposeHost {
    override val ui = LocalUiState()
    @javax.inject.Inject lateinit var vehicle: com.bydmatecn.app.data.vehicle.VehicleApi

    private var resumed = false
    private var parked = false
    private lateinit var mode: VehicleRecorderMode
    private lateinit var videos: LocalVideoStore
    private val message = LocalText { LocalFileLog.get(this).record("media", it) }
    private val files = mutableStateListOf<File>()
    private var selected: File? by androidx.compose.runtime.mutableStateOf(null)
    private var player: VideoView? by androidx.compose.runtime.mutableStateOf(null)
    private var previewGeneration = 0

    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val file = selected
        if (uri != null && file != null) lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = runCatching {
                videos.withFiles { owned ->
                    require(file in owned)
                    contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                        ?: error("无法打开导出目标")
                }
            }
            runOnUiThread {
                message.text = result.fold({ "已导出：${file.name}" }, { "导出失败：${it.message}" })
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = if (intent.getStringExtra(EXTRA_MODE) == LocalCameraActivity.MODE_SENTRY) {
            VehicleRecorderMode.PARKING_SENTRY
        } else {
            VehicleRecorderMode.DASHCAM
        }
        val quota = VehicleRecorderPreferences.quotaMb(this, mode).toLong() * 1024 * 1024
        videos = LocalVideoStore(LocalVideoStorage.directory(this, mode), quota)
        videos.quarantineStale(System.currentTimeMillis())
        LocalFileLog.get(this).record("LocalMedia", "page opened mode=$mode; directory=${LocalVideoStorage.directory(this, mode).path}")

        ui.title = if (mode == VehicleRecorderMode.PARKING_SENTRY) "哨兵录像" else "行车录像"
        ui.subtitle = "本地视频 · 应用内预览"
        ui.status = message
        ui.onBack = { finish() }
        ui.topTabs = true
        ui.visualStyle = LocalVisualStyle.EXTERIOR_AUDIO
        ui.section("已录视频")
        ui.controls.add {
            VideoLibraryWorkspace(
                files = files,
                selected = selected,
                protected = { videos.isProtected(it) },
                preview = { VideoPreview() },
                onPlay = { preview(it) },
                onExport = { selected = it; export.launch(it.name) },
                onDelete = { delete(it) },
                onProtect = { toggleProtection(it) },
                onRefresh = { refresh() },
            )
        }
        installLocalContent(ui)
        refresh()
        lifecycleScope.launch {
            while (true) {
                val snapshot = kotlinx.coroutines.withTimeoutOrNull(2500) { runCatching { vehicle.readSnapshot() }.getOrNull() }
                parked = firmwareAudioAllowed(snapshot?.gear, snapshot?.speed, resumed)
                if (!parked && player != null) {
                    clearPreview()
                    message.text = "非驻车或车辆状态未知，视频预览已停止"
                }
                delay(500)
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun VideoPreview() {
        player?.let { view ->
            androidx.compose.runtime.key(view) {
                AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(340.dp))
            }
        }
    }

    private fun refresh() {
        clearPreview()
        selected = null
        files.clear()
        files.addAll(videos.files().sortedByDescending { it.lastModified() })
        val damaged = videos.incomplete().size
        val kind = if (mode == VehicleRecorderMode.PARKING_SENTRY) "哨兵" else "行车"
        message.text = "${kind}录像 ${files.size} 段 · 未完成 $damaged 段 · 仅驻车时可预览"
    }

    private fun preview(file: File) {
        clearPreview()
        if (!parked || !resumed) {
            message.text = "需有效 P 挡、零车速才能预览录像"
            return
        }
        if (file !in videos.files()) {
            message.text = "录像已被循环存储清理"
            refresh()
            return
        }
        selected = file
        val request = previewGeneration
        player = VideoView(this).also { view ->
            val controls = MediaController(this)
            controls.setAnchorView(view)
            view.setMediaController(controls)
            view.setOnErrorListener { _, what, extra ->
                message.text = "视频解码失败：$what / $extra"
                true
            }
            view.setVideoPath(file.absolutePath)
            view.setOnPreparedListener {
                if (previewGeneration == request && parked && resumed) view.start() else view.stopPlayback()
            }
        }
        message.text = "正在预览：${file.name}"
    }

    private fun delete(file: File) {
        clearPreview()
        selected = null
        val deleted = videos.delete(file)
        refresh()
        if (!deleted) message.text = "录像已被循环清理或删除失败"
    }

    private fun toggleProtection(file: File) {
        val changed = if (videos.isProtected(file)) videos.unprotect(file) else videos.protect(file, "用户保护")
        if (changed) {
            message.text = if (videos.isProtected(file)) "已保护：${file.name}" else "已解除保护：${file.name}"
        } else {
            message.text = "录像状态已变化，请刷新"
        }
    }

    private fun clearPreview() {
        previewGeneration++
        player?.stopPlayback()
        player = null
    }

    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() {
        LocalFileLog.get(this).record("media", "page paused; releasing preview")
        resumed = false
        parked = false
        clearPreview()
        super.onPause()
    }
    override fun onDestroy() { clearPreview(); super.onDestroy() }

    companion object { const val EXTRA_MODE = "recording_mode" }
}
