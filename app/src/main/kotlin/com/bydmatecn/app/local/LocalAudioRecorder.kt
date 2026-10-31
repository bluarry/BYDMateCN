package com.bydmatecn.app.local

import android.media.MediaRecorder
import java.io.File

/** Explicit foreground recording, bounded to 60 seconds; never streams or runs at boot. */
class LocalAudioRecorder(private val root: File, private val store: LocalResourceStore,
                         private val report: (String) -> Unit, private val saved: (File) -> Unit) {
    private var recorder: MediaRecorder? = null
    private var pending: File? = null
    val active: Boolean get() = recorder != null
    fun start() {
        if (active) return
        check(root.isDirectory || root.mkdirs())
        val file = File.createTempFile("voice-", ".m4a", root)
        val candidate = MediaRecorder()
        recorder = candidate; pending = file
        try {
            candidate.setAudioSource(MediaRecorder.AudioSource.MIC)
            candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            candidate.setAudioEncodingBitRate(96_000)
            candidate.setAudioSamplingRate(44_100)
            candidate.setOutputFile(file.absolutePath)
            candidate.setMaxDuration(60_000)
            candidate.setMaxFileSize(LocalResourceStore.MAX_FILE_BYTES)
            candidate.setOnInfoListener { source, what, _ ->
                if (recorder === source && (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)) finish(true)
            }
            candidate.setOnErrorListener { source, _, _ ->
                if (recorder === source) { finish(false); report("录音出错，未保存") }
            }
            candidate.prepare(); candidate.start()
            report("正在本地录音，最长 60 秒；停止后保存，不上传")
        } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: java.io.IOException) { finish(false); report("无法录音：${error.message}") } catch (error: IllegalStateException) { finish(false); report("无法录音：${error.message}") } catch (error: IllegalArgumentException) { finish(false); report("无法录音：${error.message}") } catch (error: SecurityException) { finish(false); report("无法录音：${error.message}") }
    }
    fun finish(save: Boolean) {
        val current = recorder ?: return
        val file = pending
        recorder = null; pending = null
        val stopped = runCatching { current.stop() }.isSuccess
        current.release()
        try {
            if (save && stopped && file != null) {
                val result = file.inputStream().use { store.importAudio(it, "m4a") }
                saved(result); report("录音已保存：${result.name}")
            } else if (save) report("录音太短或停止失败，未保存")
        } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: java.io.IOException) { report("录音保存失败：${error.message}") } catch (error: IllegalStateException) { report("录音保存失败：${error.message}") } catch (error: IllegalArgumentException) { report("录音保存失败：${error.message}") } catch (error: SecurityException) { report("录音保存失败：${error.message}") }
        finally { file?.delete() }
    }
}
