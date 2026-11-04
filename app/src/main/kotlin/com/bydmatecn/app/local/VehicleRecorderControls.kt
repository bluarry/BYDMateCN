package com.bydmatecn.app.local

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/** Automatic recorder settings share the proven camera page and its parked preview. */
@Suppress("LongMethod") // One ordered settings section; splitting it would obscure the car-screen layout.
internal fun LocalCameraActivity.addAutomaticRecorderControls() {
    val prefs = VehicleRecorderPreferences.prefs(this)
    val sentry = featureMode == LocalCameraActivity.MODE_SENTRY
    ui.section(if (sentry) "驻车哨兵" else "行车记录仪")
    if (sentry) {
        ui.toggle("熄火、P 挡且锁车后启动驻车哨兵", prefs.getBoolean(VehicleRecorderPreferences.KEY_SENTRY, false)) { enabled ->
            prefs.edit().putBoolean(VehicleRecorderPreferences.KEY_SENTRY, enabled).apply()
            updateRecorderService()
            status.text = if (enabled) "驻车哨兵已启用；锁车后按配置时长录像" else "驻车哨兵已关闭"
        }
    } else {
        ui.toggle("上电行驶时自动分段录像", prefs.getBoolean(VehicleRecorderPreferences.KEY_DASHCAM, false)) { enabled ->
            prefs.edit().putBoolean(VehicleRecorderPreferences.KEY_DASHCAM, enabled).apply()
            updateRecorderService()
            status.text = if (enabled) "行车记录仪已启用；满足上电行驶条件后自动录像" else "行车记录仪已关闭"
        }
    }
    cameraButton("授予后台录像所需权限") { requestAutomaticRecorderPermissions() }
    addSharedRecorderControls(prefs)
    if (sentry) addSentryRecorderControls(prefs)
    cameraButton("立即刷新${if (sentry) "驻车哨兵" else "行车记录仪"}服务") {
        updateRecorderService()
        status.text = prefs.getString(VehicleRecorderPreferences.KEY_LAST_STATUS, "服务已刷新") ?: "服务已刷新"
    }
    cameraButton("停止本次录像（保留自动开关）") {
        stopService(Intent(this, VehicleRecorderService::class.java))
        status.text = "本次录像服务已停止；下次应用/车机启动时按开关恢复"
    }
    cameraButton("截图与录像相册") { startActivity(Intent(this, LocalMediaActivity::class.java)) }
    val last = prefs.getString(VehicleRecorderPreferences.KEY_LAST_STATUS, null)
    ui.label(last?.let { "最近状态：$it" } ?: "后台录像尚无运行记录。所有启停、抢占、分段和事件均写入应用日志。")
}

private fun LocalCameraActivity.addSharedRecorderControls(prefs: android.content.SharedPreferences) {
    cameraButton("录像方向") {
        val values = RecorderDirection.entries
        val current = VehicleRecorderPreferences.capturePlan(this)
        LocalDialogBuilder(this).setTitle("选择录像方向（每个方向独立保存通道与镜像）")
            .setItems(values.map { if (it == current.direction) "${it.displayName}（当前）" else it.displayName }.toTypedArray()) { _, which ->
                prefs.edit().putString(VehicleRecorderPreferences.KEY_CAPTURE_DIRECTION, values[which].name).apply()
                restartRecorderForCapturePlan("录像方向已设为${values[which].displayName}")
            }.show()
    }
    cameraButton("录像 AVM 通道") {
        val current = VehicleRecorderPreferences.capturePlan(this)
        val values = (1..4).toList()
        LocalDialogBuilder(this).setTitle("选择与录像方向对应的固件通道")
            .setItems(values.map { if (it == current.surfaceIndex) "通道 $it（当前）" else "通道 $it" }.toTypedArray()) { _, which ->
                VehicleRecorderPreferences.setCaptureSurface(this, current.direction, values[which])
                restartRecorderForCapturePlan("${current.direction.displayName}录像通道已设为 ${values[which]}")
            }.show()
    }
    val capturePlan = VehicleRecorderPreferences.capturePlan(this)
    ui.toggle("录像画面水平镜像", capturePlan.horizontalFlip) { enabled ->
        VehicleRecorderPreferences.setCaptureFlip(this, capturePlan.direction, enabled)
        restartRecorderForCapturePlan(if (enabled) "${capturePlan.direction.displayName}录像画面已启用水平镜像" else "${capturePlan.direction.displayName}录像画面已关闭水平镜像")
    }
    cameraButton("录像分段时长") {
        val values = intArrayOf(1, 3, 5)
        LocalDialogBuilder(this).setTitle("每段录像时长").setItems(values.map { "$it 分钟" }.toTypedArray()) { _, which ->
            prefs.edit().putInt(VehicleRecorderPreferences.KEY_SEGMENT_MINUTES, values[which]).apply()
            status.text = "录像分段已设置为 ${values[which]} 分钟"
        }.show()
    }
    cameraButton("循环存储上限") {
        val values = intArrayOf(512, 1024, 2048, 4096, 8192)
        LocalDialogBuilder(this).setTitle("应用内录像存储上限")
            .setItems(values.map { if (it < 1024) "$it MiB" else "${it / 1024} GiB" }.toTypedArray()) { _, which ->
                prefs.edit().putInt(VehicleRecorderPreferences.KEY_QUOTA_MB, values[which]).apply()
                status.text = "录像存储上限将在下一段生效：${values[which]} MiB"
            }.show()
    }
}

private fun LocalCameraActivity.addSentryRecorderControls(prefs: android.content.SharedPreferences) {
    cameraButton("驻车哨兵最长运行时间") {
        val values = intArrayOf(1, 3, 6, 12)
        LocalDialogBuilder(this).setTitle("单次驻车最长录像").setItems(values.map { "$it 小时" }.toTypedArray()) { _, which ->
            prefs.edit().putInt(VehicleRecorderPreferences.KEY_SENTRY_HOURS, values[which]).apply()
            status.text = "驻车哨兵最长运行 ${values[which]} 小时"
        }.show()
    }
    ui.toggle("开门事件保护", prefs.getBoolean(VehicleRecorderPreferences.KEY_DOOR_EVENT, true)) {
        prefs.edit().putBoolean(VehicleRecorderPreferences.KEY_DOOR_EVENT, it).apply()
    }
    ui.toggle("震动事件保护（车机有加速度传感器时）", prefs.getBoolean(VehicleRecorderPreferences.KEY_IMPACT_EVENT, true)) {
        prefs.edit().putBoolean(VehicleRecorderPreferences.KEY_IMPACT_EVENT, it).apply()
    }
    val threshold = prefs.getInt(VehicleRecorderPreferences.KEY_IMPACT_THRESHOLD, 25).coerceIn(15, 60)
    ui.slider(LocalSlider(threshold, max = 60, title = "震动触发阈值（×0.1 m/s²）"), changed = {
        prefs.edit().putInt(VehicleRecorderPreferences.KEY_IMPACT_THRESHOLD, it.coerceIn(15, 60)).apply()
    })
}
private fun LocalCameraActivity.restartRecorderForCapturePlan(message: String) {
    VehicleRecorderService.reloadCapturePlan(this)
    status.text = if (VehicleRecorderPreferences.enabled(this)) {
        "$message；录像服务正在安全重载画面方案"
    } else {
        "$message；将在下次启用录像时生效"
    }
}

private fun LocalCameraActivity.updateRecorderService() {
    if (VehicleRecorderPreferences.enabled(this)) VehicleRecorderService.start(this)
    else VehicleRecorderService.stop(this)
}

private fun LocalCameraActivity.requestAutomaticRecorderPermissions() {
    when {
        checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ->
            permission.launch(Manifest.permission.CAMERA)
        !Settings.canDrawOverlays(this) -> runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }.onFailure { status.text = "无法打开悬浮窗授权页面：${it.message}" }
        else -> {
            updateRecorderService()
            status.text = "后台录像权限已具备；服务已按当前开关刷新"
        }
    }
}
