package com.bydmatecn.app.local

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bydmatecn.app.camera.AvmCameraProbe
import com.bydmatecn.app.camera.AvmFrameShare
import com.bydmatecn.app.data.camera.CameraStateMonitor
import com.bydmatecn.app.data.vehicle.BatchReadItem
import com.bydmatecn.app.data.vehicle.HelperBootstrap
import com.bydmatecn.app.data.vehicle.HelperClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * Dashcam and parking-sentry encoder backed by one AVM producer at a time.
 * Interactive BYDMateCN previews own the vendor handle while visible and publish their rendered
 * TextureView through [AvmFrameShare], so encoding continues without opening a competing handle.
 */
@AndroidEntryPoint
@Suppress("TooManyFunctions") // Android callbacks plus one recorder state machine stay in one lifecycle owner.
class VehicleRecorderService : Service(), TextureView.SurfaceTextureListener, SensorEventListener {
    @Inject lateinit var helper: HelperClient
    @Inject lateinit var helperBootstrap: HelperBootstrap
    @Inject lateinit var factoryCamera: CameraStateMonitor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val encoder = Executors.newSingleThreadExecutor()
    private val frameBusy = AtomicBoolean(false)
    private val prefs by lazy { VehicleRecorderPreferences.prefs(this) }
    private val probe by lazy {
        AvmCameraProbe(eventSink = { note("avm: $it") }, yieldToForeground = true)
    }
    private lateinit var windowManager: WindowManager
    private var texture: TextureView? = null
    private var surface: Surface? = null
    @Volatile private var recorder: LocalVideoRecorder? = null
    @Volatile private var mode = VehicleRecorderMode.OFF
    private var monitorJob: Job? = null
    private var frameJob: Job? = null
    @Volatile private var segmentStarted = 0L
    private var sentryStarted = 0L
    private var lastDecision = ""
    private var previousDoors: List<Int?>? = null
    private var lastEventAt = -EVENT_GUARD_MS
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null
    private var gravity = FloatArray(3)
    private var sensorReady = false
    private var sensorSamples = 0
    @Volatile private var captureSession = 0
    private var lastSurfaceFrameAt = 0L
    private var sentryLimitReached = false
    private var lastForegroundPackage: String? = null
    private var usageCursorMs = 0L
    private var activeFrameSource = ""
    private var lastSourceAttemptAt = 0L

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bydmatecn:vehicle_recorder")
            .apply { setReferenceCounted(false) }
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        createNotification("等待车辆状态")
        createCaptureTexture()
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> {
                prefs.edit().putBoolean(VehicleRecorderPreferences.KEY_DASHCAM, false)
                    .putBoolean(VehicleRecorderPreferences.KEY_SENTRY, false).apply()
                note("用户停止并关闭自动录像")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (!VehicleRecorderPreferences.enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (texture == null && Settings.canDrawOverlays(this)) createCaptureTexture()
        if (monitorJob?.isActive != true) monitorJob = scope.launch { monitorLoop() }
        return START_STICKY
    }

    private fun createCaptureTexture() {
        if (texture != null) return
        if (!Settings.canDrawOverlays(this)) {
            note("无法创建后台画面：未授予悬浮窗权限")
            return
        }
        val view = TextureView(this).apply { surfaceTextureListener = this@VehicleRecorderService }
        val params = WindowManager.LayoutParams(
            CAPTURE_WIDTH, CAPTURE_HEIGHT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = -CAPTURE_WIDTH + 2
            y = 0
            alpha = 0.01f
            title = "BYDMateCN recorder surface"
        }
        runCatching { windowManager.addView(view, params) }
            .onSuccess { texture = view; note("后台录像 Surface 已创建") }
            .onFailure { note("后台 Surface 创建失败：${it.javaClass.simpleName}: ${it.message}") }
    }

    override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
        value.setDefaultBufferSize(CAPTURE_WIDTH, CAPTURE_HEIGHT)
        surface = Surface(value)
        note("后台 Surface 可用：${width}x$height")
    }
    override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(value: SurfaceTexture) {
        lastSurfaceFrameAt = SystemClock.elapsedRealtime()
    }
    override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
        surface?.release(); surface = null
        stopCapture("后台 Surface 销毁")
        return true
    }

    private suspend fun monitorLoop() {
        note("自动录像服务启动：dashcam=${prefs.getBoolean(VehicleRecorderPreferences.KEY_DASHCAM, false)}; sentry=${prefs.getBoolean(VehicleRecorderPreferences.KEY_SENTRY, false)}")
        while (scope.isActive && VehicleRecorderPreferences.enabled(this)) {
            val sample = readVehicleState()
            val decision = decideVehicleRecorderMode(sample.toRecorderInput())
            logDecision(decision, sample)
            applyDecision(decision, sample)
            delay(STATE_POLL_MS)
        }
        stopSelf()
    }

    private fun RecorderVehicleState.toRecorderInput() = VehicleRecorderInput(
        dashcamEnabled = prefs.getBoolean(VehicleRecorderPreferences.KEY_DASHCAM, false),
        sentryEnabled = prefs.getBoolean(VehicleRecorderPreferences.KEY_SENTRY, false),
        factoryCameraActive = factoryCameraInUse(),
        powerState = power,
        gear = gear,
        speedKph = speed,
        locked = lock,
    )

    private fun logDecision(decision: VehicleRecorderDecision, sample: RecorderVehicleState) {
        val summary = "mode=${decision.mode}; reason=${decision.reason}; power=${sample.power}; " +
            "gear=${sample.gear}; speed=${sample.speed}; lock=${sample.lock}"
        if (summary == lastDecision) return
        note("状态判定：$summary")
        lastDecision = summary
    }

    private fun applyDecision(decision: VehicleRecorderDecision, sample: RecorderVehicleState) {
        if (decision.mode != VehicleRecorderMode.PARKING_SENTRY) sentryLimitReached = false
        if (!permissionsReady()) {
            stopIfRecording("后台录像权限不完整")
            return
        }
        if (decision.mode == VehicleRecorderMode.OFF) {
            stopIfRecording(decision.reason)
            return
        }
        if (decision.mode == VehicleRecorderMode.PARKING_SENTRY && sentryLimitReached) {
            stopIfRecording("本次驻车哨兵已达到时长上限")
            return
        }
        if (mode != decision.mode || recorder?.active != true) startCapture(decision.mode)
        if (decision.mode == VehicleRecorderMode.PARKING_SENTRY) updateParkingSentry(sample)
    }

    private fun stopIfRecording(reason: String) {
        if (mode != VehicleRecorderMode.OFF) stopCapture(reason)
    }

    private fun updateParkingSentry(sample: RecorderVehicleState) {
        inspectSentryEvents(sample)
        val hours = VehicleRecorderPreferences.sentryHours(this)
        val limit = hours * 60 * 60_000L
        if (sentryStarted <= 0 || SystemClock.elapsedRealtime() - sentryStarted < limit) return
        sentryLimitReached = true
        stopCapture("驻车哨兵达到最长 $hours 小时")
    }

    private fun factoryCameraInUse(): Boolean {
        if (factoryCamera.active.value) return true
        if (!usageAllowed()) return true
        return runCatching {
            val now = System.currentTimeMillis()
            val begin = if (usageCursorMs == 0L) now - FOREGROUND_INITIAL_LOOKBACK_MS else usageCursorMs - 1_000L
            val usage = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
            CameraStateMonitor.latestResumed(usage, begin, now + 1)?.let { (pkg, timestamp) ->
                lastForegroundPackage = pkg
                usageCursorMs = maxOf(usageCursorMs, timestamp)
            }
            if (usageCursorMs == 0L) usageCursorMs = now
            lastForegroundPackage == FACTORY_CAMERA_PACKAGE
        }.onFailure { note("前台相机检测失败：${it.javaClass.simpleName}: ${it.message}") }.getOrDefault(true)
    }

    private fun usageAllowed(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun permissionsReady(): Boolean {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
        if (!Settings.canDrawOverlays(this) || surface == null) return false
        return usageAllowed()
    }

    private fun configuredCameraIndex(): Int = getSharedPreferences("local-mirror", MODE_PRIVATE)
        .getInt("index", 2).takeIf { it in 1..4 } ?: 2

    /** True when either an interactive preview supplies frames or our hidden AVM surface is open. */
    private fun ensureFrameSource(): Boolean {
        val shared = AvmFrameShare.activeIndex(configuredCameraIndex())
        if (shared != null) {
            if (probe.isOpen) probe.close("切换到共享预览画面")
            noteSource("共享电子后视镜画面（通道 $shared）")
            return true
        }
        if (probe.isOpen) return true
        val target = surface ?: return false
        val opened = runCatching {
            probe.discover(setOf("pano_h", "pano_l")) && probe.open(configuredCameraIndex(), target)
        }.onFailure { note("AVM 打开异常：${it.javaClass.simpleName}: ${it.message}") }.getOrDefault(false)
        if (opened) noteSource("后台隐藏画面")
        return opened
    }

    private fun noteSource(source: String) {
        if (source == activeFrameSource) return
        activeFrameSource = source
        note("录像画面源切换：$source")
    }

    private fun startCapture(targetMode: VehicleRecorderMode) {
        if (recorder?.active == true && mode == targetMode) {
            ensureFrameSource()
            return
        }
        stopCapture("切换录像模式")
        if (!ensureFrameSource()) {
            note("AVM 画面源尚未就绪，将稍后重试")
            return
        }
        val quota = VehicleRecorderPreferences.quotaMb(this).toLong() * 1024 * 1024
        recorder = LocalVideoRecorder(LocalVideoStore(File(filesDir, "local-videos"), quota))
        val started = runCatching { recorder?.start() }.onFailure {
            note("编码器启动失败：${it.javaClass.simpleName}: ${it.message}")
        }.isSuccess
        if (!started) { probe.close("编码器启动失败"); recorder?.abort(); recorder = null; return }
        captureSession++
        mode = targetMode
        segmentStarted = SystemClock.elapsedRealtime()
        lastSurfaceFrameAt = segmentStarted
        if (targetMode == VehicleRecorderMode.PARKING_SENTRY) sentryStarted = segmentStarted
        acquireWakeLock()
        configureImpactSensor(targetMode == VehicleRecorderMode.PARKING_SENTRY)
        frameJob?.cancel()
        frameJob = scope.launch { frameLoop() }
        createNotification(if (targetMode == VehicleRecorderMode.DASHCAM) "行车记录仪正在录像" else "驻车哨兵正在录像")
        note("录像开始：mode=$targetMode; segment=${VehicleRecorderPreferences.segmentMinutes(this)}min; quota=${VehicleRecorderPreferences.quotaMb(this)}MB")
    }

    private suspend fun frameLoop() {
        while (scope.isActive && mode != VehicleRecorderMode.OFF && recorder?.active == true) {
            val now = SystemClock.elapsedRealtime()
            refreshFrameSource(now)
            submitFrame()
            if (now - segmentStarted >= SURFACE_START_GRACE_MS && now - lastSurfaceFrameAt >= SURFACE_STALE_MS) {
                note("录像画面暂时中断，保持编码器并等待 AVM/电子后视镜恢复")
                lastSurfaceFrameAt = now
            }
            delay(FRAME_INTERVAL_MS)
        }
    }

    private fun refreshFrameSource(now: Long) {
        val shared = AvmFrameShare.activeIndex(configuredCameraIndex())
        if (shared != null) {
            if (probe.isOpen) probe.close("电子后视镜共享画面接管")
            noteSource("共享电子后视镜画面（通道 $shared）")
            return
        }
        if (probe.isOpen || now - lastSourceAttemptAt < SOURCE_RETRY_MS) return
        lastSourceAttemptAt = now
        if (!ensureFrameSource()) noteSource("等待 AVM 画面恢复")
    }

    private suspend fun submitFrame() {
        if (!frameBusy.compareAndSet(false, true)) return
        val current = recorder
        val session = captureSession
        if (current == null || mode == VehicleRecorderMode.OFF) {
            frameBusy.set(false)
            return
        }
        val bitmap = captureBitmap()
        if (bitmap == null) {
            frameBusy.set(false)
            return
        }
        encoder.execute { encodeFrame(current, session, bitmap) }
    }

    private suspend fun captureBitmap() = withContext(Dispatchers.Main.immediate) {
        val preferred = configuredCameraIndex()
        val sharedIndex = AvmFrameShare.activeIndex(preferred)
        val bitmap = sharedIndex?.let {
            AvmFrameShare.bitmap(it, LocalVideoRecorder.WIDTH, LocalVideoRecorder.HEIGHT)
        } ?: runCatching {
            texture?.takeIf { probe.isOpen }?.getBitmap(LocalVideoRecorder.WIDTH, LocalVideoRecorder.HEIGHT)
        }.getOrNull()
        if (bitmap != null) lastSurfaceFrameAt = SystemClock.elapsedRealtime()
        bitmap
    }

    private fun encodeFrame(current: LocalVideoRecorder, session: Int, bitmap: android.graphics.Bitmap) {
        try {
            if (!captureIsCurrent(current, session)) return
            current.frame(bitmap)
            rotateSegmentIfNeeded(current, session)
        } catch (error: IllegalStateException) {
            handleEncodingFailure(error)
        } catch (error: IllegalArgumentException) {
            handleEncodingFailure(error)
        } catch (error: SecurityException) {
            handleEncodingFailure(error)
        } finally {
            bitmap.recycle()
            frameBusy.set(false)
        }
    }

    private fun rotateSegmentIfNeeded(current: LocalVideoRecorder, session: Int) {
        val segmentMs = VehicleRecorderPreferences.segmentMinutes(this) * 60_000L
        if (SystemClock.elapsedRealtime() - segmentStarted < segmentMs) return
        val file = current.finish()
        note("分段已保存：${file?.name ?: "empty"}")
        if (!captureIsCurrent(current, session)) return
        current.start()
        segmentStarted = SystemClock.elapsedRealtime()
    }

    private fun captureIsCurrent(current: LocalVideoRecorder, session: Int): Boolean =
        recorder === current && captureSession == session && mode != VehicleRecorderMode.OFF

    private fun handleEncodingFailure(error: RuntimeException) {
        note("录像编码失败：${error.javaClass.simpleName}: ${error.message}")
        scope.launch { stopCapture("录像编码失败") }
    }

    private fun inspectSentryEvents(sample: RecorderVehicleState) {
        if (!prefs.getBoolean(VehicleRecorderPreferences.KEY_DOOR_EVENT, true)) return
        val doors = sample.doors
        if (doorOpened(previousDoors, doors)) protectEvent("驻车开门事件")
        previousDoors = doors.takeIf(::validDoorStates)
    }

    private fun doorOpened(old: List<Int?>?, current: List<Int?>): Boolean {
        if (old == null || !validDoorStates(current)) return false
        return current.indices.any { old[it] == 0 && current[it] == 1 }
    }

    private fun validDoorStates(states: List<Int?>): Boolean = states.all { it in 0..1 }

    private fun protectEvent(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEventAt < EVENT_GUARD_MS || mode == VehicleRecorderMode.OFF) return
        val current = recorder ?: return
        val session = captureSession
        lastEventAt = now
        encoder.execute {
            runCatching {
                if (recorder !== current || captureSession != session || mode != VehicleRecorderMode.PARKING_SENTRY) {
                    note("忽略过期哨兵事件：$reason")
                    return@execute
                }
                val quota = VehicleRecorderPreferences.quotaMb(this).toLong() * 1024 * 1024
                val store = LocalVideoStore(File(filesDir, "local-videos"), quota)
                store.files().lastOrNull()?.let { store.protect(it, reason) }
                val eventFile = current.finish(reason)
                if (recorder === current && captureSession == session && mode == VehicleRecorderMode.PARKING_SENTRY) {
                    current.start()
                    segmentStarted = SystemClock.elapsedRealtime()
                }
                note("哨兵事件已保护：$reason; saved=${eventFile?.name}")
            }.onFailure { note("哨兵事件保护失败：${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    private fun configureImpactSensor(enable: Boolean) {
        sensorManager?.unregisterListener(this)
        sensorReady = false
        sensorSamples = 0
        gravity.fill(0f)
        if (!enable || !prefs.getBoolean(VehicleRecorderPreferences.KEY_IMPACT_EVENT, true)) return
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) note("驻车碰撞检测不可用：车机没有加速度传感器")
        else {
            sensorReady = sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) == true
            note("驻车碰撞检测：registered=$sensorReady; sensor=${sensor.name}")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!sensorReady || mode != VehicleRecorderMode.PARKING_SENTRY) return
        val alpha = 0.8f
        var linearSquared = 0f
        for (i in 0..2) {
            gravity[i] = alpha * gravity[i] + (1 - alpha) * event.values[i]
            val linear = event.values[i] - gravity[i]
            linearSquared += linear * linear
        }
        sensorSamples++
        if (sensorSamples < SENSOR_WARMUP_SAMPLES) return
        val acceleration = sqrt(linearSquared)
        val threshold = prefs.getInt(VehicleRecorderPreferences.KEY_IMPACT_THRESHOLD, 25).coerceIn(15, 60) / 10f
        if (acceleration >= threshold) protectEvent("驻车震动事件 ${"%.1f".format(acceleration)}m/s²")
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private suspend fun readVehicleState(): RecorderVehicleState {
        var values = withTimeoutOrNull(READ_TIMEOUT_MS) { helper.readBatch(READ_ITEMS) }
        if (values == null || values.size != READ_ITEMS.size) {
            withTimeoutOrNull(10_000) { helperBootstrap.ensureRunning() }
            values = withTimeoutOrNull(READ_TIMEOUT_MS) { helper.readBatch(READ_ITEMS) }
        }
        fun raw(index: Int): Int? = values?.getOrNull(index)?.let { (status, value) -> value.takeIf { status == 0 } }
        return RecorderVehicleState(
            power = raw(0), gear = raw(1), speed = raw(2)?.let(VehicleSafetyProbe::decodeSpeed), lock = raw(3),
            doors = listOf(raw(4), raw(5), raw(6), raw(7)),
        )
    }

    private fun stopCapture(reason: String) {
        if (mode == VehicleRecorderMode.OFF && recorder == null) return
        val previous = mode
        mode = VehicleRecorderMode.OFF
        captureSession++
        frameJob?.cancel(); frameJob = null
        sensorManager?.unregisterListener(this); sensorReady = false
        previousDoors = null
        activeFrameSource = ""
        probe.close(reason)
        val finishing = recorder
        recorder = null
        encoder.execute {
            val result = runCatching { finishing?.finish() }
            note(result.fold({ "录像停止：mode=$previous; reason=$reason; saved=${it?.name}" },
                { "录像收尾失败：${it.javaClass.simpleName}: ${it.message}; reason=$reason" }))
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        createNotification("等待车辆状态")
    }

    private fun acquireWakeLock() {
        val timeout = if (mode == VehicleRecorderMode.PARKING_SENTRY) {
            VehicleRecorderPreferences.sentryHours(this) * 60 * 60_000L + 60_000L
        } else WAKE_LOCK_MAX_MS
        wakeLock?.let {
            if (it.isHeld) it.release()
            it.acquire(timeout)
        }
    }

    private fun createNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "行车记录仪与驻车哨兵", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, VehicleRecorderService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("BYDMateCN 车辆录像")
            .setContentText(text).setOngoing(true).addAction(0, "停止并关闭", stop).build())
    }

    private fun note(message: String) {
        LocalFileLog.get(this).record("vehicle-recorder", message)
        prefs.edit().putString(VehicleRecorderPreferences.KEY_LAST_STATUS, message)
            .putLong(VehicleRecorderPreferences.KEY_LAST_STATUS_AT, System.currentTimeMillis()).apply()
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        stopCapture("服务停止")
        scope.cancel()
        sensorManager?.unregisterListener(this)
        surface?.release(); surface = null
        texture?.let { runCatching { windowManager.removeView(it) } }; texture = null
        probe.close("服务销毁")
        recorder?.abort(); recorder = null
        encoder.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(true)
        super.onDestroy()
    }

    private data class RecorderVehicleState(
        val power: Int?, val gear: Int?, val speed: Float?, val lock: Int?, val doors: List<Int?>,
    )

    companion object {
        const val START = "com.bydmatecn.app.RECORDER_START"
        const val STOP = "com.bydmatecn.app.RECORDER_STOP"
        private const val CHANNEL = "vehicle_recorder"
        private const val NOTIFICATION_ID = 5814
        private const val CAPTURE_WIDTH = 640
        private const val CAPTURE_HEIGHT = 360
        private const val STATE_POLL_MS = 1_000L
        private const val FOREGROUND_INITIAL_LOOKBACK_MS = 10 * 60_000L
        private const val FACTORY_CAMERA_PACKAGE = "com.byd.avc"
        private const val FRAME_INTERVAL_MS = 200L
        private const val READ_TIMEOUT_MS = 2_000L
        private const val EVENT_GUARD_MS = 10_000L
        private const val SENSOR_WARMUP_SAMPLES = 10
        private const val SURFACE_START_GRACE_MS = 8_000L
        private const val SURFACE_STALE_MS = 5_000L
        private const val SOURCE_RETRY_MS = 1_000L
        private const val WAKE_LOCK_MAX_MS = 12 * 60 * 60_000L
        private val READ_ITEMS = listOf(
            BatchReadItem(5, 1023, 315_621_408),
            BatchReadItem(5, 1011, 555_745_336),
            BatchReadItem(7, 1013, -1_807_745_016),
            BatchReadItem(5, 1032, 1_081_081_864),
            BatchReadItem(5, 1001, 692_060_168),
            BatchReadItem(5, 1001, 692_060_170),
            BatchReadItem(5, 1001, 692_060_172),
            BatchReadItem(5, 1001, 692_060_174),
        )

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VehicleRecorderService::class.java).setAction(START))
        }
        fun restoreIfEnabled(context: Context) { if (VehicleRecorderPreferences.enabled(context)) start(context) }
        fun stop(context: Context) { context.stopService(Intent(context, VehicleRecorderService::class.java)) }
    }
}
