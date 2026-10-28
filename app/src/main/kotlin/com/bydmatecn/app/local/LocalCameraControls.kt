package com.bydmatecn.app.local

import android.graphics.SurfaceTexture
import android.view.View
import android.widget.SeekBar
import android.widget.ScrollView
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun LocalCameraActivity.createCameraViews() {
        status = TextView(this).apply { text = "固件确认 AVM 通道 1～4；通道对应哪侧需实车标定。仅驻车预览，不作为驾驶后视镜。" }
        texture = TextureView(this).apply {
            surfaceTextureListener = this@createCameraViews
            setOnClickListener { controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        secondTexture = TextureView(this).apply {
            visibility = if (dual) View.VISIBLE else View.GONE
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                    secondSurface = Surface(value); applyViewConfig()
                }
                override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) { applyViewConfig() }
                override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                    closePreview("第二路 Surface 销毁")
                    secondSurface?.release(); secondSurface = null
                    return true
                }
            }
        }
        val wide = resources.configuration.screenWidthDp >= 650
        val layout = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; setBackgroundColor(0xFF101A2D.toInt()) }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status.setTextColor(0xFFE3EDFF.toInt())
        panel.addView(status)

        cameraButton(panel, "打开使用情况访问设置") {
            runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                .onFailure { status.text = "固件没有使用情况访问设置：${it.message}" }
        }
        cameraButton(panel, "读取后视镜状态（原始码）") {
            lifecycleScope.launch { status.text = vehicle.readRearMirrorState().toString() }
        }
        cameraButton(panel, "启用 / 关闭行驶录像模式（需已验证原厂相机优先）") {
            if (driving) { closePreview("关闭行驶模式"); driving = false }
            else android.app.AlertDialog.Builder(this).setTitle("行驶录像测试模式")
                .setMessage("必须先完成驻车通道标定及倒车/原厂360抢占测试。仅D挡允许录像；不保证熄屏/后台持续录制，不能作为安全后视镜。启用后隐藏预览并自动开始MP4。")
                .setNegativeButton("取消", null).setPositiveButton("已验证，启用") { _, _ ->
                    closePreview("切换行驶模式"); driving = true; status.text = "行驶录像已启用，请打开预览（画面隐藏）"
                }.show()
        }

        addMirrorControls(panel, layout, wide)
    }

internal fun LocalCameraActivity.addMirrorControls(panel: LinearLayout, layout: LinearLayout, wide: Boolean) {
        cameraButton(panel, "打开预览") { startPreview() }
        cameraButton(panel, "选择固件 AVM 通道 1～4（需标定方位）") {
            android.app.AlertDialog.Builder(this).setTitle("通道号不等于左/右方位")
                .setItems(arrayOf("VIEW_CHANNEL_1", "VIEW_CHANNEL_2", "VIEW_CHANNEL_3", "VIEW_CHANNEL_4")) { _, which ->
                    closePreview("切换通道"); index = which + 1; saveViewConfig()
                    status.text = "通道：$index，请重新打开；单路模式生效"
                }.show()
        }
        cameraButton(panel, "单路 / 双路（索引 2 + 3）") {
            closePreview("切换布局")
            dual = !dual
            secondTexture.visibility = if (dual) View.VISIBLE else View.GONE
            saveViewConfig()
            status.text = if (dual) "双路模式，请重新打开" else "单路模式，请重新打开"
        }
        cameraButton(panel, "全屏预览（点击主画面恢复面板）") { controls.visibility = View.GONE }
        cameraButton(panel, "水平镜像") {
            viewConfig = viewConfig.copy(horizontalFlip = !viewConfig.horizontalFlip)
            applyViewConfig(); saveViewConfig()
        }
        cameraButton(panel, "旋转 90°") {
            viewConfig = viewConfig.copy(rotation = (viewConfig.rotation + 90) % 360)
            applyViewConfig(); saveViewConfig()
        }
        panel.addView(TextView(this).apply { text = "画面缩放 100%～200%"; setTextColor(0xFFE3EDFF.toInt()) })
        panel.addView(SeekBar(this).apply {
            max = 100; progress = viewConfig.zoomPercent - 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { viewConfig = viewConfig.copy(zoomPercent = 100 + progress); applyViewConfig(); saveViewConfig() }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })

        addCaptureControls(panel, layout, wide)

    }

internal fun LocalCameraActivity.addCaptureControls(panel: LinearLayout, layout: LinearLayout, wide: Boolean) {
        cameraButton(panel, "截图（原始帧，双路并排）") { capture() }
        cameraButton(panel, "开始 / 停止帧录像（1 FPS）") {
            if (open && !videoRecording) { recording = !recording; status.text = if (recording) "帧录像中（无音频）" else "帧录像已停止" }
            else status.text = "请先打开预览并停止 MP4 录像"
        }
        cameraButton(panel, "开始 / 停止 MP4 录像（5 FPS / 每分钟分段）") {
            if (videoRecording) stopVideo() else startVideo()
        }
        cameraButton(panel, "驻车事件记录：开门触发 / 关闭") {
            if (!open || driving) { status.text = "先打开驻车预览，行驶模式不启用开门事件"; return@cameraButton }
            eventMode = !eventMode; previousDoors = null
            if (eventMode && !videoRecording) startVideo()
            status.text = if (eventMode) "驻车事件记录开启：有效开门变化保存前后录像，不保持熄火供电" else "开门事件触发关闭"
        }
        cameraButton(panel, "标记录像事件（保护上一段与当前段）") { bookmarkEvent("用户标记") }
        cameraButton(panel, "截图与录像相册") { startActivity(android.content.Intent(this, LocalMediaActivity::class.java)) }
        cameraButton(panel, "导出全部帧 ZIP") { export.launch("BYDMate-local-frames.zip") }
        cameraButton(panel, "关闭") { closePreview("用户关闭") }
        controls = ScrollView(this).apply { addView(panel) }
        layout.addView(controls, if (wide) LinearLayout.LayoutParams((300 * resources.displayMetrics.density).toInt(), -1)
            else LinearLayout.LayoutParams(-1, (220 * resources.displayMetrics.density).toInt()))
        val previews = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        previews.addView(texture, LinearLayout.LayoutParams(0, -1, 1f))
        previews.addView(secondTexture, LinearLayout.LayoutParams(0, -1, 1f))
        layout.addView(previews, if (wide) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))

        observeCamera(layout)
    }

internal fun LocalCameraActivity.observeCamera(layout: LinearLayout) {
        LocalFileLog.get(this).watch(status, "camera")
        setContentView(layout)
        lifecycleScope.launch {
            factoryCamera.active.collect { active ->
                if (active && open) closePreview("原厂相机启动，释放 AVM")
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

        private fun LocalCameraActivity.cameraButton(panel: LinearLayout, label: String, action: () -> Unit) {
            panel.addView(Button(this).apply { text = label; setOnClickListener { LocalFileLog.get(this@cameraButton).record("camera/action", label); action() } })
        }

private suspend fun LocalCameraActivity.pollCameraState() {
                    val snapshot = kotlinx.coroutines.withTimeoutOrNull(2500) { runCatching { vehicle.readSnapshot() }.getOrNull() }
                    if (!localRecordingAllowed(snapshot?.gear, snapshot?.speed, usageAllowed(), !factoryCameraClear(), driving)) {
                        closePreview("车辆状态未知/非驻车，或原厂相机占用")
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
