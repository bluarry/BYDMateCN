package com.bydmatecn.app.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit, parked push-to-talk. No network, recording file, global stream or audio mode writes. */
class LocalMicRelay(private val context: Context, private val report: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val running = AtomicBoolean(false)
    private var focus: AudioFocusRequest? = null
    @Volatile private var generation = 0
    private var closed = false
    private var watchdog: Runnable? = null
    val active: Boolean get() = running.get()
    fun start(attributes: AudioAttributes, volume: Int) {
        check(!closed)
        stop()
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "需要麦克风权限" }
        val session = generation
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                if (generation == session && change != AudioManager.AUDIOFOCUS_GAIN) { stop(); report("喊话失去焦点，已停止") }
            }, handler).build()
        focus = request
        check(audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "系统拒绝喊话音频焦点" }
        running.set(true)
        watchdog = Runnable { if (generation == session) { stop(); report("喊话15秒到时停止") } }
            .also { handler.postDelayed(it, 15_000) }
        worker.execute {
            var input: AudioRecord? = null
            var output: AudioTrack? = null
            try {
                val rate = 16000
                val inputSize = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val outputSize = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(inputSize > 0 && outputSize > 0) { "硬件不支持16kHz单声道PCM" }
                input = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(inputSize * 2, 4096)).build()
                output = AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(outputSize * 2, 4096)).setTransferMode(AudioTrack.MODE_STREAM).build()
                check(input.state == AudioRecord.STATE_INITIALIZED && output.state == AudioTrack.STATE_INITIALIZED)
                check(output.setVolume(volume.coerceIn(0, 50) / 100f) == AudioTrack.SUCCESS)
                if (generation != session || !running.get()) return@execute
                input.startRecording(); output.play()
                check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                val buffer = ShortArray(320)
                handler.post { if (generation == session) report("喊话中：最多15秒，音量上限50%；外部出声位置待实车确认") }
                while (running.get() && generation == session) {
                    val count = input.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { "麦克风读取失败：$count" }
                    if (count == 0) { Thread.sleep(5); continue }
                    var offset = 0
                    val deadline = System.nanoTime() + 500_000_000
                    while (offset < count && running.get() && generation == session) {
                        val written = output.write(buffer, offset, count-offset, AudioTrack.WRITE_NON_BLOCKING)
                        check(written >= 0) { "外放写入失败：$written" }
                        offset += written
                        check(System.nanoTime() < deadline) { "音频输出阻塞" }
                        if (written == 0) Thread.sleep(5)
                    }
                }
            } catch (error: Exception) {
                handler.post { if (generation == session) { stop(); report("喊话失败：${error.message}") } }
            } finally {
                runCatching { input?.stop() }; runCatching { input?.release() }
                runCatching { output?.pause() }; runCatching { output?.flush() }; runCatching { output?.release() }
            }
        }
    }
    fun stop() {
        running.set(false); generation++
        watchdog?.let { handler.removeCallbacks(it) }; watchdog = null
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
    }
    fun close() { if (closed) return; closed = true; stop(); worker.shutdown() }
}
