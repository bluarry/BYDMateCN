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
class LocalAudioPlayer(context: Context, internal val report: (String) -> Unit) {
    internal val exteriorSession = ExteriorAudioSession(context)
    internal val fileLog = LocalFileLog.get(context)
    internal val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    internal var player: MediaPlayer? = null
    internal var prepared = false
    internal var focus: AudioFocusRequest? = null
    internal val policy = AudioPlaybackPolicy()
    internal var volume = 50
    internal var looping = false
    internal var generation = 0
    internal val handler = Handler(Looper.getMainLooper())
    internal var timeout: Runnable? = null
    internal var boundedPlayback = false
    internal val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    fun play(file: File, device: AudioDeviceInfo?, playbackAttributes: AudioAttributes = attributes,
             routeLabel: String = "普通媒体", maximumDurationMs: Long? = null) {
        stop()
        boundedPlayback = maximumDurationMs != null
        policy.begin()
        val requestGeneration = generation
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(playbackAttributes).setOnAudioFocusChangeListener({ change ->
                onFocusChanged(change, requestGeneration)
            }, Handler(Looper.getMainLooper())).build()
        focus = request
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stop(); report("系统拒绝音频焦点，没有开始播放"); return
        }
        timeout = Runnable {
            if (requestGeneration == generation && !prepared) { stop(); report("音频准备超时") }
        }.also { handler.postDelayed(it, 10_000) }
        if (maximumDurationMs != null && !startExteriorSession()) return
        fileLog.record("audio/start", "file=${file.name}; route=$routeLabel; attrs=$playbackAttributes; preferred=${device?.id}")
        prepareMediaPlayer(file, device, playbackAttributes, routeLabel, maximumDurationMs)
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
    internal fun applyVolume() {
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
        val oldFocus = focus
        focus = null
        try {
            old?.release()
        } finally {
            try { oldFocus?.let { audio.abandonAudioFocusRequest(it) } }
            finally { exteriorSession.release() }
        }
    }
}
