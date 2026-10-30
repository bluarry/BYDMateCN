package com.bydmatecn.app.local

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.bydmatecn.app.service.TrackingService

/** Receives the system Bluetooth edge even when the UI and tracking process were asleep. */
class TrustedBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val device = runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }.getOrNull()
        val address = runCatching { device?.address }.getOrNull()
        val log = LocalFileLog.get(context)
        when (action) {
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                TrustedBluetoothUnlock.arm(context, address)
                if (TrustedBluetoothUnlock.address(context).equals(address, true)) {
                    log.record("bluetooth-unlock", "trusted device disconnected; armed")
                }
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val trusted = TrustedBluetoothUnlock.isTrusted(context, address)
                log.record("bluetooth-unlock", "ACL connected trusted=$trusted address=${mask(address)}")
                if (address == null) {
                    log.record("bluetooth-unlock", "connection ignored: BluetoothDevice/address missing")
                    return
                }
                val decision = TrustedBluetoothUnlock.consumeConnection(context, address)
                log.record("bluetooth-unlock", "connection decision accepted=${decision.accepted} reason=${decision.reason}")
                if (!decision.accepted) return
                val start = Intent(context, TrackingService::class.java)
                    .setAction(TrackingService.ACTION_TRUSTED_BLUETOOTH_UNLOCK)
                    .putExtra(TrackingService.EXTRA_BLUETOOTH_ADDRESS, address)
                    .putExtra(com.bydmatecn.app.service.AutostartTrace.EXTRA_TRIGGER, "trusted_bluetooth")
                runCatching { ContextCompat.startForegroundService(context, start) }
                    .onSuccess { log.record("bluetooth-unlock", "foreground vehicle service start requested") }
                    .onFailure {
                        val result = "无法启动后台车辆服务：${it.javaClass.simpleName}"
                        TrustedBluetoothUnlock.setLastEvent(context, result)
                        log.record("bluetooth-unlock", result)
                        Log.w(TAG, result)
                    }
            }
        }
    }

    private fun mask(address: String?): String = address?.takeLast(5)?.let { "**:**:**:**:$it" } ?: "unknown"

    private companion object { const val TAG = "TrustedBluetooth" }
}
