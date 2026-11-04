package com.bydmatecn.app.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Activity-scoped controls; the activity owns state and lifecycle cleanup. */
internal fun LocalToolsActivity.microphoneControls(audioPrefs: android.content.SharedPreferences) {
    ui.section("车外语音")
    micRelay = LocalMicRelay(this, helper) { status.text = it }
    val pressState = ExteriorTalkPressState()
    ui.exteriorVoicePanel(
        settings = exteriorVoiceSettings(audioPrefs),
        status = status,
        actions = exteriorVoiceActions(audioPrefs, pressState),
    )
    speechControls(audioPrefs)
}

private class ExteriorTalkPressState(var held: Boolean = false)

private fun LocalToolsActivity.exteriorVoiceSettings(
    audioPrefs: android.content.SharedPreferences,
) = ExteriorVoiceSettings(
    selectedProfile = ExteriorControllerProfile.selected(this),
    profiles = ExteriorControllerProfile.entries.toList(),
    verified = audioPrefs.getBoolean("exterior_verified", false),
    volume = audioPrefs.getInt("volume", 25).coerceIn(0, 50),
    microphoneGain = audioPrefs.getInt("microphone_gain", 65).coerceIn(0, 100),
    noiseReduction = audioPrefs.getInt("noise_reduction", 80).coerceIn(0, 100),
    echoProtection = audioPrefs.getBoolean("echo_protection", true),
)

private fun LocalToolsActivity.exteriorVoiceActions(
    audioPrefs: android.content.SharedPreferences,
    pressState: ExteriorTalkPressState,
) = ExteriorVoiceActions(
    onVerifiedChange = { checked ->
        audioPrefs.edit().putBoolean("exterior_verified", checked).apply()
        if (!checked) {
            talkRequestGeneration++
            micRelay?.stop()
        }
    },
    onVolumeChange = { value ->
        localPlayer.setVolume(value)
        audioPrefs.edit().putInt("volume", value).apply()
    },
    onMicrophoneGainChange = { value -> audioPrefs.edit().putInt("microphone_gain", value).apply() },
    onNoiseReductionChange = { value -> audioPrefs.edit().putInt("noise_reduction", value).apply() },
    onEchoProtectionChange = { enabled -> audioPrefs.edit().putBoolean("echo_protection", enabled).apply() },
    onProfileSelected = { profile -> selectExteriorProfile(audioPrefs, profile) },
    onTalkPressed = { beginExteriorTalk(audioPrefs, pressState) },
    onTalkReleased = { endExteriorTalk(pressState) },
)

private fun LocalToolsActivity.selectExteriorProfile(
    audioPrefs: android.content.SharedPreferences,
    profile: ExteriorControllerProfile,
) {
    stopAudio()
    micRelay?.stop()
    ExteriorControllerProfile.select(this, profile)
    audioPrefs.edit().putBoolean("exterior_verified", false).apply()
    getSharedPreferences("local-lock-sound", Context.MODE_PRIVATE).edit().putBoolean("verified", false).apply()
    status.text = "已切换为${profile.title}，请重新完成驻车试听确认"
    recreate()
}

private fun LocalToolsActivity.beginExteriorTalk(
    audioPrefs: android.content.SharedPreferences,
    pressState: ExteriorTalkPressState,
) {
    pressState.held = true
    val request = ++talkRequestGeneration
    LocalFileLog.get(this).record("mic", "touch down")
    when {
        !audioPrefs.getBoolean("exterior_verified", false) -> status.text = "请先完成车外试听并确认车外路由"
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        else -> lifecycleScope.launch { startExteriorTalkWhenAllowed(audioPrefs, pressState, request) }
    }
}

private suspend fun LocalToolsActivity.startExteriorTalkWhenAllowed(
    audioPrefs: android.content.SharedPreferences,
    pressState: ExteriorTalkPressState,
    request: Int,
) {
    val snapshot = kotlinx.coroutines.withTimeoutOrNull(2500) { vehicle.readSnapshot() }
    if (request != talkRequestGeneration || !pressState.held ||
        !firmwareAudioAllowed(snapshot?.gear, snapshot?.speed, resumed)
    ) return
    runCatching {
        recorder?.finish(false)
        speech?.stop()
        stopAudio()
        micRelay?.start(
            firmwareAudio.focusAttributes(FirmwareAudioRoute.EXTERIOR_VOICE),
            MicRelaySettings(
                volume = audioPrefs.getInt("volume", 25),
                echoProtection = audioPrefs.getBoolean("echo_protection", true),
                microphoneGain = audioPrefs.getInt("microphone_gain", 65),
                noiseReduction = audioPrefs.getInt("noise_reduction", 80),
            ),
        )
    }.onFailure {
        micRelay?.stop()
        status.text = "喊话启动失败：${it.message}"
    }
}

private fun LocalToolsActivity.endExteriorTalk(pressState: ExteriorTalkPressState) {
    pressState.held = false
    talkRequestGeneration++
    micRelay?.stop()
    LocalFileLog.get(this).record("mic", "touch released/cancelled")
}

