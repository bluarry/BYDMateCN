package com.bydmatecn.app.local

import android.media.AudioManager
import android.media.MediaPlayer
import java.io.File

internal fun LocalAudioPlayer.onPrepared(ready: MediaPlayer, file: File, routeLabel: String, maximumDurationMs: Long?, requestGeneration: Int) {
                if (player === ready) {
                    timeout?.let { handler.removeCallbacks(it) }; timeout = null
                    prepared = true
                    ready.isLooping = looping && !boundedPlayback
                    applyVolume()
                    if (policy.mayPlay) ready.start()
                    fileLog.record("audio/prepared", "route=$routeLabel; device=${ready.routedDevice?.type}/${ready.routedDevice?.id}; started=${policy.mayPlay}")
                    if (maximumDurationMs != null) {
                        timeout = Runnable {
                            if (requestGeneration == generation) { stop(); report("专用试听到时，已停止") }
                        }.also { handler.postDelayed(it, maximumDurationMs) }
                    }
                    report(if (policy.mayPlay) "播放中：${file.name}；$routeLabel；设备=${ready.routedDevice?.type}/${ready.routedDevice?.id}" else "已准备，等待音频焦点或继续播放")
                }

}

internal fun LocalAudioPlayer.onFocusChanged(change: Int, requestGeneration: Int) {
                if (requestGeneration != generation) return
                if (boundedPlayback && change != AudioManager.AUDIOFOCUS_GAIN) {
                    stop(); report("专用音频让出焦点，已停止"); return
                }
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { stop(); report("音频焦点丢失，已停止") }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        policy.transientLoss()
                        if (prepared) player?.pause()
                        report("临时让出音频焦点")
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { policy.duck(); applyVolume() }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        policy.gain(); applyVolume()
                        if (prepared && policy.mayPlay) player?.start()
                    }
                }
    }

/** MediaPlayer creation is kept separate from focus and exterior-session ownership. */
internal fun LocalAudioPlayer.prepareMediaPlayer(file: File, device: android.media.AudioDeviceInfo?,
    playbackAttributes: android.media.AudioAttributes, routeLabel: String, maximumDurationMs: Long?) {
        val requestGeneration = generation
        val candidate = MediaPlayer()
        player = candidate
        try {
            val stream = exteriorStreamType
            if (stream != null) {
                @Suppress("DEPRECATION")
                candidate.setAudioStreamType(stream)
            } else candidate.setAudioAttributes(playbackAttributes)
            candidate.setDataSource(file.absolutePath)
            if (device != null && !candidate.setPreferredDevice(device)) error("输出设备被拒绝")
            applyVolume()
            candidate.setOnPreparedListener { ready -> onPrepared(ready, file, routeLabel, maximumDurationMs, requestGeneration) }
            candidate.setOnCompletionListener { completed ->
                if (player === completed) { stop(); report("播放结束") }
            }
            candidate.setOnErrorListener { failed, what, extra ->
                if (player === failed) { stop(); report("音频解码/输出失败：$what/$extra") }
                true
            }
            candidate.prepareAsync()
        } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: java.io.IOException) { stop(); report("播放失败：${error.message}") } catch (error: IllegalStateException) { stop(); report("播放失败：${error.message}") } catch (error: IllegalArgumentException) { stop(); report("播放失败：${error.message}") } catch (error: SecurityException) { stop(); report("播放失败：${error.message}") }
}
    // Restore runs even when provider/Parcel decoding throws an unexpected firmware exception.
    @Suppress("TooGenericExceptionCaught")
    internal fun LocalAudioPlayer.startExteriorSession(): Boolean = try {
        exteriorSession.acquire()
        exteriorStreamType = exteriorSession.streamType
        true
    } catch (error: Exception) {
        stop()
        report("车外路由启动失败：${error.message}")
        false
    }
