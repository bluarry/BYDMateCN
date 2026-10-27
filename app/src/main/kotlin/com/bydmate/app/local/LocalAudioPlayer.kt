package com.bydmate.app.local

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
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    fun play(file: File, device: AudioDeviceInfo?) {
        stop()
        policy.begin()
        val requestGeneration = generation
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                if (requestGeneration != generation) return@setOnAudioFocusChangeListener
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
        val candidate = MediaPlayer()
        player = candidate
        try {
            candidate.setAudioAttributes(attributes)
            candidate.setDataSource(file.absolutePath)
            if (device != null && !candidate.setPreferredDevice(device)) error("输出设备被拒绝")
            applyVolume()
            candidate.setOnPreparedListener { ready ->
                if (player === ready) {
                    prepared = true
                    ready.isLooping = looping
                    applyVolume()
                    if (policy.mayPlay) ready.start()
                    report(if (policy.mayPlay) "播放中：${file.name}" else "已准备，等待音频焦点或继续播放")
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
    fun setLooping(value: Boolean) { looping = value; if (prepared) player?.isLooping = value }
    fun seekPercent(percent: Int) {
        require(percent in 0..100)
        if (prepared) player?.let { it.seekTo((it.duration.toLong() * percent / 100).toInt()) }
    }
    fun progressPercent(): Int = if (prepared) player?.let {
        if (it.duration > 0) (it.currentPosition.toLong() * 100 / it.duration).toInt().coerceIn(0, 100) else 0
    } ?: 0 else 0
    fun progress(): String = if (prepared) player?.let {
        "${it.currentPosition / 1000}s / ${it.duration / 1000}s；实际路由：${it.routedDevice?.productName ?: "系统默认 / 尚未报告"}"
    } ?: "" else ""
    fun stop() {
        generation++
        val old = player
        player = null; prepared = false; policy.stop()
        old?.release()
        val oldFocus = focus
        focus = null
        oldFocus?.let { audio.abandonAudioFocusRequest(it) }
    }
}
