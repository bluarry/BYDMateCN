package com.bydmatecn.app.local

import android.view.WindowManager

/** Activity-scoped controls; the activity owns state and lifecycle cleanup. */
internal fun LocalCameraActivity.snapshotBitmap(): android.graphics.Bitmap? {
        val primary = readFrame(texture) ?: run { return null }
        return if (dual) {
            var secondaryRead = false
            val secondary = try { readFrame(secondTexture).also { secondaryRead = true } } finally { if (!secondaryRead) primary.recycle() }
            if (secondary == null) { primary.recycle(); return null }
            try {
                android.graphics.Bitmap.createBitmap(primary.width + secondary.width, maxOf(primary.height, secondary.height), android.graphics.Bitmap.Config.ARGB_8888).also { combined ->
                    val canvas = android.graphics.Canvas(combined)
                    canvas.drawBitmap(primary, 0f, 0f, null)
                    canvas.drawBitmap(secondary, primary.width.toFloat(), 0f, null)
                }
            } finally { primary.recycle(); secondary.recycle() }
        } else primary
    }

internal fun LocalCameraActivity.startVideo() {
        if (!open || !resumed || videoRecording) { status.text = "请先打开驻车预览"; return }
        recording = false
        videoRecording = true
        val session = ++videoSession
        videoWorker.execute { startVideoWorker(session) }
    }

internal fun LocalCameraActivity.submitVideoFrame() {
        if (!videoFrameBusy.compareAndSet(false, true)) return
        val session = videoSession
        val frame = readVideoFrame()

        if (frame == null) { videoFrameBusy.set(false); return }
        videoWorker.execute { encodeVideoFrame(session, frame) }
    }

internal fun LocalCameraActivity.bookmarkEvent(reason: String) {
        if (!videoRecording || !open) { status.text = "先开始录像"; return }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now-lastEventMs < 10_000) { status.text = "事件10秒去重保护"; return }
        lastEventMs = now
        val session = videoSession
        videoWorker.execute {
            val result = runCatching {
                videos.files().lastOrNull()?.let { videos.protect(it, reason) }
                videoRecorder.finish(reason)
                videoRecorder.start(); segmentStarted = android.os.SystemClock.elapsedRealtime()
                // Protect next finalized segment as event post-roll.
                pendingEventReason = reason
            }.onFailure { videoRecorder.abort() }
            runOnUiThread { if (videoSession == session) {
                status.text = result.fold({ "事件已标记：$reason；保护前段/当前段/后段" }, { "事件保存失败：${it.message}" })
                if (result.isFailure) { videoRecording = false }
            } }
        }
    }

internal fun LocalCameraActivity.finishSegment(): java.io.File? {
        val file = videoRecorder.finish(pendingEventReason)
        pendingEventReason = null
        return file
    }

internal fun LocalCameraActivity.stopVideo() {
        if (!videoRecording) return
        videoRecording = false
        val session = ++videoSession
        // Queued after the last frame, never block the UI or camera release while muxer drains.
        videoWorker.execute {
            val result = runCatching { finishSegment() }
            runOnUiThread {
                if (!isDestroyed && videoSession == session && resumed) status.text = result.fold(
                    { if (it == null) "录像已停止" else "已保存 MP4：${it.name}" },
                    { "录像收尾失败：${it.message}" })
            }
        }
    }

internal fun LocalCameraActivity.closePreview(reason: String) {
        generation++
        stopVideo()
        recording = false; eventMode = false; previousDoors = null
        texture.alpha = 1f; secondTexture.alpha = 1f
        probe.close(reason)
        open = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = reason
    }

private fun LocalCameraActivity.startVideoWorker(session: Int) {
            try {
                videoRecorder.start()
                segmentStarted = android.os.SystemClock.elapsedRealtime()
                runOnUiThread { if (videoSession == session && videoRecording) status.text = "MP4 录像中：640×360 / 5 FPS / 无音频 / 512 MiB 循环存储" }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: java.io.IOException) {
                videoRecorder.abort()
                runOnUiThread { if (videoSession == session) { videoRecording = false; status.text = "录像启动失败：${error.message}" } }
            } catch (error: IllegalStateException) {
                videoRecorder.abort()
                runOnUiThread { if (videoSession == session) { videoRecording = false; status.text = "录像启动失败：${error.message}" } }
            } catch (error: IllegalArgumentException) {
                videoRecorder.abort()
                runOnUiThread { if (videoSession == session) { videoRecording = false; status.text = "录像启动失败：${error.message}" } }
            } catch (error: SecurityException) {
                videoRecorder.abort()
                runOnUiThread { if (videoSession == session) { videoRecording = false; status.text = "录像启动失败：${error.message}" } }
            } catch (error: ReflectiveOperationException) {
                videoRecorder.abort()
                runOnUiThread { if (videoSession == session) { videoRecording = false; status.text = "录像启动失败：${error.message}" } }
            }

}

private fun LocalCameraActivity.encodeVideoFrame(session: Int, frame: android.graphics.Bitmap) {
            try {
                if (videoRecorder.active) {
                    videoRecorder.frame(frame)
                    if (android.os.SystemClock.elapsedRealtime() - segmentStarted >= 60_000) {
                        finishSegment()
                        videoRecorder.start()
                        segmentStarted = android.os.SystemClock.elapsedRealtime()
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: java.io.IOException) {
                videoRecorder.abort()
                runOnUiThread { if (videoSession == session) { videoRecording = false; status.text = "录像停止：${error.message}" } }
            } catch (error: IllegalStateException) {
                videoRecorder.abort()
                runOnUiThread { if (videoSession == session) { videoRecording = false; status.text = "录像停止：${error.message}" } }
            } catch (error: IllegalArgumentException) {
                videoRecorder.abort()
                runOnUiThread { if (videoSession == session) { videoRecording = false; status.text = "录像停止：${error.message}" } }
            } catch (error: SecurityException) {
                videoRecorder.abort()
                runOnUiThread { if (videoSession == session) { videoRecording = false; status.text = "录像停止：${error.message}" } }
            } catch (error: ReflectiveOperationException) {
                videoRecorder.abort()
                runOnUiThread { if (videoSession == session) { videoRecording = false; status.text = "录像停止：${error.message}" } }
            } finally { frame.recycle(); videoFrameBusy.set(false) }

}
