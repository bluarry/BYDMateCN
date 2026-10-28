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
                    if (maximumDurationMs != null) {
                        timeout = Runnable {
                            if (requestGeneration == generation) { stop(); report("专用试听到时，已停止") }
                        }.also { handler.postDelayed(it, maximumDurationMs) }
                    }
                    report(if (policy.mayPlay) "播放中：${file.name}；$routeLabel（扬声器需实车确认）" else "已准备，等待音频焦点或继续播放")
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
