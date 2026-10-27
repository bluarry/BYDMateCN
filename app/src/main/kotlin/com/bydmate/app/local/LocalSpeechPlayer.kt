package com.bydmate.app.local

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Uses installed offline voices only. No synthesis API or voice download. */
class LocalSpeechPlayer(context: Context, private val report: (String) -> Unit) {
    private var ready = false
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null
    private var generation = 0
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var engine: TextToSpeech? = null
    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) report("没有可用的本地 TTS 引擎")
        }
    }
    fun speak(text: String, rate: Float, pitch: Float) {
        val tts = engine
        if (!ready || tts == null) { report("本地语音引擎尚未就绪"); return }
        val voice = tts.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == Locale.CHINESE.language }
            ?.sortedBy { it.name }?.firstOrNull()
        if (voice == null) { report("未安装中文离线音色；本版不会联网下载"); return }
        if (text.isBlank() || text.length > 500) { report("请输入 1～500 字"); return }
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) { report("离线音色设置失败"); return }
        stop()
        val current = generation
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                if (generation == current && change != AudioManager.AUDIOFOCUS_GAIN) {
                    stop(); report("语音让出音频焦点，已停止")
                }
            }, handler).build()
        focus = request
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stop(); report("系统拒绝语音音频焦点"); return
        }
        val utterance = "bydmate-local-speech-$current"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { handler.post { if (generation == current && id == utterance) stop() } }
            override fun onError(id: String?) { handler.post {
                if (generation == current && id == utterance) { stop(); report("本地语音播放失败") }
            } }
        })
        tts.setAudioAttributes(attributes)
        tts.setSpeechRate(rate.coerceIn(.5f, 2f)); tts.setPitch(pitch.coerceIn(.5f, 2f))
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utterance)
        if (result != TextToSpeech.SUCCESS) stop()
        report(if (result == TextToSpeech.SUCCESS) "离线语音已提交（系统默认输出，不是车外广播）" else "本地语音提交失败")
    }
    fun stop() {
        generation++
        engine?.stop()
        val request = focus; focus = null
        request?.let { audio.abandonAudioFocusRequest(it) }
    }
    fun close() { ready = false; stop(); engine?.shutdown(); engine = null }
}
