package com.bydmatecn.app.local

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File

/** Main-thread local playback. Never force-routes AVAS or suppresses OEM audio. */
class LocalAudioPlayer(context: Context, private val report: (String) -> Unit) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var prepared = false
    private var focus: AudioFocusRequest? = null
    private val policy = AudioPlaybackPolicy()
    private var volume = 50
    private var looping = false
    private var generation = 0
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    private var boundedPlayback = false
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    fun play(file: File, device: AudioDeviceInfo?, playbackAttributes: AudioAttributes = attributes,
             routeLabel: String = "普通媒体", maximumDurationMs: Long? = null) {
        stop()
        boundedPlayback = maximumDurationMs != null
        policy.begin()
        val requestGeneration = generation
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(playbackAttributes).setOnAudioFocusChangeListener({ change ->
                if (requestGeneration != generation) return@setOnAudioFocusChangeListener
                if (boundedPlayback && change != AudioManager.AUDIOFOCUS_GAIN) {
                    stop(); report("专用音频让出焦点，已停止"); return@setOnAudioFocusChangeListener
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
            }, Handler(Looper.getMainLooper())).build()
        focus = request
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stop(); report("系统拒绝音频焦点，没有开始播放"); return
        }
        timeout = Runnable {
            if (requestGeneration == generation && !prepared) { stop(); report("音频准备超时") }
        }.also { handler.postDelayed(it, 10_000) }
        val candidate = MediaPlayer()
        player = candidate
        try {
            candidate.setAudioAttributes(playbackAttributes)
            candidate.setDataSource(file.absolutePath)
            if (device != null && !candidate.setPreferredDevice(device)) error("输出设备被拒绝")
            applyVolume()
            candidate.setOnPreparedListener { ready ->
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
            candidate.setOnCompletionListener { completed ->
                if (player === completed) { stop(); report("播放结束") }
            }
            candidate.setOnErrorListener { failed, what, extra ->
                if (player === failed) { stop(); report("音频解码/输出失败：$what/$extra") }
                true
            }
            candidate.prepareAsync()
        } catch (error: Exception) { stop(); report("播放失败：${error.message}") }
    }
    fun togglePause() {
        val current = player
        if (!prepared || current == null) { report("音频尚未准备好"); return }
        if (policy.wantsPlayback) { policy.pause(); current.pause(); report("已暂停") }
        else {
            policy.resume()
            if (policy.mayPlay) { current.start(); report("继续播放") }
            else report("等待系统归还音频焦点")
        }
    }
    private fun applyVolume() {
        val level = policy.effectiveVolume(volume)
        player?.setVolume(level, level)
    }
    fun setVolume(percent: Int) { require(percent in 0..100); volume = percent; applyVolume() }
    fun setLooping(value: Boolean) { looping = value; if (prepared) player?.isLooping = value && !boundedPlayback }
    fun seekPercent(percent: Int) {
        require(percent in 0..100)
        if (prepared) player?.let { it.seekTo((it.duration.toLong() * percent / 100).toInt()) }
    }
    fun positionMs(): Long = if (prepared) player?.currentPosition?.toLong() ?: 0 else 0
    fun progressPercent(): Int = if (prepared) player?.let {
        if (it.duration > 0) (it.currentPosition.toLong() * 100 / it.duration).toInt().coerceIn(0, 100) else 0
    } ?: 0 else 0
    fun progress(): String = if (prepared) player?.let {
        "${it.currentPosition / 1000}s / ${it.duration / 1000}s；实际路由：${it.routedDevice?.productName ?: "系统默认 / 尚未报告"}"
    } ?: "" else ""
    fun stop() {
        generation++
        timeout?.let { handler.removeCallbacks(it) }; timeout = null
        val old = player
        player = null; prepared = false; policy.stop()
        old?.release()
        val oldFocus = focus
        focus = null
        oldFocus?.let { audio.abandonAudioFocusRequest(it) }
    }
}
