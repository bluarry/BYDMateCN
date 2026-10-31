package com.bydmatecn.app.local

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

/** Activity-scoped controls; the activity owns state and lifecycle cleanup. */
internal fun LocalToolsActivity.microphoneControls(audioPrefs: android.content.SharedPreferences) {
        ui.section("车外喊话")
        micRelay = LocalMicRelay(this, helper) { status.text = it }
        label("实时车外喊话 · 按住说话，松手停止 · 最长15秒")
        exteriorControllerControls(audioPrefs)
        ui.toggle("已实际确认所选控制器配置可从车外扬声器出声，理解啸叫风险", audioPrefs.getBoolean("exterior_verified", false)) { checked ->
            audioPrefs.edit().putBoolean("exterior_verified", checked).apply()
            if (!checked) { talkRequestGeneration++; micRelay?.stop() }
        }
        var held = false
        ui.holdButton("按住喊话（需要先确认车外路由，音量≤50%）", pressed = {
            held = true
            val talkRequest = ++talkRequestGeneration
            LocalFileLog.get(this).record("mic", "touch down")
            if (!audioPrefs.getBoolean("exterior_verified", false)) {
                status.text = "请先选择控制器配置，完成车外试听并勾选实际出声确认"
            } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            } else lifecycleScope.launch {
                val snapshot = kotlinx.coroutines.withTimeoutOrNull(2500) { vehicle.readSnapshot() }
                if (talkRequest != talkRequestGeneration || !held || !firmwareAudioAllowed(snapshot?.gear, snapshot?.speed, resumed)) return@launch
                runCatching {
                    recorder?.finish(false); speech?.stop(); stopAudio()
                    micRelay?.start(firmwareAudio.focusAttributes(FirmwareAudioRoute.EXTERIOR_VOICE), audioPrefs.getInt("volume", 25))
                }.onFailure { micRelay?.stop(); status.text = "喊话启动失败：${it.message}" }
            }
        }, released = {
            held = false; talkRequestGeneration++; micRelay?.stop()
            LocalFileLog.get(this).record("mic", "touch released/cancelled")
        })

        speechControls(audioPrefs)
    }


private fun LocalToolsActivity.exteriorControllerControls(audioPrefs: android.content.SharedPreferences) {
    val selected = ExteriorControllerProfile.selected(this)
    val profileLabel = label("当前控制器：${selected.title}")
    button("选择车外音频控制器") {
        val profiles = ExteriorControllerProfile.entries.toTypedArray()
        LocalDialogBuilder(this).setTitle("选择控制器")
            .setMessage("宋 L DM-i 先测试23控制器；若车外无声，再切换非23控制器。切换后请重新完成试听确认。")
            .setItems(profiles.map { it.title }.toTypedArray()) { _, index ->
                val profile = profiles[index]
                stopAudio(); micRelay?.stop()
                ExteriorControllerProfile.select(this, profile)
                audioPrefs.edit().putBoolean("exterior_verified", false).apply()
                getSharedPreferences("local-lock-sound", Context.MODE_PRIVATE).edit().putBoolean("verified", false).apply()
                profileLabel.text = "当前控制器：${profile.title}"
                status.text = "已切换为${profile.title}，正在刷新页面，请重新进行驻车试听"
                recreate()
            }.show()
    }
}
internal fun LocalToolsActivity.speechControls(audioPrefs: android.content.SharedPreferences) {
        ui.section("录音")
        label("录制语音（仅本地保存，不是实时外放）")
        recorder = LocalAudioRecorder(File(cacheDir, "local-voice"), resources,
            { status.text = it }, { status.text = "录音已保存：${it.name}；如需播放，请将文件放入“本地媒体”选择的目录" })
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
        ui.section("语音合成")
        label("离线中文语音（使用已安装离线音色）")
        speech = LocalSpeechPlayer(this) { status.text = it }
        val text = LocalInput("输入语音文本，最多 500 字", audioPrefs.getString("speech_text", "") ?: "", 500)
        ui.input(text)
        label("语速 0.5～2.0")
        val rate = LocalSlider(audioPrefs.getInt("speech_rate", 50).coerceIn(0, 150), 150)
        ui.slider(rate)
        label("音调 0.5～2.0")
        val pitch = LocalSlider(audioPrefs.getInt("speech_pitch", 50).coerceIn(0, 150), 150)
        ui.slider(pitch)
        button("朗读（系统默认音频输出）") {
            recorder?.finish(false); stopAudio()
            audioPrefs.edit().putString("speech_text", text.text.toString())
                .putInt("speech_rate", rate.progress).putInt("speech_pitch", pitch.progress).apply()
            speech?.speak(text.text.toString(), .5f + rate.progress / 100f, .5f + pitch.progress / 100f)
        }
        button("生成本地语音文件（WAV，不播放）") {
            recorder?.finish(false); stopAudio()
            speech?.synthesize(text.text.toString(), .5f + rate.progress / 100f, .5f + pitch.progress / 100f, resources) { status.text = "已生成：${it.name}；在文件工具中导出ZIP并解压到已选择的音频目录后扫描" }
        }
        button("停止朗读 / 合成") { speech?.stop() }

        firmwareControls(audioPrefs)
    }

internal fun LocalToolsActivity.firmwareControls(audioPrefs: android.content.SharedPreferences) {
        ui.section("车外试听")
        label("选择本地音频试听，最长15秒。")
        button("检测车外 / 锁车试听接口（不播放）") { status.text = firmwareAudio.probe() }
        FirmwareAudioRoute.entries.filter { it != FirmwareAudioRoute.LOCK_EVENT }.forEach { route ->
            button("${route.title}：选择音频并试听") {
                showAudioPicker("选择${route.title}音频") { file ->
                    LocalDialogBuilder(this).setTitle(route.title)
                        .setMessage("文件：${file.name}\n可能在车外发声，请确认环境安全。最多播放15秒，离开页面即停止。不覆盖原厂音效。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("驻车试听") { _, _ -> playFirmwareAudio(route, file) }
                        .show()
                }
            }
        }

        ui.section("锁车与解锁")
        label("自动锁 / 解锁音效 · 手动布防 · 不修改原厂资源")
        val lockPrefs = getSharedPreferences("local-lock-sound", Context.MODE_PRIVATE)
        fun configuredSound(key: String): String {
            val path = lockPrefs.getString(key, null) ?: return "未设置"
            return audioLibrary.resolve(path)?.name ?: "${File(path).name}（目录不可用）"
        }
        val lockSoundLabel = label("当前锁车音效：${configuredSound("lock_file")}")
        val unlockSoundLabel = label("当前解锁音效：${configuredSound("unlock_file")}")
        ui.toggle("已实车确认11001事件路由与LockFL门锁原始码", lockPrefs.getBoolean("verified", false)) { checked ->
            lockPrefs.edit().putBoolean("verified", checked).apply()
            if (!checked) stopService(Intent(this, LocalLockSoundService::class.java))
        }
        button("设置锁车音效") {
            showAudioPicker("选择锁车音效") { file ->
                lockPrefs.edit().putString("lock_file", file.path).apply()
                lockSoundLabel.text = "当前锁车音效：${file.name}"
                status.text = "锁车音效已设置：${file.name}"
            }
        }
        button("设置解锁音效") {
            showAudioPicker("选择解锁音效") { file ->
                lockPrefs.edit().putString("unlock_file", file.path).apply()
                unlockSoundLabel.text = "当前解锁音效：${file.name}"
                status.text = "解锁音效已设置：${file.name}"
            }
        }
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
        button("读取布防状态") { status.text = lockPrefs.getString("last_status", "未布防") ?: "未布防" }

    }

internal fun LocalToolsActivity.playLocalAudio(requested: File) {
        val file = audioLibrary.resolve(requested.path) ?: run {
            status.text = "音频目录未授权或文件已不可用，请到“本地媒体”重新扫描"
            return
        }
        if (currentAudio != file) { lyricLines = emptyList(); lyricLabel?.text = "" }
        currentAudio = file
        recorder?.finish(false); speech?.stop()
        stopAudio()
        localPlayer.play(file, selectedOutput)
    }

internal fun LocalToolsActivity.playFirmwareAudio(route: FirmwareAudioRoute, requested: File) {
        if (busy) return
        val file = audioLibrary.resolve(requested.path) ?: run {
            status.text = "音频目录未授权或文件已不可用，请到“本地媒体”重新扫描"
            return
        }
        lifecycleScope.launch {
            busy = true
            try {
                stopAudio(); recorder?.finish(false); speech?.stop()
                val request = audioRequestGeneration
                val snapshot = kotlinx.coroutines.withTimeoutOrNull(2500) { vehicle.readSnapshot() }
                check(request == audioRequestGeneration && firmwareAudioAllowed(snapshot?.gear, snapshot?.speed, resumed)) { "非驻车、车辆状态未知或页面不可见" }
                val attrs = firmwareAudio.focusAttributes(route)
                LocalFileLog.get(this@playFirmwareAudio).record("audio/vehicle", "gear=${snapshot?.gear}; speed=${snapshot?.speed}; route=$route")
                firmwarePlayback = true
                localPlayer.play(file, null, attrs, route.title, 15_000L)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: java.io.IOException) {
                stopAudio(); status.text = "专用路由未启动：${error.message}；没有降级为普通媒体"
            } catch (error: IllegalStateException) {
                stopAudio(); status.text = "专用路由未启动：${error.message}；没有降级为普通媒体"
            } catch (error: IllegalArgumentException) {
                stopAudio(); status.text = "专用路由未启动：${error.message}；没有降级为普通媒体"
            } catch (error: SecurityException) {
                stopAudio(); status.text = "专用路由未启动：${error.message}；没有降级为普通媒体"
            } catch (error: ReflectiveOperationException) {
                stopAudio(); status.text = "专用路由未启动：${error.message}；没有降级为普通媒体"
            } finally { busy = false }
        }
    }

internal fun LocalToolsActivity.stopAudio() { audioRequestGeneration++; talkRequestGeneration++; micRelay?.stop(); firmwarePlayback = false; localPlayer.stop() }

internal fun LocalToolsActivity.diagnosticControls() {
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
                        .put("overlay_permission", Settings.canDrawOverlays(this@diagnosticControls))
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
                } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: java.io.IOException) { status.text = "检测失败：${error.message}" } catch (error: IllegalStateException) { status.text = "检测失败：${error.message}" } catch (error: IllegalArgumentException) { status.text = "检测失败：${error.message}" } catch (error: SecurityException) { status.text = "检测失败：${error.message}" } catch (error: ReflectiveOperationException) { status.text = "检测失败：${error.message}" }
                finally { busy = false }
            }
        }
        button("导出 JSON 适配报告") {
            if (diagnosticReport.isBlank()) status.text = "请先刷新报告" else exportDiagnostics.launch("BYDMateCN-capabilities.json")
        }
    }
