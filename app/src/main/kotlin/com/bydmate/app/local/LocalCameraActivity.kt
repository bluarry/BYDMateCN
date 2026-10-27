package com.bydmate.app.local

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.graphics.Matrix
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.ScrollView
import android.os.Bundle
import android.os.Process
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.bydmate.app.camera.AvmCameraProbe
import com.bydmate.app.data.camera.CameraStateMonitor
import com.bydmate.app.data.vehicle.VehicleApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Manual parked-only preview. No boot start, microphone, cloud or guessed vehicle writes. */
@AndroidEntryPoint
class LocalCameraActivity : ComponentActivity(), TextureView.SurfaceTextureListener {
    @Inject lateinit var vehicle: VehicleApi
    @Inject lateinit var factoryCamera: CameraStateMonitor
    private val probe = AvmCameraProbe()
    private lateinit var texture: TextureView
    private lateinit var secondTexture: TextureView
    private var secondSurface: Surface? = null
    private var dual = false
    private var viewConfig = MirrorViewConfig()
    private lateinit var controls: ScrollView
    private lateinit var status: TextView
    private lateinit var store: LocalCaptureStore
    private var surface: Surface? = null
    private var open = false
    private var recording = false
    private var saving = false
    private var resumed = false
    private var opening = false
    private var generation = 0
    private var index = 2
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status.text = if (granted) "相机权限已授予，请再点击打开" else "相机权限被拒绝"
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
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
        store = LocalCaptureStore(java.io.File(filesDir, "local-captures"))
        val preferences = getSharedPreferences("local-mirror", Context.MODE_PRIVATE)
        dual = preferences.getBoolean("dual", false)
        index = preferences.getInt("index", 2).takeIf { it in 2..3 } ?: 2
        viewConfig = MirrorViewConfig(
            preferences.getBoolean("flip", false),
            preferences.getInt("zoom", 100).coerceIn(100, 200),
            preferences.getInt("rotation", 0).takeIf { it in setOf(0, 90, 180, 270) } ?: 0,
        )
        status = TextView(this).apply { text = "仅驻车手动预览；默认索引 2/3 来自现有 BYDMate，非 AutoTark 动态配置" }
        texture = TextureView(this).apply {
            surfaceTextureListener = this
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
        fun button(label: String, action: () -> Unit) {
            panel.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("打开使用情况访问设置") {
            runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                .onFailure { status.text = "固件没有使用情况访问设置：${it.message}" }
        }
        button("读取后视镜状态（原始码）") {
            lifecycleScope.launch { status.text = vehicle.readRearMirrorState().toString() }
        }
        button("打开预览") { startPreview() }
        button("切换索引 2 / 3") { closePreview("切换通道"); index = if (index == 2) 3 else 2; saveViewConfig(); status.text = "通道索引：$index，请重新打开" }
        button("单路 / 双路（索引 2 + 3）") {
            closePreview("切换布局")
            dual = !dual
            secondTexture.visibility = if (dual) View.VISIBLE else View.GONE
            saveViewConfig()
            status.text = if (dual) "双路模式，请重新打开" else "单路模式，请重新打开"
        }
        button("全屏预览（点击主画面恢复面板）") { controls.visibility = View.GONE }
        button("水平镜像") {
            viewConfig = viewConfig.copy(horizontalFlip = !viewConfig.horizontalFlip)
            applyViewConfig(); saveViewConfig()
        }
        button("旋转 90°") {
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
        button("截图（原始帧，双路并排）") { capture() }
        button("开始 / 停止帧录像（1 FPS）") {
            if (open) { recording = !recording; status.text = if (recording) "帧录像中（无音频）" else "帧录像已停止" }
            else status.text = "请先打开预览"
        }
        button("导出全部帧 ZIP") { export.launch("BYDMate-local-frames.zip") }
        button("关闭") { closePreview("用户关闭") }
        controls = ScrollView(this).apply { addView(panel) }
        layout.addView(controls, if (wide) LinearLayout.LayoutParams((300 * resources.displayMetrics.density).toInt(), -1)
            else LinearLayout.LayoutParams(-1, (220 * resources.displayMetrics.density).toInt()))
        val previews = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        previews.addView(texture, LinearLayout.LayoutParams(0, -1, 1f))
        previews.addView(secondTexture, LinearLayout.LayoutParams(0, -1, 1f))
        layout.addView(previews, if (wide) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        lifecycleScope.launch {
            factoryCamera.active.collect { active ->
                if (active && open) closePreview("原厂相机启动，释放 AVM")
            }
        }
        lifecycleScope.launch {
            while (true) {
                delay(1000)
                if (open) {
                    val snapshot = runCatching { vehicle.readSnapshot() }.getOrNull()
                    if (!localCameraAllowed(snapshot?.gear, snapshot?.speed, usageAllowed(), !factoryCameraClear())) {
                        closePreview("车辆状态未知/非驻车，或原厂相机占用")
                    } else if (recording) capture()
                }
            }
        }
    }

    // Independent of TrackingService: a dormant monitor must not be interpreted as "camera clear".
    private suspend fun factoryCameraClear(): Boolean = withContext(Dispatchers.IO) {
        if (factoryCamera.active.value || !usageAllowed()) return@withContext false
        runCatching {
            val now = System.currentTimeMillis()
            val manager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val foreground = CameraStateMonitor.latestResumed(manager, now - 120_000, now)?.first
            foreground != null && foreground != "com.byd.avc"
        }.getOrDefault(false)
    }

    private fun usageAllowed(): Boolean = (getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
        .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED

    private fun startPreview() {
        if (!resumed || opening || open) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.CAMERA); return
        }
        if (dual && secondSurface == null) { status.text = "双路 Surface 尚未就绪"; return }
        val target = surface ?: run { status.text = "预览 Surface 尚未就绪"; return }
        opening = true
        val request = ++generation
        lifecycleScope.launch {
            try {
                val snapshot = vehicle.readSnapshot()
                val cameraClear = factoryCameraClear()
                if (request != generation || !resumed) return@launch
                if (!localCameraAllowed(snapshot?.gear, snapshot?.speed, usageAllowed(), !cameraClear)) {
                    status.text = "需要使用情况访问权限、有效 P 挡和零车速；原厂相机必须关闭"; return@launch
                }
                // Vendor lifetime operations are serialized on the UI thread with texture teardown.
                open = probe.discover() && if (dual) {
                    probe.openWarm(mapOf(2 to target, 3 to checkNotNull(secondSurface)))
                } else probe.open(index, target)
                if (open) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                status.text = if (open) "已绑定通道 ${if (dual) "2 + 3" else index.toString()}（有效画面需真车确认）" else probe.log.value.takeLast(4).joinToString("\n")
            } catch (error: Exception) {
                closePreview("预览启动失败：${error.message}")
            } finally { opening = false }
        }
    }

    private fun saveViewConfig() {
        getSharedPreferences("local-mirror", Context.MODE_PRIVATE).edit()
            .putBoolean("dual", dual).putInt("index", index)
            .putBoolean("flip", viewConfig.horizontalFlip).putInt("zoom", viewConfig.zoomPercent)
            .putInt("rotation", viewConfig.rotation).apply()
    }

    private fun applyViewConfig() {
        listOf(texture, secondTexture).forEach { view ->
            if (view.width > 0 && view.height > 0) {
                val transform = Matrix()
                transform.postScale(viewConfig.scaleX(), viewConfig.scaleY(), view.width / 2f, view.height / 2f)
                transform.postRotate(viewConfig.rotation.toFloat(), view.width / 2f, view.height / 2f)
                view.setTransform(transform)
            }
        }
    }

    private fun capture() {
        if (!open || saving) return
        try { captureFrame() }
        catch (_: OutOfMemoryError) { recording = false; status.text = "截图内存不足，已停止帧录像" }
        catch (error: Exception) { recording = false; status.text = "读取画面失败：${error.message}" }
    }

    private fun readFrame(view: TextureView): android.graphics.Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        val scale = minOf(1f, 1280f / view.width, 720f / view.height)
        return view.getBitmap(maxOf(1, (view.width * scale).toInt()), maxOf(1, (view.height * scale).toInt()))
    }

    private fun captureFrame() {
        val primary = readFrame(texture) ?: run { status.text = "还没有可读画面"; return }
        val bitmap = if (dual) {
            val secondary = try { readFrame(secondTexture) } catch (error: Throwable) { primary.recycle(); throw error }
            if (secondary == null) { primary.recycle(); status.text = "第二路没有可读画面"; return }
            try {
                android.graphics.Bitmap.createBitmap(primary.width + secondary.width, maxOf(primary.height, secondary.height), android.graphics.Bitmap.Config.ARGB_8888).also { combined ->
                    val canvas = android.graphics.Canvas(combined)
                    canvas.drawBitmap(primary, 0f, 0f, null)
                    canvas.drawBitmap(secondary, primary.width.toFloat(), 0f, null)
                }
            } finally { primary.recycle(); secondary.recycle() }
        } else primary
        saving = true
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = ByteArrayOutputStream().use { stream ->
                            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream))
                            stream.toByteArray()
                        }
                        store.saveJpeg(bytes, System.currentTimeMillis())
                    }
                }
                status.text = result.fold({ "已保存：${it.name}" }, { "保存失败：${it.message}" })
                if (result.isFailure) recording = false
            } finally { bitmap.recycle(); saving = false }
        }
    }

    private fun closePreview(reason: String) {
        generation++
        recording = false
        probe.close(reason)
        open = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = reason
    }

    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; closePreview("页面不可见，释放相机"); super.onPause() }
    override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) { surface = Surface(value); applyViewConfig() }
    override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) { applyViewConfig() }
    override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
        closePreview("Surface 销毁")
        surface?.release(); surface = null
        return true
    }
}
