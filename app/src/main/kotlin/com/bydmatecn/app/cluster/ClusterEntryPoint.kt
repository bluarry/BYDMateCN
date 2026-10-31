package com.bydmatecn.app.cluster

import com.bydmatecn.app.data.autoservice.AdbRestoreManager
import com.bydmatecn.app.data.autoservice.CloudOverWifiManager
import com.bydmatecn.app.data.camera.CameraStateMonitor
import com.bydmatecn.app.data.vehicle.HelperBootstrap
import com.bydmatecn.app.data.vehicle.HelperClient
import com.bydmatecn.app.hud.HudCheck
import com.bydmatecn.app.hud.HudController
import com.bydmatecn.app.split.SplitPreferences
import com.bydmatecn.app.voice.VoiceController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Bridges the @Singleton HelperClient/HelperBootstrap/VoiceController/HudController/SplitPreferences/AdbRestoreManager
 * into SteeringWheelKeyService and SettingsScreen composables (framework-instantiated, not Hilt).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ClusterEntryPoint {
    fun helperClient(): HelperClient
    fun helperBootstrap(): HelperBootstrap
    fun voiceController(): VoiceController
    fun hudController(): HudController
    fun splitPreferences(): SplitPreferences
    fun adbRestoreManager(): AdbRestoreManager
    fun cloudOverWifiManager(): CloudOverWifiManager
    fun cameraStateMonitor(): CameraStateMonitor
    fun hudCheck(): HudCheck
}
