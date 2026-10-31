package com.bydmatecn.app.local

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Activity-scoped controls; the activity owns state and lifecycle cleanup. */
internal fun LocalToolsActivity.configureTool(tool: String) {
        when (tool) {
            "audio" -> audioControls()
            "diagnostics" -> diagnosticControls()
            "files" -> fileControls()
            else -> { status.text = "此工具已移除"; finish(); return }

        }

        observeAudio()
    }

internal fun LocalToolsActivity.observeAudio() {
        installLocalContent(ui)
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

internal fun LocalToolsActivity.label(text: String, size: Float = 16f): LocalText = ui.label(text, size)

internal fun LocalToolsActivity.button(text: String, action: () -> Unit) {
    ui.button(text) { LocalFileLog.get(this).record("tools/action", text); action() }
}

internal fun LocalToolsActivity.openSettings(action: String) {
        runCatching { startActivity(Intent(action)) }.onFailure { status.text = "固件不提供此设置：${it.message}" }
    }

internal fun LocalToolsActivity.fileControls() {
        ui.section("音频目录")
        label("这里只管理目录并展示扫描到的媒体；播放和音效设置请在对应设置项中选择。")
        audioDirectoryControls()
        ui.section("媒体工具")
        button("创建本地媒体自动化（按钮或方控触发）") { mediaRuleControls() }
        button("本地媒体备份 / 恢复") { startActivity(Intent(this, LocalBackupActivity::class.java)) }
        button("截图与录像相册 · 回看 / 导出 / 删除") { startActivity(Intent(this, LocalMediaActivity::class.java)) }
        label("设置弹窗仅列出所选目录中的音频；此处 ZIP 仅导出旧导入副本、录音、合成语音及截图，不包含外部音频目录。")
        button("查看应用内生成 / 保留资源") {
            val audio = resources.files()
            val captures = LocalCaptureStore(File(filesDir, "local-captures")).captures()
            status.text = "音频：${audio.size} 个；截图/帧：${captures.size} 个\n" + audio.joinToString("\n") { it.name }
        }
        button("导出音频和截图 ZIP") { if (!busy) exportFiles.launch("BYDMateCN-local-resources.zip") }

    }

internal fun LocalToolsActivity.audioControls() {
        ui.section("播放控制")
        label("音频目录在“本地媒体”中管理；每次播放都在当前设置项中选择文件。")
        button("选择系统输出设备") {
            val devices = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { ordinaryAudioOutput(it.type) }
            val names = arrayOf("系统默认") + devices.map { "${it.productName} (type=${it.type}, id=${it.id})" }.toTypedArray()
            LocalDialogBuilder(this).setTitle("输出设备（不等于外扬声器路由）").setItems(names) { _, which ->
                selectedOutput = if (which == 0) null else devices[which - 1]
                status.text = names[which]
            }.show()
        }
        button("选择音频并播放") {
            showAudioPicker("选择要播放的音频") { file -> playLocalAudio(file) }
        }
        button("暂停 / 继续") { localPlayer.togglePause() }
        button("停止") { stopAudio(); status.text = "已停止" }
        val audioPrefs = audioPreferences()
        playbackPreferences(audioPrefs)
    }

private fun LocalToolsActivity.audioPreferences() = getSharedPreferences("local-audio", Context.MODE_PRIVATE)

private fun LocalToolsActivity.playbackPreferences(audioPrefs: android.content.SharedPreferences) {
        val volume = audioPrefs.getInt("volume", 50).coerceIn(0, 100)
        localPlayer.setVolume(volume)
        label("应用播放音量（不修改原车系统音量）")
        ui.slider(LocalSlider(volume), changed = { value ->
            localPlayer.setVolume(value); audioPrefs.edit().putInt("volume", value).apply()
        })
        val repeat = audioPrefs.getBoolean("repeat", false)
        localPlayer.setLooping(repeat)
        ui.toggle("循环播放", repeat) { checked -> localPlayer.setLooping(checked); audioPrefs.edit().putBoolean("repeat", checked).apply() }
        label("播放位置（松手跳转）")
        val position = LocalSlider(0)
        positionBar = position
        ui.slider(position, { seeking = true }, { localPlayer.seekPercent(position.progress); seeking = false })
        button("导入本地LRC歌词（UTF-8，不联网）") { importLyrics.launch(arrayOf("text/*", "application/octet-stream")) }
        lyricLabel = label("", 22f)
        button("显示播放进度") { status.text = localPlayer.progress().ifEmpty { "未在播放" } }
        microphoneControls(audioPrefs)

}
