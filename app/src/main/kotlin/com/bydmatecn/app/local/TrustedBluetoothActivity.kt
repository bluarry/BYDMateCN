package com.bydmatecn.app.local

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Selects one bonded phone/device and arms the conservative Bluetooth unlock trigger. */
class TrustedBluetoothActivity : ComponentActivity(), LocalComposeHost {
    override val ui = LocalUiState()
    private val status = LocalText()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        LocalFileLog.get(this).record("bluetooth-unlock", "BLUETOOTH_CONNECT permission result granted=$granted")
        if (granted) rebuild() else status.text = "蓝牙连接权限未授予，无法读取已配对设备"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installLocalContent(ui)
        rebuild()
    }
    @Suppress("MissingPermission")
    private fun rebuild() {
        ui.controls.clear(); ui.sections.clear(); ui.actionIndices.clear()
        ui.title = "蓝牙自动解锁"
        ui.subtitle = "受信任设备连接后，在安全条件满足时解锁车辆"
        ui.onBack = { finish() }
        ui.status = status
        status.text = TrustedBluetoothUnlock.lastEvent(this)

        ui.section("状态与安全")
        val selectedAddress = TrustedBluetoothUnlock.address(this)
        val selectedName = TrustedBluetoothUnlock.name(this).orEmpty()
        ui.label(if (selectedAddress == null) "尚未选择受信任设备" else "当前设备：${selectedName.ifBlank { "未命名设备" }}\n${mask(selectedAddress)}")
        ui.label("仅在受信任设备从断开变为连接后触发。执行前必须读取到 P 挡、车速 0、车门已锁且全部车门关闭；任一状态未知都会拒绝解锁。断开后才会重新布防。", 14f)
        ui.toggle("启用蓝牙连接自动解锁", TrustedBluetoothUnlock.enabled(this)) { enabled ->
            if (enabled && TrustedBluetoothUnlock.address(this) == null) {
                TrustedBluetoothUnlock.setEnabled(this, false)
                status.text = "请先选择一个已配对设备，再启用自动解锁"
                rebuild()
            } else {
                TrustedBluetoothUnlock.setEnabled(this, enabled)
                status.text = if (enabled) "自动解锁已启用，等待受信任设备重新连接" else "自动解锁已关闭"
                LocalFileLog.get(this).record("bluetooth-unlock", "feature enabled=$enabled trusted=${mask(TrustedBluetoothUnlock.address(this).orEmpty())}")
            }
        }
        ui.button("清除受信任设备") {
            TrustedBluetoothUnlock.clear(this)
            LocalFileLog.get(this).record("bluetooth-unlock", "trusted device cleared; feature disabled")
            status.text = "已清除受信任设备并关闭自动解锁"
            rebuild()
        }

        ui.section("已配对设备")
        if (!hasBluetoothPermission()) {
            ui.label("需要蓝牙连接权限才能列出车机已经配对的设备。")
            ui.button("授予蓝牙连接权限") { requestBluetoothPermission() }
            return
        }
        val devices = TrustedBluetoothUnlock.bondedDevices()
        LocalFileLog.get(this).record("bluetooth-unlock", "configuration page bondedDevices=${devices.size} enabled=${TrustedBluetoothUnlock.enabled(this)}")
        if (devices.isEmpty()) {
            ui.label("未找到已配对设备。请先在车机系统蓝牙设置中完成配对，再返回此页面。")
        } else {
            devices.forEach { device -> addDevice(device, selectedAddress) }
        }
        ui.section("验证记录")
        ui.label("蓝牙广播、唤醒、车辆状态检查、Helper 启动及解锁结果都会写入本地问题日志，可从“音频 → 诊断与日志”导出。", 14f)
        ui.button("刷新设备与状态") { rebuild() }
    }

    @Suppress("MissingPermission")
    private fun addDevice(device: BluetoothDevice, selected: String?) {
        val address = runCatching { device.address }.getOrNull() ?: return
        val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "未命名设备" }
        val mark = if (address.equals(selected, true)) "  ·  已选择" else ""
        ui.button("$name$mark\n${mask(address)}") {
            TrustedBluetoothUnlock.select(this, address, name)
            status.text = "已选择 $name；确认安全说明后可启用自动解锁"
            LocalFileLog.get(this).record("bluetooth-unlock", "trusted device selected address=${mask(address)}")
            rebuild()
        }
    }

    private fun hasBluetoothPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun mask(address: String): String = "**:**:**:**:${address.takeLast(5)}"
}


