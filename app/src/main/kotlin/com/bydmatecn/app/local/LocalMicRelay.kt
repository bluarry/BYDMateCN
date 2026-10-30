package com.bydmatecn.app.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.os.Handler
import android.os.Looper
import com.bydmatecn.app.data.vehicle.HelperClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Parked push-to-talk routed through the selected BYD exterior-audio controller profile. */
class LocalMicRelay(
    private val context: Context,
    helper: HelperClient? = null,
    private val report: (String) -> Unit,
) {
    private val exteriorSession = ExteriorAudioSession(context, helper)
    private val fileLog = LocalFileLog.get(context)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val running = AtomicBoolean(false)
    private val relayPending = AtomicBoolean(false)
    private var focus: AudioFocusRequest? = null
    @Volatile private var generation = 0
    private var closed = false
    private var watchdog: Runnable? = null
    val active: Boolean get() = running.get()

    @Suppress("TooGenericExceptionCaught")
    fun start(attributes: AudioAttributes, volume: Int) {
        check(!closed)
        check(!relayPending.get()) { "上一段喊话正在停止，请稍后重试" }
        stop()
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "需要麦克风权限" }
        val session = generation
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                if (generation == session && change != AudioManager.AUDIOFOCUS_GAIN) { stop(); report("喊话失去焦点，已停止") }
            }, handler).build()
        focus = request
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stop(); error("系统拒绝喊话音频焦点")
        }
        try { exteriorSession.acquire() } catch (error: Exception) { stop(); throw error }
        relayPending.set(true)
        running.set(true)
        watchdog = Runnable { if (generation == session) { stop(); report("喊话15秒到时停止") } }
            .also { handler.postDelayed(it, 15_000) }
        worker.execute { runRelay(volume, session) }
    }

    fun stop() {
        running.set(false); generation++
        watchdog?.let { handler.removeCallbacks(it) }; watchdog = null
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
        if (!relayPending.get()) exteriorSession.release()
    }

    fun close() {
        if (closed) return
        closed = true; stop(); worker.shutdown()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runRelay(volumePercent: Int, session: Int) {
        var input: AudioRecord? = null
        var output: AudioTrack? = null
        var aec: AcousticEchoCanceler? = null
        var agc: AutomaticGainControl? = null
        try {
            // The relay runs asynchronously, so permission can be revoked after start().
            // Check again on the worker immediately before constructing AudioRecord.
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                fileLog.record("mic/error", "RECORD_AUDIO permission missing before AudioRecord creation")
                handler.post { if (generation == session) { stop(); report("喊话失败：麦克风权限已被撤销") } }
                return
            }
            val rate = 16_000
            val inputSize = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val outputSize = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(inputSize > 0 && outputSize > 0) { "硬件不支持16kHz单声道PCM" }
            val (source, preferred) = findBydMicrophone()
            input = AudioRecord.Builder().setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(inputSize * 2, 4096)).build()
            if (preferred != null) {
                val accepted = input.setPreferredDevice(preferred)
                fileLog.record("mic/device", "source=$source; id=${preferred.id}; type=${preferred.type}; address=${preferred.address}; accepted=$accepted")
            } else fileLog.record("mic/device", "source=$source; BYD ECNR input not found")
            @Suppress("DEPRECATION")
            output = AudioTrack(
                exteriorSession.streamType,
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(outputSize * 2, 4096),
                AudioTrack.MODE_STREAM,
            )
            check(input.state == AudioRecord.STATE_INITIALIZED && output.state == AudioTrack.STATE_INITIALIZED)
            val baseVolume = volumePercent.coerceIn(0, 50) / 100f
            check(output.setVolume(baseVolume) == AudioTrack.SUCCESS)
            aec = enableAec(input.audioSessionId)
            agc = enableAgc(input.audioSessionId)
            fileLog.record("mic/effects", "aec=${aec?.enabled == true}; agc=${agc?.enabled == true}; stream=${exteriorSession.streamType}")
            if (generation != session || !running.get()) return
            input.startRecording(); output.play()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            relayPcm(input, output, baseVolume, session)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            handler.post { if (generation == session) { stop(); report("喊话线程已中断") } }
        } catch (error: Exception) {
            fileLog.record("mic/error", "${error.javaClass.simpleName}: ${error.message}")
            handler.post { if (generation == session) { stop(); report("喊话失败：${error.message}") } }
        } finally {
            runCatching { input?.stop() }; runCatching { input?.release() }
            runCatching { output?.pause() }; runCatching { output?.flush() }; runCatching { output?.release() }
            runCatching { aec?.release() }; runCatching { agc?.release() }
            // Keep the route alive briefly so the final PCM is not truncated.
            if (relayPending.get()) runCatching { Thread.sleep(1_000) }
            exteriorSession.release()
            relayPending.set(false)
            handler.post { if (generation == session) stop() }
        }
    }

    private fun relayPcm(input: AudioRecord, output: AudioTrack, baseVolume: Float, session: Int) {
        val buffer = ShortArray(320)
        var feedbackHits = 0
        var dynamicVolume = baseVolume
        handler.post { if (generation == session) report("喊话中：${exteriorSession.profile.title}，最多15秒") }
        while (running.get() && generation == session) {
            val count = input.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
            check(count >= 0) { "麦克风读取失败：$count" }
            if (count == 0) { Thread.sleep(5); continue }
            var peak = 0
            var total = 0L
            for (index in 0 until count) {
                val level = abs(buffer[index].toInt())
                if (level > peak) peak = level
                total += level
            }
            val average = total / count
            feedbackHits = if (peak > 30_000 && average > 12_000) feedbackHits + 1 else (feedbackHits - 1).coerceAtLeast(0)
            val next = if (feedbackHits >= 3) (dynamicVolume - 0.04f).coerceAtLeast(0.30f.coerceAtMost(baseVolume))
                else (dynamicVolume + 0.0015f).coerceAtMost(baseVolume)
            if (next != dynamicVolume) { dynamicVolume = next; output.setVolume(dynamicVolume) }
            writePcm(output, buffer, count, session)
        }
    }

    private fun writePcm(output: AudioTrack, buffer: ShortArray, count: Int, session: Int) {
        var offset = 0
        val deadline = System.nanoTime() + 500_000_000
        while (offset < count && running.get() && generation == session) {
            val written = output.write(buffer, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
            check(written >= 0) { "外放写入失败：$written" }
            offset += written
            check(System.nanoTime() < deadline) { "音频输出阻塞" }
            if (written == 0) Thread.sleep(5)
        }
    }

    private fun findBydMicrophone(): Pair<Int, AudioDeviceInfo?> {
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val byd = inputs.filter { it.address.contains("COM_BYD", ignoreCase = true) }
        val preferred = byd.firstOrNull { it.address.equals("COM_BYD_AUDIO_BUILTIN_MIC_ECNR", ignoreCase = true) }
            ?: byd.firstOrNull()
        val source = if (preferred?.type == AudioDeviceInfo.TYPE_USB_DEVICE) MediaRecorder.AudioSource.MIC
            else MediaRecorder.AudioSource.VOICE_COMMUNICATION
        fileLog.record("mic/devices", inputs.joinToString { "${it.id}/${it.type}/${it.address}" })
        return source to preferred
    }

    private fun enableAec(sessionId: Int): AcousticEchoCanceler? = runCatching {
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } else null
    }.getOrNull()

    private fun enableAgc(sessionId: Int): AutomaticGainControl? = runCatching {
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId)?.apply { enabled = true } else null
    }.getOrNull()
}
