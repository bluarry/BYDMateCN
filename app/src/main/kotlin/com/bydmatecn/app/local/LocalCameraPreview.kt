@file:Suppress("TooManyFunctions")

package com.bydmatecn.app.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.view.WindowManager
import android.view.Surface
import androidx.lifecycle.lifecycleScope
import com.bydmatecn.app.camera.AvmConsumerPriority
import com.bydmatecn.app.camera.AvmSurfaceBinding
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/** Activity-scoped controls; the activity owns state and lifecycle cleanup. */
internal fun LocalCameraActivity.requestPreviewRestart() {
    pendingPreviewRestart = true
    texture.post { restartPreviewIfReady() }
}

internal fun LocalCameraActivity.restartPreviewIfReady() {
    if (!pendingPreviewRestart) return
    if (surface == null) return
    if (dual && secondSurface == null) return
    pendingPreviewRestart = false
    startPreview()
}

internal fun LocalCameraActivity.startPreview() {
        if (!previewSurfacesReady()) return
        val target = surface ?: run { status.text = "预览 Surface 尚未就绪"; return }
        opening = true
        val request = ++generation
        lifecycleScope.launch {
            try {
                if (!previewAllowed(request)) return@launch
                bindPreview(target)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: java.io.IOException) {
                closePreview("预览启动失败：${error.message}")
            } catch (error: IllegalStateException) {
                closePreview("预览启动失败：${error.message}")
            } catch (error: IllegalArgumentException) {
                closePreview("预览启动失败：${error.message}")
            } catch (error: SecurityException) {
                closePreview("预览启动失败：${error.message}")
            } catch (error: ReflectiveOperationException) {
                closePreview("预览启动失败：${error.message}")
            } finally { opening = false }
        }
    }

internal fun LocalCameraActivity.saveViewConfig() {
        getSharedPreferences("local-mirror", Context.MODE_PRIVATE).edit()
            .putBoolean("dual", dual).putInt("index", index)
            .putBoolean("flip", viewConfig.horizontalFlip).putInt("zoom", viewConfig.zoomPercent)
            .putInt("rotation", viewConfig.rotation).apply()
    }

internal fun LocalCameraActivity.applyViewConfig() {
        listOf(texture, secondTexture).forEach { view ->
            if (view.width > 0 && view.height > 0) {
                val transform = Matrix()
                transform.postScale(viewConfig.scaleX(), viewConfig.scaleY(), view.width / 2f, view.height / 2f)
                transform.postRotate(viewConfig.rotation.toFloat(), view.width / 2f, view.height / 2f)
                view.setTransform(transform)
            }
        }
    }

internal fun LocalCameraActivity.capture() {
        if (!open || saving) return
        try { captureFrame() } catch (_: OutOfMemoryError) { recording = false; status.text = "截图内存不足，已停止帧录像" } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: java.io.IOException) { recording = false; status.text = "读取画面失败：${error.message}" } catch (error: IllegalStateException) { recording = false; status.text = "读取画面失败：${error.message}" } catch (error: IllegalArgumentException) { recording = false; status.text = "读取画面失败：${error.message}" } catch (error: SecurityException) { recording = false; status.text = "读取画面失败：${error.message}" } catch (error: ReflectiveOperationException) { recording = false; status.text = "读取画面失败：${error.message}" }
    }



internal fun LocalCameraActivity.captureFrame() {
        val bitmap = snapshotBitmap() ?: run { status.text = "还没有可读画面"; return }
        saving = true
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = ByteArrayOutputStream().use { stream ->
                            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream))
                            stream.toByteArray()
                        }
                        store.saveJpeg(bytes, System.currentTimeMillis())
                    }
                }
                status.text = result.fold({ "已保存：${it.name}" }, { "保存失败：${it.message}" })
                if (result.isFailure) recording = false
            } finally { bitmap.recycle(); saving = false }
        }
    }

private fun LocalCameraActivity.bindPreview(target: Surface) {
                // Vendor lifetime operations are serialized on the UI thread with texture teardown.
                val bindings = if (dual) {
                    listOf(
                        AvmSurfaceBinding(2, target),
                        AvmSurfaceBinding(3, checkNotNull(secondSurface)),
                    )
                } else {
                    listOf(AvmSurfaceBinding(index, target))
                }
                open = avmSession.attach(
                    MANUAL_CAMERA_OWNER,
                    bindings,
                    AvmConsumerPriority.INTERACTIVE,
                )
                if (open) {
                    lastFrameMs = android.os.SystemClock.elapsedRealtime()
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    texture.alpha = if (driving) 0f else 1f
                    secondTexture.alpha = if (driving) 0f else 1f
                    if (driving) startVideo()
                }
                status.text = if (open) {
                    "预览已启动：通道 ${if (dual) "2 + 3" else index.toString()}"
                } else {
                    avmSession.log.value.takeLast(4).joinToString("\n")
                }
}

private suspend fun LocalCameraActivity.previewAllowed(request: Int): Boolean {
                val snapshot = kotlinx.coroutines.withTimeoutOrNull(2500) { vehicle.readSnapshot() }
                if (request != generation || !resumed) return false
                if (!localRecordingAllowed(snapshot?.gear, snapshot?.speed, driving)) {
                    status.text = "车辆状态不可读，或不满足当前预览安全条件"; return false
                }

    return true
}

private fun LocalCameraActivity.previewSurfacesReady(): Boolean {
        if (!resumed || opening || open) return false
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.CAMERA); return false
        }
        if (dual && secondSurface == null) { status.text = "双路 Surface 尚未就绪"; return false }

    return true
}

internal const val MANUAL_CAMERA_OWNER = "manual-camera"
