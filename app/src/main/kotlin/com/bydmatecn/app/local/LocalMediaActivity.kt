package com.bydmatecn.app.local

import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
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
class LocalMediaActivity : ComponentActivity() {
    @javax.inject.Inject lateinit var vehicle: com.bydmatecn.app.data.vehicle.VehicleApi
    private var resumed = false
    private var parked = false
    private lateinit var captures: LocalCaptureStore
    private lateinit var videos: LocalVideoStore
    private lateinit var list: LinearLayout
    private lateinit var message: TextView
    private var selected: File? = null
    private var player: VideoView? = null
    private var image: Bitmap? = null
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
        captures = LocalCaptureStore(File(filesDir, "local-captures"))
        videos = LocalVideoStore(File(filesDir, "local-videos"))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(0xFF101A2D.toInt()) }
        message = TextView(this).apply { setTextColor(0xFFE3EDFF.toInt()); textSize = 18f }
        LocalFileLog.get(this).watch(message, "media-gallery")
        setContentView(ScrollView(this).apply { addView(list) })
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
    private fun button(text: String, action: () -> Unit) {
        list.addView(Button(this).apply { this.text = text; setOnClickListener { action() } })
    }
    private fun refresh() {
        clearPreview()
        list.removeAllViews(); list.addView(message)
        button("返回") { finish() }
        val damaged = videos.incomplete()
        if (damaged.isNotEmpty()) button("隔离的未完成录像：${damaged.size}个（不作为正常MP4）") {
            AlertDialog.Builder(this).setItems(damaged.map { it.name }.toTypedArray()) { _, which ->
                AlertDialog.Builder(this).setMessage("此段录像未正常收尾。删除隔离文件？")
                    .setNegativeButton("保留", null).setPositiveButton("删除") { _, _ -> videos.deleteIncomplete(damaged[which]); refresh() }.show()
            }.show()
        }
        val files = (captures.captures() + videos.files()).sortedByDescending { it.lastModified() }
        message.text = "本地相册 · ${files.size} 个文件 · 录像无音频\n仅驻车回看，离开页面停止播放"
        // Bound initial UI allocation; older files remain available through page selection.
        val pages = files.chunked(50)
        fun page(index: Int) {
            clearPreview(); list.removeAllViews(); list.addView(message)
            button("返回") { finish() }
            if (damaged.isNotEmpty()) button("清理未完成录像（${damaged.size}段，保留前请自行取证）") {
                AlertDialog.Builder(this).setMessage("删除已隔离的不完整录像？不删除正常MP4。")
                    .setNegativeButton("保留", null).setPositiveButton("删除") { _, _ -> damaged.forEach { videos.deleteIncomplete(it) }; refresh() }.show()
            }
            if (index > 0) button("上一页") { page(index - 1) }
            pages.getOrNull(index).orEmpty().forEach { file ->
                button("${if (file.extension == "mp4" && videos.isProtected(file)) "[事件保护] " else ""}${file.name} · ${file.length() / 1024} KiB") {
                    selected = file
                    AlertDialog.Builder(this).setTitle(file.name).setItems(arrayOf("回看", "导出", "删除", "保护 / 解除事件保护")) { _, which ->
                        performMediaAction(file, which)
                    }.show()
                }
            }
            if (index + 1 < pages.size) button("下一页") { page(index + 1) }
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
                list.addView(view, 0, LinearLayout.LayoutParams(-1, (300 * resources.displayMetrics.density).toInt()))
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
                else { image = bitmap; list.addView(ImageView(this@LocalMediaActivity).apply { setImageBitmap(bitmap); adjustViewBounds = true }, 0) }
            }.onFailure { message.text = "截图读取失败：${it.message}" }
        }
    }
    private fun <T> withOwned(file: File, action: () -> T): T =
        if (file.extension == "mp4") videos.withFiles { require(file in it); action() }
        else captures.withCaptures { require(file in it); action() }
    private fun clearPreview() {
        previewGeneration++
        player?.let { it.stopPlayback(); list.removeView(it) }; player = null
        for (i in list.childCount - 1 downTo 0) if (list.getChildAt(i) is ImageView) list.removeViewAt(i)
        image?.recycle(); image = null
    }
    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { LocalFileLog.get(this).record("media", "page paused; releasing preview"); resumed = false; parked = false; clearPreview(); super.onPause() }
    override fun onDestroy() { clearPreview(); super.onDestroy() }

    private fun performMediaAction(file: File, which: Int) {
        when (which) {
                            3 -> { if (file.extension == "mp4") { if (videos.isProtected(file)) videos.unprotect(file) else videos.protect(file, "用户保护"); refresh() } }
                            0 -> preview(file)
                            1 -> export.launch(file.name)
                            2 -> AlertDialog.Builder(this).setMessage("删除私有副本？导出副本不受影响。")
                                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                                    clearPreview()
                                    val deleted = if (file.extension == "mp4") videos.delete(file) else captures.delete(file)
                                    refresh(); if (!deleted) message.text = "文件已被循环存储清理或删除失败"
                                }.show()

        }
    }
}
