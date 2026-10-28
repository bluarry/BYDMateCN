package com.bydmatecn.app.local

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
import com.bydmatecn.app.data.vehicle.HelperClient
import com.bydmatecn.app.data.vehicle.VehicleApi
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
    @Inject lateinit var rules: com.bydmatecn.app.data.local.dao.RuleDao
    private lateinit var layout: LinearLayout
    private lateinit var status: TextView
    private lateinit var resources: LocalResourceStore
    private lateinit var localPlayer: LocalAudioPlayer
    private var recorder: LocalAudioRecorder? = null
    private var speech: LocalSpeechPlayer? = null
    private var micRelay: LocalMicRelay? = null
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status.text = if (granted) "麦克风权限已授予，请再点开始录音" else "麦克风权限被拒绝"
    }
    private var selectedAudio: File? = null
    private var selectedOutput: AudioDeviceInfo? = null
    private var resumed = false
    private var busy = false
    private var positionBar: SeekBar? = null
    private var seeking = false
    private var firmwarePlayback = false
    private var audioRequestGeneration = 0
    private var talkRequestGeneration = 0
    private val firmwareAudio by lazy { FirmwareAudioBridge(this) }
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
    private var lyricLines: List<LocalLyrics.Line> = emptyList()
    private var lyricLabel: TextView? = null
    private val importLyrics = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    while (true) { val n=input.read(buf); if (n<0) break; require(out.size()+n <= 256*1024); out.write(buf,0,n) }
                    LocalLyrics.parse(out.toString("UTF-8"))
                } ?: error("无法读取LRC")
            } }
            result.onSuccess { lyricLines=it; status.text="本地歌词：${it.size}行（仅当前选中音频）" }
                .onFailure { status.text="歌词导入失败：${it.message}" }
        }
    }
    private var diagnosticReport = ""
    private val exportLocalLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                contentResolver.openOutputStream(uri)?.use { LocalFileLog.get(this@LocalToolsActivity).export(it) }
                    ?: error("无法打开日志导出文件")
            } }
            status.text = result.fold({ "日志已导出，请连同复现时间和操作步骤发回" }, { "日志导出失败：${it.message}" })
        }
    }
    private val exportDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val report = diagnosticReport
        if (uri != null && report.isNotBlank()) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray(Charsets.UTF_8)) }
                    ?: error("无法写入报告")
            } }
            status.text = result.fold({ "已导出适配报告，不含 VIN、账号或 token" }, { "报告导出失败：${it.message}" })
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
        LocalFileLog.get(this).watch(status, "tools/${intent.getStringExtra("tool")}")
        button("返回") { finish() }
        when (tool) {
            "audio" -> audioControls()
            "diagnostics" -> diagnosticControls()
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
                if (resumed && !seeking) {
                    positionBar?.progress = localPlayer.progressPercent()
                    lyricLabel?.text = LocalLyrics.at(lyricLines, localPlayer.positionMs())
                }
                if (firmwarePlayback || micRelay?.active == true) {
                    val snapshot = kotlinx.coroutines.withTimeoutOrNull(2500) { runCatching { vehicle.readSnapshot() }.getOrNull() }
                    if (!firmwareAudioAllowed(snapshot?.gear, snapshot?.speed, resumed)) {
                        stopAudio(); micRelay?.stop(); status.text = "驻车条件丢失或状态不可读，专用音频已停止"
                    }
                }
            }
        }
    }

    private fun label(text: String, size: Float = 16f): TextView = TextView(this).also {
        it.text = text; it.textSize = size; it.setTextColor(0xFFE3EDFF.toInt()); it.setPadding(0, 10, 0, 10); layout.addView(it)
    }
    private fun button(text: String, action: () -> Unit) {
        layout.addView(Button(this).apply { this.text = text; setOnClickListener { LocalFileLog.get(this@LocalToolsActivity).record("tools/action", text); action() } })
    }
    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action)) }.onFailure { status.text = "固件不提供此设置：${it.message}" }
    }
    private fun fileControls() {
        button("创建本地媒体自动化（按钮或方控触发）") { mediaRuleControls() }
        button("本地媒体备份 / 恢复") { startActivity(Intent(this, LocalBackupActivity::class.java)) }
        button("截图与录像相册 · 回看 / 导出 / 删除") { startActivity(Intent(this, LocalMediaActivity::class.java)) }
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
    private fun mediaRuleControls() {
        val actions = arrayOf("截图", "开始MP4", "停止MP4", "选中音频播放", "停止音频", "车外试听", "锁车试听")
        AlertDialog.Builder(this).setTitle("本地媒体动作（对应页面必须可见）").setItems(actions) { _, selected ->
            val command = listOf("snapshot", "record_start", "record_stop", "play", "stop", "exterior", "lock_preview")[selected]
            val name = resources.files().firstOrNull { it == selectedAudio }?.name
            if (selected in setOf(3, 5, 6) && name == null) { status.text = "先在音频页选中本地文件"; return@setItems }
            val key = EditText(this).apply { hint = "方控Android keyCode（留空=按钮触发）"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            AlertDialog.Builder(this).setTitle(actions[selected]).setView(key).setNegativeButton("取消", null)
                .setPositiveButton("创建停用规则") { _, _ ->
                    lifecycleScope.launch {
                        val code = key.text.toString().toIntOrNull()
                        if (key.text.isNotBlank() && (code == null || code !in 1..1000)) { status.text = "keyCode无效"; return@launch }
                        val trigger = com.bydmatecn.app.data.local.entity.TriggerDef(
                            if (code == null) "button_press" else "steering_key", "本地触发", "==", code?.toString() ?: "",
                            "${actions[selected]}快捷操作", if (code == null) "button_press" else "steering_key")
                        val action = com.bydmatecn.app.data.local.entity.ActionDef("", actions[selected],
                            if (selected < 3) "local_camera" else "local_audio",
                            if (selected < 3) command else org.json.JSONObject().put("command", command).put("file", name).toString())
                        val id = rules.insert(com.bydmatecn.app.data.local.entity.RuleEntity(name="本地：${actions[selected]}", enabled=false,
                            triggers=com.bydmatecn.app.data.local.entity.TriggerDef.listToJson(listOf(trigger)),
                            actions=com.bydmatecn.app.data.local.entity.ActionDef.listToJson(listOf(action)), requirePark=true, cooldownSeconds=5))
                        status.text = "规则${id}已创建且停用，请在自动化页检查并启用；后台不能偷偷打开相机/音频页面。"
                    }
                }.show()
        }.show()
    }

    private fun audioControls() {
        label("普通播放使用 Android 输出设备；下方新增固件专用车外/锁车试听入口。专用标签已恢复，外功放授权和实际出声仍需真车验证。")
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
        button("导入本地LRC歌词（UTF-8，不联网）") { importLyrics.launch(arrayOf("text/*", "application/octet-stream")) }
        lyricLabel = label("", 22f)
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
        micRelay = LocalMicRelay(this) { status.text = it }
        label("实时车外喊话 · 按住说话，松手停止 · 最长15秒")
        val pushToTalk = Button(this).apply {
            this.text = "按住喊话（需要先确认车外路由，音量≤50%）"
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        LocalFileLog.get(this@LocalToolsActivity).record("mic", "touch down")
                        view.isPressed = true
                        val talkRequest = ++talkRequestGeneration
                        if (!audioPrefs.getBoolean("exterior_verified", false)) {
                            status.text = "请先完成车外试听并勾选实际出声确认"
                        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else lifecycleScope.launch {
                            val snapshot = kotlinx.coroutines.withTimeoutOrNull(2500) { vehicle.readSnapshot() }
                            if (talkRequest != talkRequestGeneration || !view.isPressed || !firmwareAudioAllowed(snapshot?.gear, snapshot?.speed, resumed)) return@launch
                            runCatching {
                                recorder?.finish(false); speech?.stop(); stopAudio()
                                micRelay?.start(firmwareAudio.attributes(FirmwareAudioRoute.EXTERIOR_VOICE), audioPrefs.getInt("volume", 25))
                            }.onFailure { micRelay?.stop(); status.text = "喊话启动失败：${it.message}" }
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        LocalFileLog.get(this@LocalToolsActivity).record("mic", "touch released/cancelled")
                        talkRequestGeneration++; view.isPressed = false; micRelay?.stop(); view.performClick()
                    }
                }
                true
            }
        }
        layout.addView(Switch(this).apply {
            text = "已实际确认11002从车外扬声器出声，理解啸叫风险"
            setTextColor(0xFFE3EDFF.toInt()); isChecked = audioPrefs.getBoolean("exterior_verified", false)
            setOnCheckedChangeListener { _, checked ->
                audioPrefs.edit().putBoolean("exterior_verified", checked).apply()
                if (!checked) micRelay?.stop()
            }
        })
        layout.addView(pushToTalk)
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
        button("生成本地语音文件（WAV，不播放）") {
            recorder?.finish(false); stopAudio()
            speech?.synthesize(text.text.toString(), .5f + rate.progress / 100f, .5f + pitch.progress / 100f, resources) { selectAudio(it) }
        }
        button("停止朗读 / 合成") { speech?.stop() }
        label("固件专用路由：仅驻车手动试听，最多 15 秒；不代表已实现自动锁车播放。")
        button("检测车外 / 锁车试听接口（不播放）") { status.text = firmwareAudio.probe() }
        FirmwareAudioRoute.entries.filter { it != FirmwareAudioRoute.LOCK_EVENT }.forEach { route ->
            button("${route.title}：试听选中音频") {
                AlertDialog.Builder(this).setTitle(route.title)
                    .setMessage("可能在车外发声，请确认环境安全。最多播放 15 秒，离开页面即停止。不覆盖原厂音效。")
                    .setNegativeButton("取消", null).setPositiveButton("驻车试听") { _, _ -> playFirmwareAudio(route) }.show()
            }
        }

        label("自动锁 / 解锁音效 · 手动布防 · 不修改原厂资源")
        val lockPrefs = getSharedPreferences("local-lock-sound", MODE_PRIVATE)
        layout.addView(Switch(this).apply {
            this.text = "已实车确认11001事件路由与LockFL门锁原始码"
            setTextColor(0xFFE3EDFF.toInt()); isChecked = lockPrefs.getBoolean("verified", false)
            setOnCheckedChangeListener { _, checked ->
                lockPrefs.edit().putBoolean("verified", checked).apply()
                if (!checked) stopService(Intent(this@LocalToolsActivity, LocalLockSoundService::class.java))
            }
        })
        button("选中音频设为锁车音效") { selectedAudio?.let { lockPrefs.edit().putString("lock_file", it.name).apply(); status.text = "锁车音效：${it.name}" } }
        button("选中音频设为解锁音效") { selectedAudio?.let { lockPrefs.edit().putString("unlock_file", it.name).apply(); status.text = "解锁音效：${it.name}" } }
        button("手动布防锁车音效（最多2小时）") {
            if (!lockPrefs.getBoolean("verified", false)) status.text = "需要先完成实车路由与门锁验证"
            else {
                lockPrefs.edit().putInt("volume", audioPrefs.getInt("volume", 25).coerceIn(0, 50)).apply()
                runCatching { androidx.core.content.ContextCompat.startForegroundService(this,
                    Intent(this, LocalLockSoundService::class.java).setAction(LocalLockSoundService.ARM)) }
                    .onFailure { status.text = "布防启动失败：${it.message}" }
            }
        }
        button("停止锁车布防") { stopService(Intent(this, LocalLockSoundService::class.java)); status.text = "已请求停止布防" }
        button("读取布防状态") { status.text = lockPrefs.getString("last_status", "未布防") }

    }
    private fun selectAudio(file: File) {
        if (selectedAudio != file) { lyricLines = emptyList(); lyricLabel?.text = "" }
        selectedAudio = file
        getSharedPreferences("local-audio", Context.MODE_PRIVATE).edit().putString("selected", file.name).apply()
    }
    private fun playSelected() {
        val file = selectedAudio ?: run { status.text = "请先选择音频"; return }
        recorder?.finish(false); speech?.stop()
        stopAudio()
        localPlayer.play(file, selectedOutput)
    }
    private fun playFirmwareAudio(route: FirmwareAudioRoute) {
        if (busy) return
        val file = selectedAudio ?: run { status.text = "请先选择本地音频"; return }
        lifecycleScope.launch {
            busy = true
            try {
                stopAudio(); recorder?.finish(false); speech?.stop()
                val request = audioRequestGeneration
                val snapshot = kotlinx.coroutines.withTimeoutOrNull(2500) { vehicle.readSnapshot() }
                check(request == audioRequestGeneration && firmwareAudioAllowed(snapshot?.gear, snapshot?.speed, resumed)) { "非驻车、车辆状态未知或页面不可见" }
                val attrs = firmwareAudio.attributes(route)
                firmwarePlayback = true
                localPlayer.play(file, null, attrs, route.title, 15_000L)
            } catch (error: Exception) {
                stopAudio(); status.text = "专用路由未启动：${error.message}；没有降级为普通媒体"
            } finally { busy = false }
        }
    }
    private fun stopAudio() { audioRequestGeneration++; talkRequestGeneration++; micRelay?.stop(); firmwarePlayback = false; localPlayer.stop() }
    private fun diagnosticControls() {
        label("本地日志自动保存，最多两份各1MiB；不含音频内容。卸载/清除数据会删除日志。")
        button("导出本地问题日志 TXT") { exportLocalLog.launch("BYDMateCN-local-log-${System.currentTimeMillis()}.txt") }
        label("固件包用于确认接口；实车还必须提供运行时类、Binder 服务、HAL 路由和权限。此检测只读，不自动授权，不改系统设置。")
        button("相机权限") { permissionForCamera() }
        button("使用情况访问权限") { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        button("悬浮窗权限") {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
                .onFailure { status.text = "固件没有悬浮窗设置页：${it.message}" }
        }
        button("应用详情 / 权限管理") {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
                .onFailure { status.text = "无法打开应用详情：${it.message}" }
        }
        button("刷新只读适配报告") {
            if (busy) return@button
            lifecycleScope.launch {
                busy = true
                try {
                    val report = org.json.JSONObject()
                        .put("schema", "bydmate.local-capabilities.v1")
                        .put("android_sdk", android.os.Build.VERSION.SDK_INT)
                        .put("build_display", android.os.Build.DISPLAY)
                        .put("model", android.os.Build.MODEL)
                        .put("camera_permission", checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                        .put("microphone_permission", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        .put("overlay_permission", Settings.canDrawOverlays(this@LocalToolsActivity))
                    val ops = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                    report.put("usage_access", ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED)
                    val types = org.json.JSONObject()
                    listOf("android.media.BydAudioManager", "android.hardware.BmmCameraInfo", "android.hardware.AVMCamera").forEach { name ->
                        types.put(name, runCatching { Class.forName(name); "present" }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" })
                    }
                    report.put("runtime_classes", types)
                    report.put("audio_attribute_probe", firmwareAudio.probe())
                    report.put("output_devices", org.json.JSONArray(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map {
                        org.json.JSONObject().put("id", it.id).put("type", it.type).put("name", it.productName.toString())
                    }))
                    val snapshot = kotlinx.coroutines.withTimeoutOrNull(2500) { runCatching { vehicle.readSnapshot() }.getOrNull() }
                    report.put("vehicle_readable", snapshot != null)
                    report.put("parked_now", snapshot?.gear == 1 && snapshot.speed == 0)
                    report.put("displays", runCatching { helper.listDisplays()?.joinToString("\n") }.getOrNull() ?: "helper unavailable")
                    report.put("limitations", "Class presence and AudioAttributes do not verify HAL output or Binder authorization. No playback/camera open performed.")
                    diagnosticReport = report.toString(2)
                    status.text = diagnosticReport
                } catch (error: Exception) { status.text = "检测失败：${error.message}" }
                finally { busy = false }
            }
        }
        button("导出 JSON 适配报告") {
            if (diagnosticReport.isBlank()) status.text = "请先刷新报告" else exportDiagnostics.launch("BYDMate-capabilities.json")
        }
    }
    private fun permissionForCamera() {
        // Permission grant remains controlled by the system/user, not by firmware extraction.
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
            .onFailure { status.text = "请在系统设置中授予相机权限" }
    }

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
                            val snapshot = kotlinx.coroutines.withTimeoutOrNull(2500) { vehicle.readSnapshot() }
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
        button("驻车预览、MP4 分段录像与截图") { startActivity(Intent(this, LocalCameraActivity::class.java)) }
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
    override fun onResume() {
        super.onResume(); resumed = true
        if (intent.getStringExtra("tool") == "audio") LocalMediaCommands.audio = { command, name ->
            val file = resources.files().firstOrNull { it.name == name }
            when {
                command == "stop" -> { stopAudio(); true }
                file == null -> false
                command == "play" -> { selectAudio(file); playSelected(); true }
                command in setOf("exterior", "lock_preview") &&
                    getSharedPreferences("local-audio", MODE_PRIVATE).getBoolean("exterior_verified", false) -> {
                    selectAudio(file)
                    playFirmwareAudio(if (command == "exterior") FirmwareAudioRoute.EXTERIOR_VOICE else FirmwareAudioRoute.LOCK_PREVIEW)
                    true
                }
                else -> false
            }
        }
    }
    override fun onPause() { LocalFileLog.get(this).record("tools", "page paused; releasing audio/microphone"); LocalMediaCommands.audio = null; resumed = false; stopAudio(); recorder?.finish(false); speech?.stop(); super.onPause() }
    override fun onDestroy() {
        recorder?.finish(false); speech?.close(); micRelay?.close()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        stopAudio()
        super.onDestroy()
    }
}
