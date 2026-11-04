package com.bydmatecn.app.local

import com.bydmatecn.app.camera.AvmFrameShare

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.SurfaceTexture
import android.view.View
import android.view.Surface
import android.view.TextureView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun LocalCameraActivity.createCameraViews() {
        texture = TextureView(this).apply {
            surfaceTextureListener = this@createCameraViews
            setOnClickListener { panelVisible = !panelVisible }
        }
        secondTexture = TextureView(this).apply {
            visibility = if (dual) View.VISIBLE else View.GONE
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                    secondSurface = Surface(value)
                    AvmFrameShare.publish(3, secondTexture)
                    applyViewConfig()
                    restartPreviewIfReady()
                }
                override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) { applyViewConfig() }
                override fun onSurfaceTextureUpdated(value: SurfaceTexture) {
                    AvmFrameShare.frame(3, secondTexture)
                }
                override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                    AvmFrameShare.unpublish(secondTexture)
                    closePreview("第二路 Surface 销毁")
                    secondSurface?.release(); secondSurface = null
                    return true
                }
            }
        }
        ui.title = "行车记录仪与驻车哨兵"
        ui.subtitle = "自动启停 · 循环分段 · 事件保护 · 本地回看"
        ui.status = status
        ui.onBack = { finish() }
        addAutomaticRecorderControls()
        ui.section("手动预览与标定")

        addMirrorControls()
    }

internal fun LocalCameraActivity.addMirrorControls() {
        ui.section("预览与画面")
        cameraButton("打开预览") { startPreview() }
        cameraButton("选择固件 AVM 通道 1～4（需标定方位）") {
            LocalDialogBuilder(this).setTitle("通道号不等于左/右方位")
                .setItems(arrayOf("VIEW_CHANNEL_1", "VIEW_CHANNEL_2", "VIEW_CHANNEL_3", "VIEW_CHANNEL_4")) { _, which ->
                    val reopen = open
                    AvmFrameShare.unpublish(texture)
                    closePreview("切换通道")
                    index = which + 1
                    dual = false
                    secondTexture.visibility = View.GONE
                    saveViewConfig()
                    if (reopen) requestPreviewRestart()
                    else status.text = "已选择通道 $index"
                }.show()
        }
        cameraButton("单路 / 双路（索引 2 + 3）") {
            val reopen = open
            AvmFrameShare.unpublish(texture)
            AvmFrameShare.unpublish(secondTexture)
            closePreview("切换布局")
            dual = !dual
            secondTexture.visibility = if (dual) View.VISIBLE else View.GONE
            saveViewConfig()
            if (reopen) requestPreviewRestart()
            else status.text = if (dual) "已选择双路模式（通道 2 + 3）" else "已选择单路模式（通道 $index）"
        }
        cameraButton("全屏预览（点击主画面恢复面板）") { panelVisible = false }
        cameraButton("水平镜像") {
            viewConfig = viewConfig.copy(horizontalFlip = !viewConfig.horizontalFlip)
            applyViewConfig(); saveViewConfig()
        }
        cameraButton("旋转 90°") {
            viewConfig = viewConfig.copy(rotation = (viewConfig.rotation + 90) % 360)
            applyViewConfig(); saveViewConfig()
        }
        ui.slider(LocalSlider(viewConfig.zoomPercent - 100, title = "画面缩放 100%～200%"), changed = { progress ->
            viewConfig = viewConfig.copy(zoomPercent = 100 + progress); applyViewConfig(); saveViewConfig()
        })

        addCaptureControls()

    }

internal fun LocalCameraActivity.addCaptureControls() {
        ui.section("录像与相册")
        cameraButton("截图（原始帧，双路并排）") { capture() }
        cameraButton("开始 / 停止帧录像（1 FPS）") {
            if (open && !videoRecording) { recording = !recording; status.text = if (recording) "帧录像中（无音频）" else "帧录像已停止" }
            else status.text = "请先打开预览并停止 MP4 录像"
        }
        cameraButton("开始 / 停止 MP4 录像（5 FPS / 每分钟分段）") {
            if (videoRecording) stopVideo() else startVideo()
        }
        cameraButton("驻车事件记录：开门触发 / 关闭") {
            if (!open || driving) { status.text = "先打开驻车预览，行驶模式不启用开门事件"; return@cameraButton }
            eventMode = !eventMode; previousDoors = null
            if (eventMode && !videoRecording) startVideo()
            status.text = if (eventMode) "驻车事件记录开启：有效开门变化保存前后录像，不保持熄火供电" else "开门事件触发关闭"
        }
        cameraButton("标记录像事件（保护上一段与当前段）") { bookmarkEvent("用户标记") }
        cameraButton("截图与录像相册") { startActivity(android.content.Intent(this, LocalMediaActivity::class.java)) }
        cameraButton("导出全部帧 ZIP") { export.launch("BYDMateCN-local-frames.zip") }
        cameraButton("关闭") { closePreview("用户关闭") }
        installLocalContent(ui) { CameraLayout() }
        observeCamera()
    }

@Composable
private fun LocalCameraActivity.CameraLayout() {
    val wide = LocalConfiguration.current.screenWidthDp >= 650
    if (wide) {
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            LocalPanel(ui, Modifier.width(if (panelVisible) 560.dp else 0.dp).fillMaxHeight())
            CameraPreviews(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            LocalPanel(ui, if (panelVisible) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(0.dp))
            CameraPreviews(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun LocalCameraActivity.CameraPreviews(modifier: Modifier) {
    Row(modifier) {
        AndroidView(factory = { texture }, modifier = Modifier.weight(1f).fillMaxHeight())
        // Keep the second surface mounted so a UI layout change never silently destroys a live vendor target.
        AndroidView(factory = { secondTexture }, modifier = Modifier.weight(if (dual) 1f else .001f).fillMaxHeight(),
            update = { it.visibility = if (dual) View.VISIBLE else View.GONE })
    }
}

internal fun LocalCameraActivity.observeCamera() {
        lifecycleScope.launch {
            factoryCamera.active.collect { active ->
                LocalFileLog.get(this@observeCamera).record(
                    "camera/share",
                    "原厂相机状态变化：active=$active；保持当前 AVM 订阅，由帧超时检测决定是否释放",
                )
            }
        }
        lifecycleScope.launch {
            while (true) {
                delay(1000)
                if (open) pollCameraState()
            }
        }
        lifecycleScope.launch {
            while (true) {
                delay(200)
                if (open && resumed && videoRecording) submitVideoFrame()
            }
        }

    }

internal fun LocalCameraActivity.cameraButton(label: String, action: () -> Unit) {
    ui.button(label) { LocalFileLog.get(this).record("camera/action", label); action() }
}

private suspend fun LocalCameraActivity.pollCameraState() {
                    val snapshot = kotlinx.coroutines.withTimeoutOrNull(2500) { runCatching { vehicle.readSnapshot() }.getOrNull() }
                    if (!localRecordingAllowed(snapshot?.gear, snapshot?.speed, driving)) {
                        closePreview("车辆状态未知或不满足当前预览安全条件")
                    } else {
                        if (lastFrameMs > 0 && android.os.SystemClock.elapsedRealtime()-lastFrameMs > 5000) {
                            closePreview("相机5秒未更新，停止录像并释放资源")
                        } else {
                            if (recording) capture()
                            updateDoorEvents(snapshot)
                        }
                    }

}

private fun LocalCameraActivity.updateDoorEvents(snapshot: com.bydmatecn.app.data.remote.DiParsData?) {
                            val doors = listOf(snapshot?.doorFL, snapshot?.doorFR, snapshot?.doorRL, snapshot?.doorRR)
                            val monitoring = eventMode && videoRecording
                            if (monitoring && !driving) {
                                val old = previousDoors
                                val validDoors = doors.all { it in 0..1 }
                                if (old != null && validDoors && doors.indices.any { doorOpened(old[it], doors[it]) }) bookmarkEvent("驻车开门事件")
                                previousDoors = if (doors.all { it in 0..1 }) doors else null
                            }
}

private fun doorOpened(before: Int?, after: Int?): Boolean = before == 0 && after == 1
