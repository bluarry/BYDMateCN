package com.bydmatecn.app.local

import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Activity-scoped controls; the activity owns state and lifecycle cleanup. */
internal fun LocalToolsActivity.permissionForCamera() {
        // Permission grant remains controlled by the system/user, not by firmware extraction.
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
            .onFailure { status.text = "请在系统设置中授予相机权限" }
    }

internal fun LocalToolsActivity.sentryControls() {
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

internal fun LocalToolsActivity.vehicleControls(guard: Boolean) {
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
