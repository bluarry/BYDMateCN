package com.bydmatecn.app.local

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Uses installed offline voices only, including local file synthesis. Never downloads voices. */
class LocalSpeechPlayer(context: Context, private val report: (String) -> Unit) {
    private var ready = false
    private val cache = java.io.File(context.cacheDir, "local-tts")
    private var pending: java.io.File? = null
    private var timeout: Runnable? = null
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
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
    fun synthesize(text: String, rate: Float, pitch: Float, store: LocalResourceStore, saved: (java.io.File) -> Unit) {
        val tts = engine
        if (!ready || tts == null) { report("离线语音引擎未就绪"); return }
        val voice = tts.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == Locale.CHINESE.language }
            ?.sortedBy { it.name }?.firstOrNull()
        if (voice == null) { report("请先安装中文离线音色，本版不联网下载"); return }
        if (text.isBlank() || text.length > 500) { report("请输入 1～500 字"); return }
        stop()
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) { report("无法设置离线音色"); return }
        val file = createSpeechFile() ?: return
        pending = file
        val current = generation
        val utterance = "bydmate-local-file-$current"
        val watchdog = Runnable { if (generation == current) { stop(); report("离线合成超时，已停止") } }
        timeout = watchdog; handler.postDelayed(watchdog, 60_000)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { handler.post {
                if (generation != current || id != utterance) { file.delete(); return@post }
                timeout?.let { handler.removeCallbacks(it) }; timeout = null
                pending = null
                worker.execute {
                    val result = runCatching {
                        check(file.length() in 44..LocalResourceStore.MAX_FILE_BYTES) { "合成结果为空或过大" }
                        file.inputStream().use { input ->
                            val header = ByteArray(12)
                            check(input.read(header) == 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                                String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "引擎没有生成标准 WAV" }
                        }
                        file.inputStream().use { store.importAudio(it, "wav") }
                    }
                    file.delete()
                    handler.post {
                        if (generation == current) result.onSuccess { saved(it); report("离线语音已保存，可选择固件专用路由试听") }
                            .onFailure { report("语音保存失败：${it.message}") }
                        else result.getOrNull()?.let { store.delete(it) }
                    }
                }
            } }
            override fun onError(id: String?) { handler.post {
                if (generation == current && id == utterance) { stop(); report("离线文件合成失败") }
            } }
        })
        tts.setSpeechRate(rate.coerceIn(.5f, 2f)); tts.setPitch(pitch.coerceIn(.5f, 2f))
        // Synthesis to a private file does not request audio focus or play any sound.
        if (tts.synthesizeToFile(text, Bundle(), file, utterance) != TextToSpeech.SUCCESS) {
            stop(); report("引擎拒绝离线文件合成")
        } else report("正在离线合成 WAV（不会播放）")
    }

    fun stop() {
        generation++
        timeout?.let { handler.removeCallbacks(it) }; timeout = null
        engine?.stop()
        pending?.delete(); pending = null
        val request = focus; focus = null
        request?.let { audio.abandonAudioFocusRequest(it) }
    }
    fun close() { ready = false; stop(); engine?.shutdown(); engine = null; worker.shutdown() }

    private fun createSpeechFile(): java.io.File? = try {
            check(cache.isDirectory || cache.mkdirs())
            java.io.File.createTempFile("speech-", ".wav", cache)
        } catch (error: java.io.IOException) { report("创建语音文件失败：${error.message}"); null } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: IllegalStateException) { report("创建语音文件失败：${error.message}"); null } catch (error: SecurityException) { report("创建语音文件失败：${error.message}"); null }
}
