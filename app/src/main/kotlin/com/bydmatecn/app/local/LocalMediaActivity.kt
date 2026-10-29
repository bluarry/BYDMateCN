package com.bydmatecn.app.local

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Private, offline gallery. SAF exports one selected file; no public storage permission. */
@dagger.hilt.android.AndroidEntryPoint
class LocalMediaActivity : ComponentActivity(), LocalComposeHost {
    override val ui = LocalUiState()
    @javax.inject.Inject lateinit var vehicle: com.bydmatecn.app.data.vehicle.VehicleApi
    private var resumed = false
    private var parked = false
    private lateinit var captures: LocalCaptureStore
    private lateinit var videos: LocalVideoStore
    private val message = LocalText() { LocalFileLog.get(this).record("media", it) }
    private var selected: File? = null
    private var player: VideoView? by androidx.compose.runtime.mutableStateOf(null)
    private var image: Bitmap? by androidx.compose.runtime.mutableStateOf(null)
    private var previewGeneration = 0
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = selected
        if (uri != null && file != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                withOwned(file) {
                    contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: error("无法打开导出目标")
                }
            } }
            message.text = result.fold({ "已导出：${file.name}" }, { "导出失败：${it.message}" })
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalFileLog.get(this).record("LocalMedia", "page opened")
        captures = LocalCaptureStore(File(filesDir, "local-captures"))
        videos = LocalVideoStore(File(filesDir, "local-videos"))
        installLocalContent(ui)
        videos.quarantineStale(System.currentTimeMillis())
        refresh()
        lifecycleScope.launch {
            while (true) {
                val snapshot = kotlinx.coroutines.withTimeoutOrNull(2500) { runCatching { vehicle.readSnapshot() }.getOrNull() }
                parked = firmwareAudioAllowed(snapshot?.gear, snapshot?.speed, resumed)
                if (!parked && player != null) { clearPreview(); message.text = "非驻车或车辆状态未知，视频回看已停止" }
                kotlinx.coroutines.delay(500)
            }
        }
    }
    @androidx.compose.runtime.Composable
    private fun GalleryPreview() {
        player?.let { view -> androidx.compose.runtime.key(view) { AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(300.dp)) } }
        image?.let { bitmap -> Image(bitmap.asImageBitmap(), contentDescription = "本地截图", modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) }
    }
    private fun refresh() {
        fun galleryHeader() {
            ui.controls.clear()
            ui.actionIndices.clear()
            ui.title = "本地相册"
            ui.status = message
            ui.onBack = { finish() }
            ui.controls.add { GalleryPreview() }
        }

        clearPreview()
        galleryHeader()
        val damaged = videos.incomplete()
        if (damaged.isNotEmpty()) mediaButton("隔离的未完成录像：${damaged.size}个（不作为正常MP4）") {
            LocalDialogBuilder(this).setItems(damaged.map { it.name }.toTypedArray()) { _, which ->
                LocalDialogBuilder(this).setMessage("此段录像未正常收尾。删除隔离文件？")
                    .setNegativeButton("保留", null).setPositiveButton("删除") { _, _ -> videos.deleteIncomplete(damaged[which]); refresh() }.show()
            }.show()
        }
        val files = (captures.captures() + videos.files()).sortedByDescending { it.lastModified() }
        message.text = "本地相册 · ${files.size} 个文件 · 录像无音频\n仅驻车回看，离开页面停止播放"
        // Bound initial UI allocation; older files remain available through page selection.
        val pages = files.chunked(50)
        fun page(index: Int) {
            clearPreview(); galleryHeader()
            if (damaged.isNotEmpty()) mediaButton("清理未完成录像（${damaged.size}段，保留前请自行取证）") {
                LocalDialogBuilder(this).setMessage("删除已隔离的不完整录像？不删除正常MP4。")
                    .setNegativeButton("保留", null).setPositiveButton("删除") { _, _ -> damaged.forEach { videos.deleteIncomplete(it) }; refresh() }.show()
            }
            if (index > 0) mediaButton("上一页") { page(index - 1) }
            pages.getOrNull(index).orEmpty().forEach { file ->
                mediaButton("${if (file.extension == "mp4" && videos.isProtected(file)) "[事件保护] " else ""}${file.name} · ${file.length() / 1024} KiB") {
                    selected = file
                    LocalDialogBuilder(this).setTitle(file.name).setItems(arrayOf("回看", "导出", "删除", "保护 / 解除事件保护")) { _, which ->
                        performMediaAction(file, which)
                    }.show()
                }
            }
            if (index + 1 < pages.size) mediaButton("下一页") { page(index + 1) }
        }
        page(0)
    }
    private fun preview(file: File) {
        clearPreview()
        val request = previewGeneration
        if (file.extension == "mp4") {
            if (!parked || !resumed) { message.text = "需有效 P 挡、零车速才能回看录像"; return }
            if (!videos.files().contains(file)) { message.text = "录像已被清理"; return }
            player = VideoView(this).also { view ->
                val controls = MediaController(this); controls.setAnchorView(view)
                view.setMediaController(controls)
                view.setOnErrorListener { _, what, extra -> message.text = "视频解码失败：$what / $extra"; true }
                view.setVideoPath(file.absolutePath)
                view.setOnPreparedListener { if (previewGeneration == request && parked && resumed) view.start() else view.stopPlayback() }
            }
        } else lifecycleScope.launch {
            val decoded = withContext(Dispatchers.IO) { runCatching { withOwned(file) {
                decodeCapture(file)
            } } }
            decoded.onSuccess { bitmap ->
                if (request != previewGeneration || isFinishing) bitmap.recycle()
                else { image = bitmap }
            }.onFailure { message.text = "截图读取失败：${it.message}" }
        }
    }
    private fun <T> withOwned(file: File, action: () -> T): T =
        if (file.extension == "mp4") videos.withFiles { require(file in it); action() }
        else captures.withCaptures { require(file in it); action() }
    private fun clearPreview() {
        previewGeneration++
        player?.stopPlayback(); player = null
        // Compose may still draw the previous frame until recomposition. Let GC release this bitmap.
        image = null
    }
    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { LocalFileLog.get(this).record("media", "page paused; releasing preview"); resumed = false; parked = false; clearPreview(); super.onPause() }
    override fun onDestroy() { clearPreview(); super.onDestroy() }

    private fun performMediaAction(file: File, which: Int) {
        when (which) {
                            3 -> { if (file.extension == "mp4") { if (videos.isProtected(file)) videos.unprotect(file) else videos.protect(file, "用户保护"); refresh() } }
                            0 -> preview(file)
                            1 -> export.launch(file.name)
                            2 -> LocalDialogBuilder(this).setMessage("删除私有副本？导出副本不受影响。")
                                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                                    clearPreview()
                                    val deleted = if (file.extension == "mp4") videos.delete(file) else captures.delete(file)
                                    refresh(); if (!deleted) message.text = "文件已被循环存储清理或删除失败"
                                }.show()

        }
    }
}
