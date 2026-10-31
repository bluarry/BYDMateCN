package com.bydmatecn.app.local

import android.view.TextureView

internal fun LocalCameraActivity.readVideoFrame(): android.graphics.Bitmap? = try {
    snapshotBitmap()
} catch (error: OutOfMemoryError) {
    videoFrameReadFailed(error)
    null
} catch (error: IllegalStateException) {
    videoFrameReadFailed(error)
    null
} catch (error: IllegalArgumentException) {
    videoFrameReadFailed(error)
    null
}

private fun LocalCameraActivity.videoFrameReadFailed(error: Throwable) {
    videoFrameBusy.set(false)
    stopVideo()
    status.text = "录像读取画面失败：${error.message}"
}

internal fun LocalCameraActivity.readFrame(view: TextureView): android.graphics.Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        val scale = minOf(1f, 1280f / view.width, 720f / view.height)
        return view.getBitmap(maxOf(1, (view.width * scale).toInt()), maxOf(1, (view.height * scale).toInt()))
    }
