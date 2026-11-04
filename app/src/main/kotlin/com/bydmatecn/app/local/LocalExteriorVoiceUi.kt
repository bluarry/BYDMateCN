package com.bydmatecn.app.local

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val VoicePanel = Color(0xFF171E25)
private val VoiceSecondary = Color(0xFF8B96A2)

internal data class ExteriorVoiceSettings(
    val selectedProfile: ExteriorControllerProfile,
    val profiles: List<ExteriorControllerProfile>,
    val verified: Boolean,
    val volume: Int,
    val microphoneGain: Int,
    val noiseReduction: Int,
    val echoProtection: Boolean,
)

internal data class ExteriorVoiceActions(
    val onVerifiedChange: (Boolean) -> Unit,
    val onVolumeChange: (Int) -> Unit,
    val onMicrophoneGainChange: (Int) -> Unit,
    val onNoiseReductionChange: (Int) -> Unit,
    val onEchoProtectionChange: (Boolean) -> Unit,
    val onProfileSelected: (ExteriorControllerProfile) -> Unit,
    val onTalkPressed: () -> Unit,
    val onTalkReleased: () -> Unit,
)

internal fun LocalUiState.exteriorVoicePanel(
    settings: ExteriorVoiceSettings,
    status: LocalText,
    actions: ExteriorVoiceActions,
) {
    controls.add { ExteriorVoiceConfiguration(settings, status, actions) }
}

@Composable
private fun ExteriorVoiceConfiguration(
    settings: ExteriorVoiceSettings,
    status: LocalText,
    actions: ExteriorVoiceActions,
) {
    var enabled by remember(settings.verified) { mutableStateOf(settings.verified) }
    var volume by remember(settings.volume) { mutableIntStateOf(settings.volume) }
    var microphoneGain by remember(settings.microphoneGain) { mutableIntStateOf(settings.microphoneGain) }
    var noiseReduction by remember(settings.noiseReduction) { mutableIntStateOf(settings.noiseReduction) }
    var echoProtection by remember(settings.echoProtection) { mutableStateOf(settings.echoProtection) }

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text("车外喊话", color = Color(0xFFF2F4F6), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "驻车时通过车外扬声器实时喊话，按住下方按钮说话，松手停止。",
            color = VoiceSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 7.dp, bottom = 16.dp),
        )
        VoiceSwitchRow(
            title = "启用车外喊话",
            description = "启用前请先完成车外试听，确认所选控制器确实从车外扬声器发声。",
            checked = enabled,
        ) {
            enabled = it
            actions.onVerifiedChange(it)
        }
        VoiceDivider()
        VoiceSliderRow("喊话音量", "限制最高 50%，降低车内反馈与车外音量突变风险。", volume, 50) {
            volume = it
            actions.onVolumeChange(it)
        }
        VoiceDivider()
        VoiceSliderRow("麦克风增益", "调整人声输入强度，过高可能产生啸叫。", microphoneGain, 100) {
            microphoneGain = it
            actions.onMicrophoneGainChange(it)
        }
        VoiceDivider()
        VoiceSliderRow("降噪强度", "过滤环境底噪；强度过高可能削弱较轻的人声。", noiseReduction, 100) {
            noiseReduction = it
            actions.onNoiseReductionChange(it)
        }
        VoiceDivider()
        VoiceSwitchRow("防啸叫与回声抑制", "检测持续强反馈时自动降低输出，并启用系统回声消除。", echoProtection) {
            echoProtection = it
            actions.onEchoProtectionChange(it)
        }
        VoiceDivider()

        Text(
            "车外音频控制器",
            color = Color(0xFFF0F2F4),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
        )
        Text("不同车型的车外功放通道可能不同，切换后需要重新试听确认。", color = VoiceSecondary, fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 12.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            settings.profiles.forEach { profile ->
                val selected = profile == settings.selectedProfile
                Box(
                    Modifier.background(if (selected) AudioAccent else Color(0xFF222A32), RoundedCornerShape(6.dp))
                        .clickable { if (!selected) actions.onProfileSelected(profile) }
                        .padding(horizontal = 28.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        profile.title,
                        color = if (selected) Color(0xFF081421) else Color(0xFFBBC4CC),
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }

        Text("按住喊话", color = Color(0xFFF0F2F4), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 58.dp)
                .background(if (enabled) AudioAccent else Color(0xFF29323A), RoundedCornerShape(7.dp))
                .pointerInput(enabled) {
                    detectTapGestures(onPress = {
                        if (!enabled) return@detectTapGestures
                        try {
                            actions.onTalkPressed()
                            tryAwaitRelease()
                        } finally {
                            actions.onTalkReleased()
                        }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (enabled) "按住说话 · 松手停止" else "请先启用并确认车外路由",
                color = if (enabled) Color(0xFF071522) else Color(0xFF7F8A94),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Text("最近状态", color = Color(0xFFF0F2F4), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
        Row(
            Modifier.fillMaxWidth().background(VoicePanel, RoundedCornerShape(6.dp))
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(AudioAccent, RoundedCornerShape(4.dp)))
            Text(
                status.text,
                color = Color(0xFFAAB4BD),
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 11.dp),
            )
        }
        Spacer(Modifier.size(18.dp))
    }
}

@Composable
private fun VoiceSwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color(0xFFE9EDF0), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = VoiceSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AudioAccent,
                uncheckedThumbColor = Color(0xFFA4ADB5),
                uncheckedTrackColor = Color(0xFF343C44),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun VoiceSliderRow(title: String, description: String, value: Int, max: Int, onValueChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color(0xFFE9EDF0), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(description, color = VoiceSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
            }
            Text(if (max == 100) "$value%" else value.toString(), color = AudioAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..max.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = AudioAccent,
                activeTrackColor = AudioAccent,
                inactiveTrackColor = Color(0xFF343D47),
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
private fun VoiceDivider() {
    Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(Color(0xFF252E36)))
}
