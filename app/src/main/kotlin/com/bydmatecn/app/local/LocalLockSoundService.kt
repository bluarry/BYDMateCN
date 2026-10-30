package com.bydmatecn.app.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.bydmatecn.app.data.vehicle.HelperClient
import com.bydmatecn.app.data.vehicle.VehicleApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** User-armed foreground lock/unlock observer. Never boots itself or holds a wake lock. */
@AndroidEntryPoint
class LocalLockSoundService : Service() {
    @Inject lateinit var vehicle: VehicleApi
    @Inject lateinit var helper: HelperClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private lateinit var player: LocalAudioPlayer
    private val prefs by lazy { getSharedPreferences("local-lock-sound", MODE_PRIVATE) }
    override fun onCreate() { super.onCreate(); player = LocalAudioPlayer(this, helper) { note(it) } }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ARM) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "本地锁车音效观察", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, LocalLockSoundService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(5813, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("本地锁车音效已手动布防").setContentText("最多2小时；系统休眠不唤醒；低电压停止")
            .addAction(0, "停止", stop).setOngoing(true).build())
        job?.cancel(); player.stop()
        job = scope.launch {
            val edges = LockSoundEdge()
            val deadline = SystemClock.elapsedRealtime() + 2*60*60*1000
            try {
                check(prefs.getBoolean("verified", false)) { "自动锁车路由未实车确认" }
                // 11001, not the preview context: availability checked before arming.
                FirmwareAudioBridge(this@LocalLockSoundService).focusAttributes(FirmwareAudioRoute.LOCK_EVENT)
                note("已布防：等待有效门锁基线，不在启动时播放")
                observeEdges(edges, deadline)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: java.io.IOException) {
                note("布防失败：${error.message}")
            } catch (error: IllegalStateException) {
                note("布防失败：${error.message}")
            } catch (error: IllegalArgumentException) {
                note("布防失败：${error.message}")
            } catch (error: SecurityException) {
                note("布防失败：${error.message}")
            }  finally { stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
    private fun note(value: String) {
        LocalFileLog.get(this).record("lock-sound", value)
        prefs.edit().putString("last_status", value).putLong("last_time", System.currentTimeMillis()).apply()
    }
    override fun onDestroy() { note("布防服务停止"); job?.cancel(); scope.cancel(); player.stop(); stopForeground(true); super.onDestroy() }
    companion object { const val ARM = "com.bydmatecn.app.LOCK_ARM"; const val STOP = "com.bydmatecn.app.LOCK_STOP"; private const val CHANNEL = "local_lock_sound" }
    private suspend fun observeEdges(edges: LockSoundEdge, deadline: Long) {
        while (currentCoroutineContext().isActive && SystemClock.elapsedRealtime() < deadline) {
                    val data = withTimeoutOrNull(2500) { vehicle.readSnapshot() }
                    val safe = data?.gear == 1 && data.speed == 0 && data.voltage12v != null && data.voltage12v >= 12.0
                    if (data?.voltage12v != null && data.voltage12v < 12.0) { note("12V低于12.0V，停止布防"); break }
                    if (!safe) player.stop()
                    val event = edges.sample(data?.lockFL, safe, SystemClock.elapsedRealtime())
                    if (event != null) playEvent(event)
                    delay(500)

        }
    }

    private fun playEvent(event: Int) {
                        val name = prefs.getString(if (event == 2) "lock_file" else "unlock_file", null)
                        val file = LocalAudioLibrary(this).resolve(name)
                        if (file != null) {
                            player.setVolume(prefs.getInt("volume", 25).coerceIn(0, 50))
                            player.play(file, null, FirmwareAudioBridge(this@LocalLockSoundService).focusAttributes(FirmwareAudioRoute.LOCK_EVENT),
                                if (event == 2) "本地锁车事件" else "本地解锁事件", 5000)
                        } else note("事件$event：未配置或音频已删除")

    }
}
