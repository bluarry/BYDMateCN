package com.bydmatecn.app.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bydmatecn.app.data.vehicle.BatchReadItem
import com.bydmatecn.app.data.vehicle.HelperClient
import com.bydmatecn.app.data.vehicle.HelperBootstrap
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Foreground lock/unlock observer. It stays active while the user enables event sounds. */
@AndroidEntryPoint
class LocalLockSoundService : Service() {
    @Inject lateinit var helper: HelperClient
    @Inject lateinit var helperBootstrap: HelperBootstrap
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private lateinit var player: LocalAudioPlayer
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var lastSampleSummary: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockAcquiredAt = 0L
    private var consecutiveReadFailures = 0
    private var lastHelperRecoveryAt = -HELPER_RECOVERY_INTERVAL_MS

    override fun onCreate() {
        super.onCreate()
        player = LocalAudioPlayer(this, helper) { note(it) }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bydmatecn:lock_sound")
            .apply { setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?) = null

    @Suppress("TooGenericExceptionCaught") // Vendor vehicle APIs throw undocumented runtime exception subtypes.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply()
            note("用户停止锁车/解锁音效")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ARM && !(intent == null && prefs.getBoolean(KEY_ENABLED, false))) {
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        createNotification()
        holdWakeLock()
        job?.cancel()
        player.stop()
        job = scope.launch {
            val detector = LockSoundEventDetector(initialLock = prefs.getInt(KEY_LAST_LOCK, 0).takeIf { it in VALID_LOCK_CODES })
            try {
                check(prefs.getBoolean("verified", false)) { "门锁状态与车外音频控制器尚未确认" }
                val profile = ExteriorControllerProfile.selected(this@LocalLockSoundService)
                note("观察服务启动：profile=${profile.preferenceValue}; stream=${profile.streamType}; 等待门锁基线")
                observeEvents(detector)
            } catch (error: CancellationException) {
                throw error
            } catch (error: RuntimeException) {
                note("观察服务失败：${error.javaClass.simpleName}: ${error.message}")
            } finally {
                if (!prefs.getBoolean(KEY_ENABLED, false)) stopSelf(startId)
            }
        }
        return START_STICKY
    }

    private fun createNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "锁车与解锁音效", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, LocalLockSoundService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("BYDMateCN 事件音效")
                .setContentText("正在观察门锁与灯光确认信号")
                .addAction(0, "停止", stop).setOngoing(true).build(),
        )
    }

    private fun holdWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) lock.release()
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        wakeLockAcquiredAt = SystemClock.elapsedRealtime()
        note("CPU 保持锁已获取：最长${WAKE_LOCK_TIMEOUT_MS / 60_000}分钟")
    }

    private fun note(value: String) {
        LocalFileLog.get(this).record("lock-sound", value)
        prefs.edit().putString("last_status", value).putLong("last_time", System.currentTimeMillis()).apply()
    }

    override fun onDestroy() {
        note("锁车/解锁音效观察服务停止")
        job?.cancel()
        scope.cancel()
        player.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        note("CPU 保持锁已释放")
        stopForeground(true)
        super.onDestroy()
    }

    private suspend fun observeEvents(detector: LockSoundEventDetector) {
        while (currentCoroutineContext().isActive && prefs.getBoolean(KEY_ENABLED, false)) {
            val now = SystemClock.elapsedRealtime()
            if (now - wakeLockAcquiredAt >= WAKE_LOCK_RENEW_MS) holdWakeLock()
            val sample = readSample()
            sample.lockFL?.takeIf { it in VALID_LOCK_CODES }?.let { prefs.edit().putInt(KEY_LAST_LOCK, it).apply() }
            val summary = "lockFL=${sample.lockFL}; flash=${sample.turnSignalFlash}; doorFL=${sample.doorFL}; power=${sample.powerState}; gear=${sample.gear}; speed=${sample.speedKph}; motion=${sample.motion}; wake=${wakeLock?.isHeld == true}"
            if (summary != lastSampleSummary) {
                note("状态：$summary")
                lastSampleSummary = summary
            }
            detector.sample(sample, now)?.let { detection ->
                when {
                    detection.suppressed ->
                        note("事件抑制：${detection.event}; reason=${detection.reason}; $summary")
                    sample.motion == VehicleMotionState.MOVING ->
                        note("事件抑制：${detection.event}; 行车状态不播放；reason=${detection.reason}; $summary")
                    else -> {
                        note("事件确认：${detection.event}; reason=${detection.reason}; $summary")
                        playEvent(detection.event)
                    }
                }
            }
            delay(POLL_MS)
        }
    }

    @Suppress("CyclomaticComplexMethod") // Batch decode, motion classification and helper recovery stay atomic.
    private suspend fun readSample(): LockSoundSample {
        val pairs = withTimeoutOrNull(LOCK_READ_TIMEOUT_MS) { helper.readBatch(SAMPLE_ITEMS) }
        if (pairs != null && pairs.size == SAMPLE_ITEMS.size) {
            consecutiveReadFailures = 0
            fun value(index: Int): Int? = pairs[index].let { (status, raw) -> raw.takeIf { status == 0 } }
            val speed = value(5)?.let(VehicleSafetyProbe::decodeSpeed)
            val gear = value(4)
            val power = value(3)
            val moving = (speed != null && kotlin.math.abs(speed) > 0.5f) ||
                (gear != null && gear != VehicleSafetyProbe.PARK_GEAR)
            val parked = gear == VehicleSafetyProbe.PARK_GEAR && speed != null && kotlin.math.abs(speed) <= 0.5f
            val motion = when { moving -> VehicleMotionState.MOVING; parked -> VehicleMotionState.PARKED; else -> VehicleMotionState.UNKNOWN }
            return LockSoundSample(value(0), value(1), value(2), power, gear, speed, motion)
        }
        consecutiveReadFailures++
        val now = SystemClock.elapsedRealtime()
        if (consecutiveReadFailures >= HELPER_RECOVERY_FAILURES && now - lastHelperRecoveryAt >= HELPER_RECOVERY_INTERVAL_MS) {
            lastHelperRecoveryAt = now
            val recovered = withTimeoutOrNull(HELPER_RECOVERY_TIMEOUT_MS) { helperBootstrap.ensureRunning() } == true
            note("helper恢复：success=$recovered; consecutiveFailures=$consecutiveReadFailures")
            if (recovered) consecutiveReadFailures = 0
        }
        // Keep a direct LockFL fallback for firmware whose helper does not expose batch reads.
        val lock = withTimeoutOrNull(LOCK_READ_TIMEOUT_MS) {
            helper.read(LOCK_DEVICE, LOCK_FID)?.toInt()?.takeIf { it in VALID_LOCK_CODES }
        }
        return LockSoundSample(lock, null, null, null, null, null, VehicleMotionState.UNKNOWN)
    }

    private fun playEvent(event: LockSoundEvent) {
        val locked = event == LockSoundEvent.LOCK
        val key = if (locked) "lock_file" else "unlock_file"
        val name = prefs.getString(key, null)
        val file = LocalAudioLibrary(this).resolve(name)
        note("准备${if (locked) "锁车" else "解锁"}音效：configured=${name != null}; readable=${file != null}")
        if (file == null) {
            note("${if (locked) "锁车" else "解锁"}音效未配置、目录授权失效或文件已删除")
            return
        }
        player.setVolume(prefs.getInt("volume", 25).coerceIn(0, 50))
        player.play(
            file, null, FirmwareAudioBridge(this).focusAttributes(FirmwareAudioRoute.LOCK_EVENT),
            if (locked) "锁车事件车外播放" else "解锁事件车外播放",
            EVENT_PLAY_MAX_MS,
            startDelayMs = EVENT_ROUTE_WARMUP_MS,
        )
    }

    companion object {
        const val ARM = "com.bydmatecn.app.LOCK_ARM"
        const val STOP = "com.bydmatecn.app.LOCK_STOP"
        const val PREFS = "local-lock-sound"
        const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_LOCK = "last_observed_lock"
        private const val CHANNEL = "local_lock_sound"
        private const val NOTIFICATION_ID = 5813
        private const val LOCK_DEVICE = 1032
        private const val LOCK_FID = 1_081_081_864
        private val VALID_LOCK_CODES = 1..2
        private const val POLL_MS = 250L
        private const val LOCK_READ_TIMEOUT_MS = 1_500L
        private const val EVENT_PLAY_MAX_MS = 15_000L
        private const val EVENT_ROUTE_WARMUP_MS = 1_500L
        private const val HELPER_RECOVERY_FAILURES = 3
        private const val HELPER_RECOVERY_INTERVAL_MS = 15_000L
        private const val HELPER_RECOVERY_TIMEOUT_MS = 20_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60_000L
        private const val WAKE_LOCK_RENEW_MS = 25 * 60_000L
        private val SAMPLE_ITEMS = listOf(
            BatchReadItem(5, 1032, 1_081_081_864), // LockFL
            BatchReadItem(5, 1004, 950_009_900),   // turnSignal / flash confirmation
            BatchReadItem(5, 1001, 692_060_168),   // front-left door
            BatchReadItem(5, 1023, 315_621_408),   // vehicle power state
            BatchReadItem(5, 1011, 555_745_336),   // gear
            BatchReadItem(7, 1013, -1_807_745_016), // speed float bits
        )

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LocalLockSoundService::class.java).setAction(ARM))
        }

        fun restoreIfEnabled(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_ENABLED, false) && prefs.getBoolean("verified", false)) start(context)
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
            context.stopService(Intent(context, LocalLockSoundService::class.java))
        }
    }
}
