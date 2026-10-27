package com.bydmate.app.local

import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.widget.EditText
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.os.Handler
import android.os.Looper
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.widget.SeekBar
import android.widget.Switch
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** User-initiated local tools only. Unsupported firmware operations remain explicit. */
@AndroidEntryPoint
class LocalToolsActivity : ComponentActivity() {
    @Inject lateinit var vehicle: VehicleApi
    @Inject lateinit var helper: HelperClient
    private lateinit var layout: LinearLayout
    private lateinit var status: TextView
    private lateinit var resources: LocalResourceStore
    private lateinit var localPlayer: LocalAudioPlayer
    private var recorder: LocalAudioRecorder? = null
    private var speech: LocalSpeechPlayer? = null
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status.text = if (granted) "麦克风权限已授予，请再点开始录音" else "麦克风权限被拒绝"
    }
    private var selectedAudio: File? = null
    private var selectedOutput: AudioDeviceInfo? = null
    private var resumed = false
    private var busy = false
    private var positionBar: SeekBar? = null
    private var seeking = false
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            val selected = selectedOutput ?: return
            if (removed.any { it.id == selected.id }) {
                stopAudio(); selectedOutput = null
                status.text = "选中的输出设备已断开，停止播放；不会自动切换扬声器"
            }
        }
    }
    private val importAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: error("无法读取文件名")
                    contentResolver.openInputStream(uri)?.use { resources.importAudio(it, name.substringAfterLast('.', "")) }
                        ?: error("无法读取文件")
                }
            }
            result.onSuccess { selectAudio(it) }
            status.text = result.fold({ "已导入：${it.name}" }, { "导入失败：${it.message}" })
        }
    }
    private val exportFiles = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) lifecycleScope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        synchronized(resources) {
                            val captures = LocalCaptureStore(File(filesDir, "local-captures"))
                            captures.withCaptures { frames ->
                                contentResolver.openOutputStream(uri)?.use { output ->
                                    ZipOutputStream(output).use { zip ->
                                        val entries = resources.files().map { "audio/${it.name}" to it } +
                                            frames.map { "captures/${it.name}" to it }
                                        entries.forEach { (name, file) ->
                                            zip.putNextEntry(ZipEntry(name))
                                            file.inputStream().use { it.copyTo(zip) }
                                            zip.closeEntry()
                                        }
                                    }
                                } ?: error("无法打开输出文件")
                            }
                        }
                    }
                }
                status.text = result.fold({ "本地资源已导出（不含账户和数据库）" }, { "导出失败：${it.message}" })
            } finally { busy = false }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resources = LocalResourceStore(File(filesDir, "local-audio"))
        localPlayer = LocalAudioPlayer(this) { message -> status.text = message }
        val lastAudio = getSharedPreferences("local-audio", Context.MODE_PRIVATE).getString("selected", null)
        selectedAudio = resources.files().firstOrNull { it.name == lastAudio }
        val tool = intent.getStringExtra("tool") ?: "status"
        layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF101A2D.toInt())
        }
        label(localTools.firstOrNull { it.id == tool }?.title ?: "本地工具", 26f)
        status = label("本地功能就绪", 17f)
        button("返回") { finish() }
        when (tool) {
            "audio" -> audioControls()
            "files" -> fileControls()
            "sentry" -> sentryControls()
            "status", "guard" -> vehicleControls(tool == "guard")
            "bluetooth" -> {
                label("可进入系统蓝牙设置配对。米篓宝的专用数据协议尚未恢复，配对成功不等于插件可用。")
                button("系统蓝牙设置") { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
            }
            "front" -> label("AutoTark 此能力有 FrontCarReminderPluginState；目前未取得插件的完整输入和算法。不能用自车速度变化冒充前车起步判断。")
            "traffic" -> label("实时红绿灯需要外部信号源；此前约定的本地版不移植此项，不显示虚假倒计时。")
            "touch" -> {
                label("已有投屏并不自动提供触摸注入权限；当前 helper 尚未有经确认的仪表输入协议，不执行猜测的 input 命令。")
                button("设备显示诊断") { lifecycleScope.launch { status.text = helper.listDisplays()?.joinToString("\n") ?: "helper 不可用" } }
            }
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        lifecycleScope.launch {
            while (true) {
                delay(500)
                if (resumed && !seeking) positionBar?.progress = localPlayer.progressPercent()
            }
        }
    }

    private fun label(text: String, size: Float = 16f): TextView = TextView(this).also {
        it.text = text; it.textSize = size; it.setTextColor(0xFFE3EDFF.toInt()); it.setPadding(0, 10, 0, 10); layout.addView(it)
    }
    private fun button(text: String, action: () -> Unit) {
        layout.addView(Button(this).apply { this.text = text; setOnClickListener { action() } })
    }
    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action)) }.onFailure { status.text = "固件不提供此设置：${it.message}" }
    }
    private fun fileControls() {
        label("支持本地音频导入与 ZIP 导出。文件名生成于私有目录；单文件 32 MiB，总音频 128 MiB。不是局域网接收服务器。")
        button("导入本地音频") { importAudio.launch(arrayOf("audio/*")) }
        button("查看本地资源") {
            val audio = resources.files()
            val captures = LocalCaptureStore(File(filesDir, "local-captures")).captures()
            status.text = "音频：${audio.size} 个；截图/帧：${captures.size} 个\n" + audio.joinToString("\n") { it.name }
        }
        button("导出音频和截图 ZIP") { if (!busy) exportFiles.launch("BYDMate-local-resources.zip") }
        button("系统分享设置") { openSettings(Settings.ACTION_WIRELESS_SETTINGS) }
    }
    private fun audioControls() {
        label("播放用户导入的本地音频；使用 Android 输出设备选择。外扬声器 AVAS/锁车路由仍需完整的厂商 slot 配置，不把普通媒体声道当作车外播放。")
        button("导入音频") { importAudio.launch(arrayOf("audio/*")) }
        button("选择已导入音频") {
            val files = resources.files()
            if (files.isEmpty()) status.text = "请先导入音频"
            else AlertDialog.Builder(this).setTitle("本地音频").setItems(files.map { it.name }.toTypedArray()) { _, which ->
                selectAudio(files[which]); status.text = "选择：${files[which].name}"
            }.show()
        }
        button("选择系统输出设备") {
            val devices = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val names = arrayOf("系统默认") + devices.map { "${it.productName} (type=${it.type}, id=${it.id})" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("输出设备（不等于外扬声器路由）").setItems(names) { _, which ->
                selectedOutput = if (which == 0) null else devices[which - 1]
                status.text = names[which]
            }.show()
        }
        button("播放") { playSelected() }
        button("暂停 / 继续") { localPlayer.togglePause() }
        button("停止") { stopAudio(); status.text = "已停止" }
        val audioPrefs = getSharedPreferences("local-audio", Context.MODE_PRIVATE)
        val volume = audioPrefs.getInt("volume", 50).coerceIn(0, 100)
        localPlayer.setVolume(volume)
        label("应用播放音量（不修改原车系统音量）")
        layout.addView(SeekBar(this).apply {
            max = 100; progress = volume
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) { localPlayer.setVolume(value); audioPrefs.edit().putInt("volume", value).apply() }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
        val repeat = audioPrefs.getBoolean("repeat", false)
        localPlayer.setLooping(repeat)
        layout.addView(Switch(this).apply {
            text = "循环播放"; setTextColor(0xFFE3EDFF.toInt()); isChecked = repeat
            setOnCheckedChangeListener { _, checked -> localPlayer.setLooping(checked); audioPrefs.edit().putBoolean("repeat", checked).apply() }
        })
        label("播放位置（松手跳转）")
        positionBar = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(bar: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar?) { localPlayer.seekPercent(bar?.progress ?: 0); seeking = false }
            })
        }
        layout.addView(positionBar)
        button("显示播放进度") { status.text = localPlayer.progress().ifEmpty { "未在播放" } }
        button("删除选中音频") {
            val file = selectedAudio ?: return@button
            AlertDialog.Builder(this).setMessage("删除 ${file.name}？仅删除本应用导入副本。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                    stopAudio()
                    if (resources.delete(file)) {
                        selectedAudio = null
                        audioPrefs.edit().remove("selected").apply()
                        status.text = "已删除导入副本"
                    } else status.text = "删除失败"
                }.show()
        }
        label("录制语音（仅本地保存，不是实时外放）")
        recorder = LocalAudioRecorder(File(cacheDir, "local-voice"), resources,
            { status.text = it }, { selectAudio(it) })
        button("开始录音 · 最长 60 秒") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                stopAudio(); speech?.stop()
                runCatching { recorder?.start() }.onFailure { status.text = "录音启动失败：${it.message}" }
            }
        }
        button("停止录音并保存") { recorder?.finish(true) }
        button("放弃录音") { recorder?.finish(false); status.text = "录音已取消" }
        label("离线中文语音（使用已安装离线音色）")
        speech = LocalSpeechPlayer(this) { status.text = it }
        val text = EditText(this).apply {
            hint = "输入语音文本，最多 500 字"
            filters = arrayOf(android.text.InputFilter.LengthFilter(500))
            setTextColor(0xFFE3EDFF.toInt()); setHintTextColor(0xFF8090AA.toInt())
            setText(audioPrefs.getString("speech_text", ""))
        }
        layout.addView(text)
        label("语速 0.5～2.0")
        val rate = SeekBar(this).apply { max = 150; progress = audioPrefs.getInt("speech_rate", 50).coerceIn(0, 150) }
        layout.addView(rate)
        label("音调 0.5～2.0")
        val pitch = SeekBar(this).apply { max = 150; progress = audioPrefs.getInt("speech_pitch", 50).coerceIn(0, 150) }
        layout.addView(pitch)
        button("朗读（系统默认音频输出）") {
            recorder?.finish(false); stopAudio()
            audioPrefs.edit().putString("speech_text", text.text.toString())
                .putInt("speech_rate", rate.progress).putInt("speech_pitch", pitch.progress).apply()
            speech?.speak(text.text.toString(), .5f + rate.progress / 100f, .5f + pitch.progress / 100f)
        }
        button("停止朗读") { speech?.stop() }
        button("外放 / 锁车音效接口说明") {
            status.text = "APK 外放的类名、方法名、stream、flags 和 FID 使用运行时 profile。已恢复调用逻辑，但尚缺目标固件配置；不会用 FID=0 或普通媒体路由冒充外放。"
        }
    }
    private fun selectAudio(file: File) {
        selectedAudio = file
        getSharedPreferences("local-audio", Context.MODE_PRIVATE).edit().putString("selected", file.name).apply()
    }
    private fun playSelected() {
        val file = selectedAudio ?: run { status.text = "请先选择音频"; return }
        recorder?.finish(false); speech?.stop()
        localPlayer.play(file, selectedOutput)
    }
    private fun stopAudio() { localPlayer.stop() }
    private fun sentryControls() {
        label("此开关复用 BYDMate 已有 helper 白名单：sentrymode_enabled_switch。它控制原厂模式，不是已经实现的 BYDMate 自定义事件录像。")
        button("读取原厂哨兵状态") { lifecycleScope.launch { status.text = "原始值：${helper.getGlobalSetting("sentrymode_enabled_switch") ?: "不可读"}" } }
        fun confirm(value: Int) {
            if (busy) return
            AlertDialog.Builder(this).setMessage(if (value == 1) "启用原厂哨兵可能增加驻车耗电，继续？" else "关闭原厂哨兵？")
                .setNegativeButton("取消", null).setPositiveButton("确认") { _, _ ->
                    lifecycleScope.launch {
                        busy = true
                        try {
                            val snapshot = vehicle.readSnapshot()
                            if (snapshot?.gear != 1 || snapshot.speed != 0) { status.text = "车辆状态未知或非驻车，拒绝修改"; return@launch }
                            val ok = helper.putGlobalSetting("sentrymode_enabled_switch", value)
                            val actual = helper.getGlobalSetting("sentrymode_enabled_switch")
                            status.text = if (ok && actual == value) "开关写入并读回：$value；是否实际录像需原厂验证" else "设置未确认，读回：$actual"
                        } finally { busy = false }
                    }
                }.show()
        }
        button("启用原厂哨兵") { confirm(1) }
        button("关闭原厂哨兵") { confirm(0) }
        button("驻车预览和手动帧录像") { startActivity(Intent(this, LocalCameraActivity::class.java)) }
    }
    private fun vehicleControls(guard: Boolean) {
        if (guard) {
            label("显示车辆及 12V 状态；不会写未知的熄火供电/休眠接口，不承诺锁车后持续运行。")
            button("原厂哨兵管理") { startActivity(Intent(this, LocalToolsActivity::class.java).putExtra("tool", "sentry")) }
        }
        lifecycleScope.launch {
            while (true) {
                if (resumed) {
                    val data = vehicle.readSnapshot()
                    status.text = data?.let { "SOC：${it.soc ?: "未知"}%\n车速：${it.speed ?: "未知"}\n挡位原始码：${it.gear ?: "未知"}\n里程：${it.mileage ?: "未知"}\n12V：${it.voltage12v ?: "未知"} V\n功率：${it.power ?: "未知"}\n充电：${it.chargingStatus ?: "未知"}" } ?: "车辆服务不可用，未显示伪数据"
                }
                delay(1500)
            }
        }
    }
    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; stopAudio(); recorder?.finish(false); speech?.stop(); super.onPause() }
    override fun onDestroy() {
        recorder?.finish(false); speech?.close()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        stopAudio()
        super.onDestroy()
    }
}
