@file:Suppress("TooManyFunctions")

package com.bydmatecn.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bydmatecn.app.camera.BlindSpotPositionOverlay
import com.bydmatecn.app.camera.BlindSpotPreferences
import com.bydmatecn.app.camera.blindSpotClusterDisplay
import com.bydmatecn.app.cluster.ClusterEntryPoint
import com.bydmatecn.app.diagnostics.Trace
import com.bydmatecn.app.diagnostics.TraceArea
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val MirrorBackground = Color(0xFF1D1D1F)
private val MirrorPanel = Color(0xFF29292C)
private val MirrorRow = Color(0xFF333336)
private val MirrorBlue = Color(0xFF2879F5)
private val MirrorText = Color(0xFFF4F4F5)
private val MirrorMuted = Color(0xFFAAAAAF)
private val MirrorDisabled = Color(0xFF66666B)

/** Dedicated wide-screen editor for the turn-signal camera feature. */
@Composable
fun BlindSpotToolScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(BlindSpotPreferences.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val clusterEntryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ClusterEntryPoint::class.java,
        )
    }
    var enabled by remember { mutableStateOf(prefs.getBoolean(BlindSpotPreferences.KEY_ENABLED, false)) }
    var thresholdKmh by remember {
        mutableStateOf(prefs.getInt(BlindSpotPreferences.KEY_THRESHOLD_KMH, BlindSpotPreferences.DEFAULT_THRESHOLD_KMH))
    }
    var closeDelayMs by remember {
        mutableStateOf(prefs.getInt(BlindSpotPreferences.KEY_CLOSE_DELAY_MS, BlindSpotPreferences.DEFAULT_CLOSE_DELAY_MS))
    }
    var keepWhileSignalOn by remember {
        mutableStateOf(prefs.getBoolean(BlindSpotPreferences.KEY_KEEP_WHILE_SIGNAL_ON, true))
    }
    var bsdGlow by remember { mutableStateOf(prefs.getBoolean(BlindSpotPreferences.KEY_BSD_GLOW, true)) }
    var rotate90 by remember {
        mutableStateOf(prefs.getBoolean(BlindSpotPreferences.KEY_PIP_ROTATE_90, false))
    }
    var pipWidthPct by remember {
        mutableStateOf(prefs.getInt(BlindSpotPreferences.KEY_PIP_WIDTH_PCT, BlindSpotPreferences.DEFAULT_PIP_WIDTH_PCT))
    }
    var bothOnMain by remember {
        mutableStateOf(prefs.getBoolean(BlindSpotPreferences.KEY_BOTH_ON_MAIN, false))
    }
    var bothOnCluster by remember {
        mutableStateOf(prefs.getBoolean(BlindSpotPreferences.KEY_BOTH_ON_CLUSTER, false))
    }
    val hasClusterDisplay = remember { blindSpotClusterDisplay(context) != null }

    val placingState = remember { mutableStateOf<BlindSpotPositionOverlay.Side?>(null) }
    var placing by placingState
    val positionOverlay = remember {
        BlindSpotPositionOverlay().apply { onHidden = { placingState.value = null } }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) positionOverlay.hide()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            positionOverlay.hide()
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensurePermissions() {
        scope.launch {
            val helperReady = runCatching { clusterEntryPoint.helperBootstrap().ensureRunning() }.getOrDefault(false)
            val helperGranted = helperReady && runCatching {
                clusterEntryPoint.helperClient().grantOverlayPermission()
            }.getOrDefault(false)
            Trace.event(
                TraceArea.CAMERA,
                "overlay-grant",
                "stage" to "mirror-page",
                "daemon" to helperReady,
                "helper" to helperGranted,
                "granted" to AndroidSettings.canDrawOverlays(context),
            )
            if (!AndroidSettings.canDrawOverlays(context)) {
                runCatching {
                    context.startActivity(
                        Intent(
                            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        BlindSpotPreferences.setEnabled(prefs, on)
        if (on) ensurePermissions() else positionOverlay.hide()
    }

    fun setTarget(target: MirrorTarget) {
        bothOnMain = target == MirrorTarget.MAIN
        bothOnCluster = target == MirrorTarget.CLUSTER
        prefs.edit()
            .putBoolean(BlindSpotPreferences.KEY_BOTH_ON_MAIN, bothOnMain)
            .putBoolean(BlindSpotPreferences.KEY_BOTH_ON_CLUSTER, bothOnCluster)
            .apply()
        if (!bothOnMain && placing == BlindSpotPositionOverlay.Side.LEFT) positionOverlay.hide()
        Trace.event(TraceArea.USER, "select", "id" to "blindspot_target", "value" to target.name.lowercase())
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MirrorBackground)) {
        val compact = maxWidth < 900.dp
        Column(Modifier.fillMaxSize()) {
            MirrorHeader(onBack = onBack)
            if (compact) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MirrorGeneralPanel(enabled, ::setEnabled)
                    MirrorTriggerPanel(enabled, keepWhileSignalOn, closeDelayMs, thresholdKmh, { keepWhileSignalOn = it; prefs.edit().putBoolean(BlindSpotPreferences.KEY_KEEP_WHILE_SIGNAL_ON, it).apply() }, { closeDelayMs = it; prefs.edit().putInt(BlindSpotPreferences.KEY_CLOSE_DELAY_MS, it).apply() }, { thresholdKmh = it; prefs.edit().putInt(BlindSpotPreferences.KEY_THRESHOLD_KMH, it).apply() })
                    MirrorDisplayPanel(enabled, hasClusterDisplay, currentTarget(bothOnMain, bothOnCluster), ::setTarget, pipWidthPct, rotate90, bsdGlow, { pipWidthPct = it; prefs.edit().putInt(BlindSpotPreferences.KEY_PIP_WIDTH_PCT, it).apply(); if (placing != null) positionOverlay.refreshSize() }, { rotate90 = it; prefs.edit().putBoolean(BlindSpotPreferences.KEY_PIP_ROTATE_90, it).apply(); if (placing != null) positionOverlay.refreshSize() }, { bsdGlow = it; prefs.edit().putBoolean(BlindSpotPreferences.KEY_BSD_GLOW, it).apply() }, placing, positionOverlay, { placing = it }, context, ::ensurePermissions, bothOnMain)
                }
            } else {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    MirrorColumn(Modifier.weight(1f)) {
                        MirrorGeneralPanel(enabled, ::setEnabled)
                    }
                    MirrorColumn(Modifier.weight(1f)) {
                        MirrorTriggerPanel(enabled, keepWhileSignalOn, closeDelayMs, thresholdKmh, { keepWhileSignalOn = it; prefs.edit().putBoolean(BlindSpotPreferences.KEY_KEEP_WHILE_SIGNAL_ON, it).apply() }, { closeDelayMs = it; prefs.edit().putInt(BlindSpotPreferences.KEY_CLOSE_DELAY_MS, it).apply() }, { thresholdKmh = it; prefs.edit().putInt(BlindSpotPreferences.KEY_THRESHOLD_KMH, it).apply() })
                    }
                    MirrorColumn(Modifier.weight(1f)) {
                        MirrorDisplayPanel(enabled, hasClusterDisplay, currentTarget(bothOnMain, bothOnCluster), ::setTarget, pipWidthPct, rotate90, bsdGlow, { pipWidthPct = it; prefs.edit().putInt(BlindSpotPreferences.KEY_PIP_WIDTH_PCT, it).apply(); if (placing != null) positionOverlay.refreshSize() }, { rotate90 = it; prefs.edit().putBoolean(BlindSpotPreferences.KEY_PIP_ROTATE_90, it).apply(); if (placing != null) positionOverlay.refreshSize() }, { bsdGlow = it; prefs.edit().putBoolean(BlindSpotPreferences.KEY_BSD_GLOW, it).apply() }, placing, positionOverlay, { placing = it }, context, ::ensurePermissions, bothOnMain)
                    }
                }
            }
        }
    }
}

private enum class MirrorTarget { AUTO, MAIN, CLUSTER }

private fun currentTarget(main: Boolean, cluster: Boolean): MirrorTarget = when {
    main -> MirrorTarget.MAIN
    cluster -> MirrorTarget.CLUSTER
    else -> MirrorTarget.AUTO
}

@Composable
private fun MirrorHeader(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(Color(0xFF202022)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = MirrorText)
        }
        Text("电子后视镜", color = MirrorText, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MirrorColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(MirrorPanel)
            .verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun MirrorGeneralPanel(
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
) {
    MirrorSection("基础设置") {
        MirrorSwitchRow("启用电子后视镜", "转向灯触发对应侧摄像头", enabled, onEnabled)
        MirrorInfoRow("触发方式", "车辆转向灯信号")
        MirrorInfoRow("摄像头", "左转显示左侧画面，右转显示右侧画面")
    }
}

@Composable
private fun MirrorTriggerPanel(
    enabled: Boolean,
    keepSignal: Boolean,
    delayMs: Int,
    speed: Int,
    onKeepSignal: (Boolean) -> Unit,
    onDelay: (Int) -> Unit,
    onSpeed: (Int) -> Unit,
) {
    MirrorSection("触发与关闭") {
        MirrorSwitchRow("转向灯亮期间持续显示", "避免灯光闪烁间隙关闭画面", keepSignal, onKeepSignal, enabled)
        MirrorSliderRow(
            title = "延迟关闭",
            value = delayMs.toFloat(),
            range = BlindSpotPreferences.MIN_CLOSE_DELAY_MS.toFloat()..BlindSpotPreferences.MAX_CLOSE_DELAY_MS.toFloat(),
            valueText = if (delayMs == 0) "立即" else "${delayMs / 1000f} 秒",
            enabled = enabled && keepSignal,
            onValue = {
                onDelay((it.roundToInt() / BlindSpotPreferences.CLOSE_DELAY_STEP_MS) * BlindSpotPreferences.CLOSE_DELAY_STEP_MS)
            },
        )
        MirrorSliderRow(
            title = "最低触发车速",
            value = speed.toFloat(),
            range = BlindSpotPreferences.MIN_THRESHOLD_KMH.toFloat()..BlindSpotPreferences.MAX_THRESHOLD_KMH.toFloat(),
            valueText = "$speed km/h",
            enabled = enabled,
            onValue = { onSpeed((it / 5f).roundToInt() * 5) },
        )
        MirrorInfoRow("安全互斥", "倒车、原厂 360 影像开启时自动让出画面")
    }
}

@Suppress("LongParameterList")
@Composable
private fun MirrorDisplayPanel(
    enabled: Boolean,
    hasCluster: Boolean,
    target: MirrorTarget,
    onTarget: (MirrorTarget) -> Unit,
    widthPct: Int,
    rotate90: Boolean,
    glow: Boolean,
    onWidth: (Int) -> Unit,
    onRotate: (Boolean) -> Unit,
    onGlow: (Boolean) -> Unit,
    placing: BlindSpotPositionOverlay.Side?,
    overlay: BlindSpotPositionOverlay,
    onPlacing: (BlindSpotPositionOverlay.Side?) -> Unit,
    context: Context,
    ensurePermissions: () -> Unit,
    bothOnMain: Boolean,
) {
    MirrorSection("显示与预警") {
        Text("显示位置", color = MirrorMuted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MirrorChoice("双屏", target == MirrorTarget.AUTO, enabled, Modifier.weight(1f)) { onTarget(MirrorTarget.AUTO) }
            MirrorChoice("中控屏", target == MirrorTarget.MAIN, enabled, Modifier.weight(1f)) { onTarget(MirrorTarget.MAIN) }
            MirrorChoice("仪表屏", target == MirrorTarget.CLUSTER, enabled && hasCluster, Modifier.weight(1f)) { onTarget(MirrorTarget.CLUSTER) }
        }
        if (!hasCluster) Text("当前车机未发现可用仪表投屏显示器", color = MirrorMuted, fontSize = 12.sp)
        MirrorSliderRow(
            title = "画面宽度",
            value = widthPct.toFloat(),
            range = BlindSpotPreferences.MIN_PIP_WIDTH_PCT.toFloat()..BlindSpotPreferences.MAX_PIP_WIDTH_PCT.toFloat(),
            valueText = "$widthPct%",
            enabled = enabled,
            onValue = { onWidth((it / 2f).roundToInt() * 2) },
        )
        MirrorSwitchRow("竖向镜面布局", "画面旋转 90°，适配窄长后视区域", rotate90, onRotate, enabled)
        MirrorSwitchRow("盲区预警闪烁", "检测到侧后方目标时闪烁高亮边框", glow, onGlow, enabled)
        MirrorActionRow("右侧画面位置", if (placing == BlindSpotPositionOverlay.Side.RIGHT) "完成" else "调整") {
            togglePosition(BlindSpotPositionOverlay.Side.RIGHT, placing, overlay, onPlacing, context, ensurePermissions)
        }
        if (bothOnMain) {
            MirrorActionRow("左侧画面位置", if (placing == BlindSpotPositionOverlay.Side.LEFT) "完成" else "调整") {
                togglePosition(BlindSpotPositionOverlay.Side.LEFT, placing, overlay, onPlacing, context, ensurePermissions)
            }
        }
    }
}

@Suppress("LongParameterList")
private fun togglePosition(
    side: BlindSpotPositionOverlay.Side,
    placing: BlindSpotPositionOverlay.Side?,
    overlay: BlindSpotPositionOverlay,
    onPlacing: (BlindSpotPositionOverlay.Side?) -> Unit,
    context: Context,
    ensurePermissions: () -> Unit,
) {
    val closing = placing == side
    overlay.hide()
    if (closing) return
    if (!AndroidSettings.canDrawOverlays(context)) {
        ensurePermissions()
        return
    }
    if (overlay.show(context, side)) onPlacing(side)
}

@Composable
private fun MirrorSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = MirrorText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun MirrorSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MirrorRow).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) MirrorText else MirrorDisabled, fontSize = 15.sp)
            Text(subtitle, color = if (enabled) MirrorMuted else MirrorDisabled, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MirrorBlue,
                uncheckedThumbColor = Color(0xFFB8B8BC),
                uncheckedTrackColor = Color(0xFF55555A),
            ),
        )
    }
}

@Composable
private fun MirrorSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    enabled: Boolean,
    onValue: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MirrorRow).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = if (enabled) MirrorText else MirrorDisabled, fontSize = 15.sp)
            Text(valueText, color = if (enabled) MirrorBlue else MirrorDisabled, fontSize = 14.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValue,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = MirrorBlue,
                activeTrackColor = MirrorBlue,
                inactiveTrackColor = Color(0xFF5A5A60),
            ),
        )
    }
}

@Composable
private fun MirrorChoice(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MirrorBlue else MirrorRow,
            contentColor = MirrorText,
            disabledContainerColor = Color(0xFF303034),
            disabledContentColor = MirrorDisabled,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
    ) { Text(label, fontSize = 12.sp, maxLines = 1) }
}

@Composable
private fun MirrorActionRow(title: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MirrorRow).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = MirrorText, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(action, color = MirrorBlue, fontSize = 13.sp)
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MirrorMuted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MirrorInfoRow(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MirrorRow).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = MirrorText, fontSize = 14.sp, modifier = Modifier.widthIn(min = 86.dp))
        Spacer(Modifier.width(10.dp))
        Text(value, color = MirrorMuted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
    }
}

