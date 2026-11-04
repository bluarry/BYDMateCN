package com.bydmatecn.app.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import com.bydmatecn.app.camera.AvmConsumerPriority
import com.bydmatecn.app.camera.AvmFrameShare
import com.bydmatecn.app.camera.AvmSurfaceBinding
import com.bydmatecn.app.camera.AvmSourceConfig
import com.bydmatecn.app.camera.SharedAvmSession
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
 * Dashcam and parking-sentry encoder backed by the process-wide shared AVM producer.
 * Interactive previews and the recorder attach independent surfaces to that producer. On firmware
 * that rejects a combined set, [AvmFrameShare] remains the compatibility fallback.
 */
@AndroidEntryPoint
@Suppress("TooManyFunctions") // Android callbacks plus one recorder state machine stay in one lifecycle owner.
class VehicleRecorderService : Service(), TextureView.SurfaceTextureListener, SensorEventListener {
    @Inject lateinit var helper: HelperClient
    @Inject lateinit var helperBootstrap: HelperBootstrap
    @Inject lateinit var avmSession: SharedAvmSession

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val encoder = Executors.newSingleThreadExecutor()
    private val frameBusy = AtomicBoolean(false)
    private val prefs by lazy { VehicleRecorderPreferences.prefs(this) }
    private lateinit var windowManager: WindowManager
    private var texture: TextureView? = null
    private var surface: Surface? = null
    private var directFrameSource: AvmImageReaderFrameSource? = null
    private var capturePlan = RecorderCapturePlan()
    private var directSourceConfig: AvmSourceConfig? = null
    @Volatile private var directFrameSourceFailures = 0
    private var directFrameSourceRetryAt = 0L
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
    @Volatile private var lastSurfaceFrameAt = 0L
    @Volatile private var lastTextureFrameAt = 0L
    private var lastStaleRecoveryAt = 0L
    private var firstFrameWaitingSince = 0L
    private var deliveredFrameCount = 0L
    private var sentryLimitReached = false
    private var activeFrameSource = ""
    private var lastSourceAttemptAt = 0L
    private val eventProtection = RecorderEventProtection()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bydmatecn:vehicle_recorder")
            .apply { setReferenceCounted(false) }
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        capturePlan = VehicleRecorderPreferences.capturePlan(this)
        createNotification("等待车辆状态")
        note(
            "录像画面方案：direction=${capturePlan.direction}; surface=${capturePlan.surfaceIndex}; " +
                "strip=${capturePlan.direction.stripIndex}; flip=${capturePlan.horizontalFlip}",
        )
        createDirectFrameSource()
        if (directFrameSource == null) createCaptureTexture()
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RELOAD_CAPTURE_PLAN -> reloadCapturePlan()
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
        if (directFrameSource == null && texture == null && Settings.canDrawOverlays(this)) createCaptureTexture()
        if (monitorJob?.isActive != true) monitorJob = scope.launch { monitorLoop() }
        return START_STICKY
    }

    private fun createDirectFrameSource() {
        if (directFrameSource != null || SystemClock.elapsedRealtime() < directFrameSourceRetryAt) return
        val sourceConfig = runCatching {
            avmSession.discover()
            avmSession.preferredSourceConfig()
        }.onFailure {
            note("AVM 默认画面规格探测失败：${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(DEFAULT_RECORDER_SOURCE_CONFIG)
        val source = AvmImageReaderFrameSource(
            sourceWidth = sourceConfig.width,
            sourceHeight = sourceConfig.height,
            outputWidth = LocalVideoRecorder.WIDTH,
            outputHeight = LocalVideoRecorder.HEIGHT,
            targetFps = LocalVideoRecorder.FPS,
            stripIndex = capturePlan.direction.stripIndex,
            horizontalFlip = capturePlan.horizontalFlip,
            note = ::note,
            onFrame = {
                lastSurfaceFrameAt = it
                firstFrameWaitingSince = 0L
                directFrameSourceFailures = 0
            },
        )
        if (source.start()) {
            directSourceConfig = sourceConfig
            directFrameSource = source
            directFrameSourceRetryAt = 0L
            note("录像优先使用 ImageReader 直接取帧：$sourceConfig")
        } else {
            directFrameSourceFailures++
            scheduleDirectFrameRetry("ImageReader 创建失败")
        }
    }

    private fun scheduleDirectFrameRetry(reason: String) {
        val retiring = directFrameSource
        val retiringSurface = retiring?.surface
        if (retiringSurface != null) {
            val retiringBinding = AvmSurfaceBinding(configuredCameraIndex(), retiringSurface)
            val wasActive = avmSession.isBindingActive(RECORDER_OWNER, retiringBinding)
            val fallback = surface
            val fallbackReady = fallback != null && runCatching {
                avmSession.attach(
                    RECORDER_OWNER,
                    listOf(AvmSurfaceBinding(configuredCameraIndex(), fallback)),
                    AvmConsumerPriority.BACKGROUND,
                )
            }.getOrDefault(false)
            if (!fallbackReady) {
                // Also clear a deferred request for this ImageReader: another owner attaching later
                // must never revive a Surface after its ImageReader has been closed.
                avmSession.detach(RECORDER_OWNER, "ImageReader 退避前清理失效 Surface")
            } else if (wasActive) {
                note("ImageReader 退避前已无缝切换到隐藏 TextureView Surface")
            }
        }
        retiring?.stopAndAwait(IMAGE_READER_RELEASE_WAIT_MS)
        firstFrameWaitingSince = 0L
        directFrameSource = null
        directSourceConfig = null
        val delay = if (directFrameSourceFailures >= DIRECT_SOURCE_MAX_FAILURES) {
            DIRECT_SOURCE_BACKOFF_MS
        } else {
            DIRECT_SOURCE_RETRY_MS
        }
        directFrameSourceRetryAt = SystemClock.elapsedRealtime() + delay
        note(
            "ImageReader 暂时不可用：$reason；failure=$directFrameSourceFailures/" +
                "$DIRECT_SOURCE_MAX_FAILURES；${delay}ms 后重试",
        )
        if (directFrameSourceFailures >= DIRECT_SOURCE_MAX_FAILURES) createCaptureTexture()
    }

    private fun recreateDirectFrameSource(reason: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < directFrameSourceRetryAt) {
            noteSource("ImageReader 重试退避中（剩余 ${directFrameSourceRetryAt - now}ms）")
            return false
        }
        val previous = directFrameSource ?: return false
        val previousConfig = directSourceConfig
        directFrameSource = null
        directSourceConfig = null
        directFrameSourceRetryAt = 0L
        note("无缝重建 ImageReader：$reason；保留旧 Surface 直到新 Surface 完成绑定")
        val failuresBeforeCreate = directFrameSourceFailures
        createDirectFrameSource()
        val replacement = directFrameSource
        val replacementBinding = replacement?.surface?.let {
            AvmSurfaceBinding(configuredCameraIndex(), it)
        }
        if (replacement != null && replacementBinding != null && ensureFrameSource() &&
            avmSession.isBindingActive(RECORDER_OWNER, replacementBinding)
        ) {
            previous.stopAndAwait(IMAGE_READER_RELEASE_WAIT_MS)
            firstFrameWaitingSince = SystemClock.elapsedRealtime()
            note("ImageReader 已无缝替换，等待首帧确认；${avmSession.diagnosticSnapshot()}")
            return true
        }

        replacement?.stopAndAwait(IMAGE_READER_RELEASE_WAIT_MS)
        firstFrameWaitingSince = 0L
        directFrameSource = previous
        directSourceConfig = previousConfig
        previous.surface?.let { oldSurface ->
            runCatching {
                avmSession.attach(
                    RECORDER_OWNER,
                    listOf(AvmSurfaceBinding(configuredCameraIndex(), oldSurface)),
                    AvmConsumerPriority.BACKGROUND,
                    previousConfig,
                )
            }
        }
        if (directFrameSourceFailures == failuresBeforeCreate) directFrameSourceFailures++
        val delay = if (directFrameSourceFailures >= DIRECT_SOURCE_MAX_FAILURES) {
            DIRECT_SOURCE_BACKOFF_MS
        } else {
            DIRECT_SOURCE_RETRY_MS
        }
        directFrameSourceRetryAt = SystemClock.elapsedRealtime() + delay
        note(
            "ImageReader 替换失败，继续使用旧画面：$reason；failure=$directFrameSourceFailures/" +
                "$DIRECT_SOURCE_MAX_FAILURES；${delay}ms 后重试；${avmSession.diagnosticSnapshot()}",
        )
        if (directFrameSourceFailures >= DIRECT_SOURCE_MAX_FAILURES) createCaptureTexture()
        return false
    }

    private fun reloadCapturePlan() {
        val previous = capturePlan
        stopCapture("重新加载录像画面方案")
        avmSession.detach(RECORDER_OWNER, "重新加载录像画面方案")
        directFrameSource?.stopAndAwait(IMAGE_READER_RELEASE_WAIT_MS)
        firstFrameWaitingSince = 0L
        directFrameSource = null
        directSourceConfig = null
        directFrameSourceFailures = 0
        directFrameSourceRetryAt = 0L
        capturePlan = VehicleRecorderPreferences.capturePlan(this)
        createDirectFrameSource()
        if (directFrameSource == null) createCaptureTexture()
        note("录像画面方案已重载：$previous -> $capturePlan")
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
        val now = SystemClock.elapsedRealtime()
        lastTextureFrameAt = now
        lastSurfaceFrameAt = now
        firstFrameWaitingSince = 0L
    }
    override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
        val destroyed = surface
        stopCapture("后台 Surface 销毁")
        if (destroyed != null && avmSession.isBindingActive(
                RECORDER_OWNER,
                AvmSurfaceBinding(configuredCameraIndex(), destroyed),
            )
        ) {
            avmSession.detach(RECORDER_OWNER, "后台 Surface 销毁")
        }
        destroyed?.release()
        surface = null
        lastTextureFrameAt = 0L
        firstFrameWaitingSince = 0L
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
        if (mode != VehicleRecorderMode.OFF || recorder != null) {
            stopCapture(reason)
            return
        }
        if (avmSession.hasRegistration(RECORDER_OWNER)) {
            activeFrameSource = ""
            firstFrameWaitingSince = 0L
            avmSession.detach(RECORDER_OWNER, reason)
            note("取消等待录像首帧：$reason")
        }
    }

    private fun updateParkingSentry(sample: RecorderVehicleState) {
        inspectSentryEvents(sample)
        val hours = VehicleRecorderPreferences.sentryHours(this)
        val limit = hours * 60 * 60_000L
        if (sentryStarted <= 0 || SystemClock.elapsedRealtime() - sentryStarted < limit) return
        sentryLimitReached = true
        stopCapture("驻车哨兵达到最长 $hours 小时")
    }

    private fun permissionsReady(): Boolean {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
        if (directFrameSource?.surface == null && (!Settings.canDrawOverlays(this) || surface == null)) return false
        return true
    }

    private fun configuredCameraIndex(): Int = capturePlan.surfaceIndex

    /** Registers the recorder Surface on the same AVM handle used by the electronic mirror. */
    private fun ensureFrameSource(): Boolean {
        val target = directFrameSource?.surface ?: surface ?: return false
        val binding = AvmSurfaceBinding(configuredCameraIndex(), target)
        if (avmSession.isBindingActive(RECORDER_OWNER, binding)) {
            markFirstFrameWaitIfNeeded(target)
            return true
        }
        val opened = runCatching {
            avmSession.attach(
                RECORDER_OWNER,
                listOf(binding),
                AvmConsumerPriority.BACKGROUND,
                directSourceConfig.takeIf { directFrameSource?.surface === target },
            )
        }.onFailure { note("AVM 共享订阅异常：${it.javaClass.simpleName}: ${it.message}") }.getOrDefault(false)
        if (opened) markFirstFrameWaitIfNeeded(target)
        val shared = AvmFrameShare.activeIndex(configuredCameraIndex())
        when {
            opened && directFrameSource?.surface === target -> noteSource(
                "ImageReader 已绑定，等待首帧（${capturePlan.direction.displayName}/通道${capturePlan.surfaceIndex}）",
            )
            opened -> noteSource("后台共享 AVM Surface（${capturePlan.direction.displayName}/通道${capturePlan.surfaceIndex}）")
            shared != null -> noteSource("电子后视镜帧降级（通道 $shared）")
        }
        return opened || shared != null
    }

    private fun markFirstFrameWaitIfNeeded(target: Surface) {
        val alreadyHasFrame = if (directFrameSource?.surface === target) {
            directFrameSource?.lastFrameAt?.let { it > 0L } == true
        } else {
            lastTextureFrameAt > 0L
        }
        if (!alreadyHasFrame && firstFrameWaitingSince <= 0L) {
            firstFrameWaitingSince = SystemClock.elapsedRealtime()
        }
    }

    private fun firstFrameWaitTimedOut(now: Long): Boolean =
        firstFrameWaitingSince > 0L && now - firstFrameWaitingSince >= SURFACE_START_GRACE_MS

    private fun hasFreshFrameSource(now: Long): Boolean {
        val directAt = directFrameSource?.lastFrameAt ?: 0L
        if (directAt > 0L && now - directAt <= SURFACE_STALE_MS) return true
        if (AvmFrameShare.freshIndex(configuredCameraIndex()) != null) return true
        val hiddenSurface = surface ?: return false
        val hiddenBinding = AvmSurfaceBinding(configuredCameraIndex(), hiddenSurface)
        return avmSession.isBindingActive(RECORDER_OWNER, hiddenBinding) &&
            lastTextureFrameAt > 0L && now - lastTextureFrameAt <= SURFACE_STALE_MS
    }

    private fun noteSource(source: String) {
        if (source == activeFrameSource) return
        activeFrameSource = source
        note("录像画面源切换：$source")
    }

    private fun startCapture(targetMode: VehicleRecorderMode) {
        if (recorder?.active == true) {
            ensureFrameSource()
            if (mode != targetMode) switchCaptureMode(targetMode)
            return
        }
        stopCapture("清理旧录像状态")
        if (!ensureFrameSource()) {
            note("AVM 画面源尚未就绪，将稍后重试")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!hasFreshFrameSource(now)) {
            refreshFrameSource(now)
            return
        }
        val quota = VehicleRecorderPreferences.quotaMb(this).toLong() * 1024 * 1024
        recorder = LocalVideoRecorder(LocalVideoStore(File(filesDir, "local-videos"), quota))
        val started = runCatching { recorder?.start() }.onFailure {
            note("编码器启动失败：${it.javaClass.simpleName}: ${it.message}")
        }.isSuccess
        if (!started) { avmSession.detach(RECORDER_OWNER, "编码器启动失败"); recorder?.abort(); recorder = null; return }
        captureSession++
        mode = targetMode
        segmentStarted = SystemClock.elapsedRealtime()
        // Do not fabricate frame freshness here. AVM binding success only means that the vendor
        // accepted the Surface; the first ImageReader/TextureView callback proves real pixels.
        lastStaleRecoveryAt = 0L
        deliveredFrameCount = 0L
        if (targetMode == VehicleRecorderMode.PARKING_SENTRY) sentryStarted = segmentStarted
        acquireWakeLock()
        configureImpactSensor(targetMode == VehicleRecorderMode.PARKING_SENTRY)
        frameJob?.cancel()
        frameJob = scope.launch { frameLoop() }
        createNotification(if (targetMode == VehicleRecorderMode.DASHCAM) "行车记录仪正在录像" else "驻车哨兵正在录像")
        note("录像开始：mode=$targetMode; segment=${VehicleRecorderPreferences.segmentMinutes(this)}min; quota=${VehicleRecorderPreferences.quotaMb(this)}MB")
    }

    private fun switchCaptureMode(targetMode: VehicleRecorderMode) {
        val previous = mode
        mode = targetMode
        if (targetMode == VehicleRecorderMode.PARKING_SENTRY) {
            sentryStarted = SystemClock.elapsedRealtime()
            previousDoors = null
        } else {
            sentryLimitReached = false
            previousDoors = null
        }
        configureImpactSensor(targetMode == VehicleRecorderMode.PARKING_SENTRY)
        acquireWakeLock()
        createNotification(if (targetMode == VehicleRecorderMode.DASHCAM) "行车记录仪正在录像" else "驻车哨兵正在录像")
        note("录像模式无缝切换：$previous -> $targetMode; AVM 与编码器保持运行")
    }

    private suspend fun frameLoop() {
        while (scope.isActive && mode != VehicleRecorderMode.OFF && recorder?.active == true) {
            val now = SystemClock.elapsedRealtime()
            refreshFrameSource(now)
            submitFrame()
            if (now - segmentStarted >= SURFACE_START_GRACE_MS && now - lastSurfaceFrameAt >= SURFACE_STALE_MS) {
                recoverStaleFrameSource(now)
            }
            delay(FRAME_INTERVAL_MS)
        }
    }

    private fun refreshFrameSource(now: Long) {
        if (directFrameSource == null && now >= directFrameSourceRetryAt) {
            createDirectFrameSource()
            directFrameSource?.surface?.let { newSurface ->
                val binding = AvmSurfaceBinding(configuredCameraIndex(), newSurface)
                if (!ensureFrameSource() || !avmSession.isBindingActive(RECORDER_OWNER, binding)) {
                    directFrameSourceFailures++
                    scheduleDirectFrameRetry("ImageReader Surface 热切换失败；${avmSession.diagnosticSnapshot()}")
                } else {
                    note("ImageReader Surface 已热切换；${avmSession.diagnosticSnapshot()}")
                }
            }
        }
        val direct = directFrameSource
        val directBinding = direct?.surface?.let {
            AvmSurfaceBinding(configuredCameraIndex(), it)
        }
        if (direct != null && directBinding != null &&
            avmSession.isBindingActive(RECORDER_OWNER, directBinding)
        ) {
            val frameAt = direct.lastFrameAt
            when {
                frameAt <= 0L && firstFrameWaitTimedOut(now) -> {
                    val waited = now - firstFrameWaitingSince
                    noteSource("ImageReader 等待首帧超时（${waited}ms），准备重建")
                    recoverStaleFrameSource(now, "等待首帧 ${waited}ms")
                }
                frameAt <= 0L -> noteSource("ImageReader 已绑定，等待首帧")
                now - frameAt <= SURFACE_STALE_MS -> noteSource("ImageReader 共享 AVM Surface")
                else -> noteSource("ImageReader Surface 已绑定但画面过期（${now - frameAt}ms）")
            }
            return
        }
        val textureBinding = surface?.let { AvmSurfaceBinding(configuredCameraIndex(), it) }
        if (textureBinding != null && avmSession.isBindingActive(RECORDER_OWNER, textureBinding)) {
            when {
                lastTextureFrameAt <= 0L && firstFrameWaitTimedOut(now) -> {
                    noteSource("隐藏 TextureView 等待首帧超时，重建后台 AVM 订阅")
                    recoverStaleFrameSource(now, "隐藏 TextureView 等待首帧超时")
                }
                lastTextureFrameAt <= 0L -> noteSource("隐藏 TextureView 已绑定，等待首帧")
                now - lastTextureFrameAt <= SURFACE_STALE_MS -> noteSource("隐藏 TextureView 共享 AVM Surface")
                else -> noteSource("隐藏 TextureView 已绑定但画面过期（${now - lastTextureFrameAt}ms）")
            }
            return
        }
        if (avmSession.isActive(RECORDER_OWNER)) {
            noteSource("录像 AVM 请求已登记，但目标 Surface 尚未生效")
            return
        }
        val shared = AvmFrameShare.activeIndex(configuredCameraIndex())
        if (shared != null) {
            noteSource("电子后视镜帧降级（通道 $shared）")
            return
        }
        if (now - lastSourceAttemptAt < SOURCE_RETRY_MS) return
        lastSourceAttemptAt = now
        if (!ensureFrameSource()) noteSource("等待 AVM 画面恢复")
    }

    /**
     * Keep a delivered-frame timestamp and restart a stale capture subscription. A successful
     * getBitmap is not proof of a new frame because
     * TextureView returns its last buffer indefinitely, so freshness comes only from the real
     * SurfaceTexture callback or AvmFrameShare's frame callback.
     */
    private fun recoverStaleFrameSource(now: Long, reason: String? = null) {
        if (now - lastStaleRecoveryAt < STALE_RECOVERY_COOLDOWN_MS) return
        lastStaleRecoveryAt = now
        val shared = AvmFrameShare.freshIndex(configuredCameraIndex())
        if (directFrameSource != null) {
            val age = directFrameSource?.lastFrameAt?.takeIf { it > 0L }?.let { now - it }
            val detail = reason ?: "ImageReader ${age ?: "尚未"}ms 未收到新帧"
            if (shared != null) {
                note(
                    "录像 ImageReader 过期，但电子后视镜通道 $shared 仍有新鲜帧；" +
                        "仅热替换录像 Surface，不重启共享 AVM producer",
                )
            }
            if (!recreateDirectFrameSource(detail)) {
                if (shared != null) {
                    noteSource("ImageReader 热替换失败，继续使用电子后视镜帧（通道 $shared）")
                } else if (!ensureFrameSource()) {
                    noteSource("等待 ImageReader 重试或隐藏 TextureView Surface")
                }
            }
            return
        }
        if (shared != null) {
            // There is no recorder-owned ImageReader to repair. Keep consuming the mirror frames;
            // detaching/reopening the producer here would unnecessarily blank the interactive view.
            noteSource("电子后视镜帧降级（通道 $shared）")
            return
        }
        note(
            "录像画面超时：lastFrameAge=${now - lastSurfaceFrameAt}ms; " +
                "delivered=$deliveredFrameCount; 重建后台 AVM 订阅",
        )
        avmSession.detach(RECORDER_OWNER, "录像帧超时")
        firstFrameWaitingSince = 0L
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
        directFrameSource?.copyBitmap()?.let { bitmap ->
            lastSurfaceFrameAt = SystemClock.elapsedRealtime()
            return@withContext bitmap
        }
        val preferred = configuredCameraIndex()
        val sharedIndex = AvmFrameShare.activeIndex(preferred)
        val sharedBitmap = sharedIndex?.let {
            AvmFrameShare.bitmap(it, LocalVideoRecorder.WIDTH, LocalVideoRecorder.HEIGHT)
        }
        if (sharedBitmap != null) {
            lastSurfaceFrameAt = SystemClock.elapsedRealtime()
            return@withContext sharedBitmap
        }
        val now = SystemClock.elapsedRealtime()
        val hiddenSurface = surface ?: return@withContext null
        val hiddenBinding = AvmSurfaceBinding(configuredCameraIndex(), hiddenSurface)
        if (!avmSession.isBindingActive(RECORDER_OWNER, hiddenBinding) ||
            lastTextureFrameAt <= 0L || now - lastTextureFrameAt > SURFACE_STALE_MS
        ) {
            return@withContext null
        }
        runCatching {
            texture?.getBitmap(LocalVideoRecorder.WIDTH, LocalVideoRecorder.HEIGHT)
        }.getOrNull()
    }

    private fun encodeFrame(current: LocalVideoRecorder, session: Int, bitmap: android.graphics.Bitmap) {
        try {
            if (!captureIsCurrent(current, session)) return
            current.frame(bitmap)
            deliveredFrameCount++
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
        val postReason = eventProtection.consumePostRoll()
        val file = current.finish(postReason?.let { "$it（事件后）" })
        note("分段已保存：${file?.name ?: "empty"}; postEvent=${postReason ?: "none"}")
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
                val eventFile = current.finish("$reason（事件发生）")
                if (recorder === current && captureSession == session && mode == VehicleRecorderMode.PARKING_SENTRY) {
                    current.start()
                    segmentStarted = SystemClock.elapsedRealtime()
                    eventProtection.armPostRoll(reason)
                }
                note("哨兵事件已保护：$reason; current=${eventFile?.name}; postRoll=armed")
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
        firstFrameWaitingSince = 0L
        avmSession.detach(RECORDER_OWNER, reason)
        val finishing = recorder
        recorder = null
        encoder.execute {
            val postReason = eventProtection.consumePostRoll()
            val result = runCatching { finishing?.finish(postReason?.let { "$it（事件后）" }) }
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
        texture?.let { runCatching { windowManager.removeView(it) } }; texture = null
        avmSession.detach(RECORDER_OWNER, "服务销毁")
        directFrameSource?.stopAndAwait(IMAGE_READER_DESTROY_WAIT_MS)
        directFrameSource = null
        directSourceConfig = null
        surface?.release(); surface = null
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
        const val RELOAD_CAPTURE_PLAN = "com.bydmatecn.app.RECORDER_RELOAD_CAPTURE_PLAN"
        private const val CHANNEL = "vehicle_recorder"
        private const val RECORDER_OWNER = "vehicle-recorder"
        private const val NOTIFICATION_ID = 5814
        private const val CAPTURE_WIDTH = 640
        private const val CAPTURE_HEIGHT = 360
        private val DEFAULT_RECORDER_SOURCE_CONFIG = AvmSourceConfig(1280, 720, 15)
        private const val STATE_POLL_MS = 1_000L
        private const val FRAME_INTERVAL_MS = 200L
        private const val READ_TIMEOUT_MS = 2_000L
        private const val EVENT_GUARD_MS = 10_000L
        private const val SENSOR_WARMUP_SAMPLES = 10
        private const val SURFACE_START_GRACE_MS = 8_000L
        private const val SURFACE_STALE_MS = 5_000L
        private const val STALE_RECOVERY_COOLDOWN_MS = 5_000L
        private const val SOURCE_RETRY_MS = 1_000L
        private const val DIRECT_SOURCE_RETRY_MS = 2_000L
        private const val DIRECT_SOURCE_BACKOFF_MS = 30_000L
        private const val DIRECT_SOURCE_MAX_FAILURES = 3
        private const val IMAGE_READER_RELEASE_WAIT_MS = 150L
        private const val IMAGE_READER_DESTROY_WAIT_MS = 500L
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
        fun reloadCapturePlan(context: Context) {
            if (VehicleRecorderPreferences.enabled(context)) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, VehicleRecorderService::class.java).setAction(RELOAD_CAPTURE_PLAN),
                )
            }
        }
        fun stop(context: Context) { context.stopService(Intent(context, VehicleRecorderService::class.java)) }
    }
}
