package com.bydmatecn.app.local

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.bydmatecn.app.data.vehicle.HelperClient
import com.bydmatecn.app.data.vehicle.VehicleApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** User-initiated local tools only. Unsupported firmware operations remain explicit. */
@AndroidEntryPoint
class LocalToolsActivity : ComponentActivity(), LocalComposeHost {
    override val ui = LocalUiState()
    @Inject lateinit var vehicle: VehicleApi
    @Inject lateinit var helper: HelperClient
    @Inject lateinit var rules: com.bydmatecn.app.data.local.dao.RuleDao
    internal val status = LocalText("本地功能就绪") { LocalFileLog.get(this).record("tools/status", it) }
    internal lateinit var resources: LocalResourceStore
    internal lateinit var localPlayer: LocalAudioPlayer
    internal var recorder: LocalAudioRecorder? = null
    internal var speech: LocalSpeechPlayer? = null
    internal var micRelay: LocalMicRelay? = null
    internal val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status.text = if (granted) "麦克风权限已授予，请再点开始录音" else "麦克风权限被拒绝"
    }
    internal val audioLibrary by lazy { LocalAudioLibrary(this) }
    internal val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showAudioDirectoryPicker(File("/storage/emulated/0"))
        else status.text = "未授予存储读取权限，无法扫描音频目录"
    }
    internal var selectedAudio: File? = null
    internal var selectedOutput: AudioDeviceInfo? = null
    internal var resumed = false
    internal var busy = false
    internal var positionBar: LocalSlider? = null
    internal var seeking = false
    internal var firmwarePlayback = false
    internal var audioRequestGeneration = 0
    internal var talkRequestGeneration = 0
    internal val firmwareAudio by lazy { FirmwareAudioBridge(this) }
    internal val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    internal val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            val selected = selectedOutput ?: return
            if (removed.any { it.id == selected.id }) {
                stopAudio(); selectedOutput = null
                status.text = "选中的输出设备已断开，停止播放；不会自动切换扬声器"
            }
        }
    }
    internal var lyricLines: List<LocalLyrics.Line> = emptyList()
    internal var lyricLabel: LocalText? = null
    internal val importLyrics = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    while (true) { val n = input.read(buf)
                        if (n < 0) break
                        require(out.size() + n <= 256 * 1024)
                        out.write(buf, 0, n) }
                    LocalLyrics.parse(out.toString("UTF-8"))
                } ?: error("无法读取LRC")
            } }
            result.onSuccess { lyricLines=it; status.text="本地歌词：${it.size}行（仅当前选中音频）" }
                .onFailure { status.text="歌词导入失败：${it.message}" }
        }
    }
    internal var diagnosticReport = ""
    internal val exportLocalLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                contentResolver.openOutputStream(uri)?.use { LocalFileLog.get(this@LocalToolsActivity).export(it) }
                    ?: error("无法打开日志导出文件")
            } }
            status.text = result.fold({ "日志已导出，请连同复现时间和操作步骤发回" }, { "日志导出失败：${it.message}" })
        }
    }
    internal val exportDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val report = diagnosticReport
        if (uri != null && report.isNotBlank()) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray(Charsets.UTF_8)) }
                    ?: error("无法写入报告")
            } }
            status.text = result.fold({ "已导出适配报告，不含 VIN、账号或 token" }, { "报告导出失败：${it.message}" })
        }
    }
    internal val exportFiles = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
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
        LocalFileLog.get(this).record("LocalTools", "page opened")
        resources = LocalResourceStore(File(filesDir, "local-audio"))
        localPlayer = LocalAudioPlayer(this, helper) { message -> status.text = message }
        val lastAudio = getSharedPreferences("local-audio", Context.MODE_PRIVATE).getString("selected", null)
        selectedAudio = audioLibrary.resolve(lastAudio)
        val tool = intent.getStringExtra("tool") ?: "diagnostics"
        val toolInfo = localTools.firstOrNull { it.id == tool }
        ui.title = toolInfo?.title ?: "本地工具"
        ui.subtitle = toolInfo?.subtitle ?: "车机本地能力"
        ui.status = status
        ui.onBack = { finish() }

        configureTool(tool)
    }

    override fun onResume() {
        super.onResume(); resumed = true
        if (intent.getStringExtra("tool") == "audio") LocalMediaCommands.audio = { command, name ->
            val file = audioLibrary.resolve(name)
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
