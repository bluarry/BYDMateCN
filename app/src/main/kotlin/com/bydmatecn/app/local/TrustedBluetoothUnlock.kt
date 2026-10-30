package com.bydmatecn.app.local

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context

/** Persistent configuration and replay protection for trusted-Bluetooth vehicle unlock. */
@Suppress("TooManyFunctions")
internal object TrustedBluetoothUnlock {
    private const val PREFS = "trusted-bluetooth-unlock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"
    private const val KEY_ARMED = "armed"
    private const val KEY_LAST_ATTEMPT = "last_attempt"
    private const val KEY_LAST_EVENT = "last_event"
    const val COOLDOWN_MS = 60_000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    fun address(context: Context): String? = prefs(context).getString(KEY_ADDRESS, null)?.uppercase()
    fun name(context: Context): String? = prefs(context).getString(KEY_NAME, null)
    fun select(context: Context, address: String, name: String?) {
        prefs(context).edit()
            .putString(KEY_ADDRESS, address.uppercase())
            .putString(KEY_NAME, name.orEmpty())
            .putBoolean(KEY_ARMED, true)
            .apply()
    }
    fun clear(context: Context) = prefs(context).edit()
        .remove(KEY_ADDRESS).remove(KEY_NAME).putBoolean(KEY_ENABLED, false).putBoolean(KEY_ARMED, true).apply()

    fun isTrusted(context: Context, address: String?): Boolean =
        enabled(context) && address != null && address.equals(this.address(context), ignoreCase = true)

    fun arm(context: Context, address: String?) {
        if (address != null && address.equals(this.address(context), ignoreCase = true)) {
            prefs(context).edit().putBoolean(KEY_ARMED, true).putString(KEY_LAST_EVENT, "受信任设备已断开，已重新布防").apply()
        }
    }

    data class ConnectionDecision(val accepted: Boolean, val reason: String)

    /** Atomically consumes one connection edge. Reconnect storms cannot repeatedly unlock. */
    fun consumeConnection(context: Context, address: String, now: Long = System.currentTimeMillis()): ConnectionDecision {
        if (!isTrusted(context, address)) return ConnectionDecision(false, "设备不是当前已启用的受信任设备")
        val p = prefs(context)
        val armed = p.getBoolean(KEY_ARMED, true)
        val last = p.getLong(KEY_LAST_ATTEMPT, 0L)
        val elapsed = if (last == 0L) Long.MAX_VALUE else (now - last).coerceAtLeast(0L)
        if (!armed) return ConnectionDecision(false, "同一次蓝牙连接已经消费，等待断开后重新布防")
        if (elapsed < COOLDOWN_MS) {
            return ConnectionDecision(false, "仍在冷却期，剩余 ${COOLDOWN_MS - elapsed}ms")
        }
        val saved = p.edit().putBoolean(KEY_ARMED, false).putLong(KEY_LAST_ATTEMPT, now)
            .putString(KEY_LAST_EVENT, "收到受信任设备连接，等待车辆安全检查").commit()
        return if (saved) ConnectionDecision(true, "连接事件已消费，开始车辆安全检查")
        else ConnectionDecision(false, "连接状态持久化失败")
    }

    fun setLastEvent(context: Context, text: String) = prefs(context).edit().putString(KEY_LAST_EVENT, text).apply()
    fun lastEvent(context: Context): String = prefs(context).getString(KEY_LAST_EVENT, "尚未收到蓝牙连接事件") ?: "尚未收到蓝牙连接事件"

    @Suppress("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> = runCatching {
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty()
            .sortedWith(compareBy({ runCatching { it.name }.getOrNull().orEmpty() }, { it.address }))
    }.getOrDefault(emptyList())
}
