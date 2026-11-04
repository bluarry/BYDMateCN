package com.bydmatecn.app.local

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface

/**
 * Direct AVM frame sink used by the background recorder.
 *
 * The vendor source writes YUV frames into an [ImageReader], avoiding an invisible overlay and
 * TextureView.getBitmap() polling. Frames are converted directly to the encoder size instead of
 * first allocating a full-size bitmap. Every acquired Image is closed on the callback thread.
 */
@Suppress("LongParameterList") // Immutable geometry and callbacks describe one vendor ImageReader pipeline.
internal class AvmImageReaderFrameSource(
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val targetFps: Int,
    private val stripIndex: Int,
    private val horizontalFlip: Boolean,
    private val note: (String) -> Unit,
    private val onFrame: (Long) -> Unit = {},
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(outputWidth > 0 && outputHeight > 0)
        require(targetFps > 0)
        require(stripIndex in 0 until PANORAMA_STRIP_COUNT)
    }

    private val lock = Any()
    private var thread: HandlerThread? = null
    private var reader: ImageReader? = null
    private var latest: Bitmap? = null
    // Callback-thread scratch storage. Bitmap.createBitmap copies this array, so one allocation can
    // be reused for every YUV frame without exposing mutable pixels to the encoder thread.
    private val conversionPixels = IntArray(outputWidth * outputHeight)
    private var generation = 0L
    private var lastAcceptedAt = 0L
    private var lastConvertedAt = 0L
    private var frames = 0L
    private var conversionErrors = 0L
    private var totalConversionMs = 0L
    private var peakConversionMs = 0L
    private var lastSlowConversionLogAt = 0L

    val surface: Surface? get() = synchronized(lock) { reader?.surface }
    val lastFrameAt: Long get() = synchronized(lock) { lastConvertedAt }
    val deliveredFrames: Long get() = synchronized(lock) { frames }

    fun start(): Boolean = synchronized(lock) {
        if (reader != null) return true
        generation++
        val currentGeneration = generation
        val cameraThread = HandlerThread("BYDMateCN-AVM-ImageReader").apply { start() }
        val created = runCatching {
            ImageReader.newInstance(sourceWidth, sourceHeight, ImageFormat.YUV_420_888, MAX_IMAGES)
        }.onFailure {
            cameraThread.quitSafely()
            note("ImageReader 创建失败：${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull() ?: return false
        thread = cameraThread
        reader = created
        created.setOnImageAvailableListener(
            { source -> handleAvailable(source, currentGeneration) },
            Handler(cameraThread.looper),
        )
        note(
            "ImageReader 已就绪：${sourceWidth}x$sourceHeight YUV_420_888 -> " +
                "${outputWidth}x$outputHeight; strip=$stripIndex/$PANORAMA_STRIP_COUNT; " +
                "flip=$horizontalFlip; maxImages=$MAX_IMAGES",
        )
        true
    }

    fun copyBitmap(): Bitmap? {
        val copy = try {
            synchronized(lock) {
                val fresh = SystemClock.elapsedRealtime() - lastConvertedAt <= FRAME_FRESH_MS
                if (fresh) latest?.copy(Bitmap.Config.ARGB_8888, false) else null
            }
        } catch (error: OutOfMemoryError) {
            note("ImageReader 复制帧内存不足：${error.message}")
            null
        } catch (error: IllegalStateException) {
            note("ImageReader 复制帧失败：${error.message}")
            null
        }
        return copy
    }

    fun stop() {
        stopAndAwait(0L)
    }

    /**
     * Detaches the listener, closes the reader and optionally waits for its callback thread.
     *
     * A direction/source switch must not leave an old ImageReader callback racing the replacement.
     * Waiting is bounded, and a callback can safely stop its own source without joining itself.
     */
    fun stopAndAwait(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0L)
        val (oldReader, oldThread, oldBitmap) = synchronized(lock) {
            generation++
            val resources = Triple(reader, thread, latest)
            reader = null
            thread = null
            latest = null
            lastAcceptedAt = 0L
            lastConvertedAt = 0L
            frames = 0L
            totalConversionMs = 0L
            peakConversionMs = 0L
            lastSlowConversionLogAt = 0L
            resources
        }
        runCatching { oldReader?.setOnImageAvailableListener(null, null) }
        runCatching { oldReader?.close() }
        oldBitmap?.recycle()
        oldThread?.quitSafely()
        if (oldThread == null || oldThread === Thread.currentThread() || timeoutMs == 0L) {
            return oldThread?.isAlive != true
        }
        try {
            oldThread.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val stopped = !oldThread.isAlive
        if (!stopped) note("ImageReader 释放等待超时：${timeoutMs}ms；旧回调将由 generation 隔离")
        return stopped
    }

    @Suppress("TooGenericExceptionCaught") // Vendor Image/ImageReader implementations throw several RuntimeException subtypes.
    private fun handleAvailable(source: ImageReader, callbackGeneration: Long) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (!shouldConvert(source, callbackGeneration, now)) return
            val conversionStartedAt = SystemClock.elapsedRealtime()
            val bitmap = yuv420ToBitmap(
                image,
                outputWidth,
                outputHeight,
                stripIndex,
                PANORAMA_STRIP_COUNT,
                horizontalFlip,
                conversionPixels,
            )
            val completedAt = SystemClock.elapsedRealtime()
            val conversionMs = completedAt - conversionStartedAt
            val acceptedFrame = synchronized(lock) {
                if (callbackGeneration != generation || reader !== source) {
                    0L
                } else {
                    latest?.recycle()
                    latest = bitmap
                    lastConvertedAt = completedAt
                    frames++
                    totalConversionMs += conversionMs
                    peakConversionMs = maxOf(peakConversionMs, conversionMs)
                    frames
                }
            }
            if (acceptedFrame == 0L) {
                bitmap.recycle()
                return
            }
            onFrame(completedAt)
            if (acceptedFrame == 1L) logFirstFrame(image)
            logConversionPerformance(acceptedFrame, conversionMs, completedAt)
        } catch (error: RuntimeException) {
            logConversionError(error)
        } catch (error: OutOfMemoryError) {
            logConversionError(error)
        } finally {
            image.close()
        }
    }

    private fun shouldConvert(source: ImageReader, callbackGeneration: Long, now: Long): Boolean =
        synchronized(lock) {
            val current = callbackGeneration == generation && reader === source
            val due = now - lastAcceptedAt >= 1_000L / targetFps
            if (current && due) lastAcceptedAt = now
            current && due
        }

    private fun logFirstFrame(image: Image) {
        note(
            "ImageReader 收到首帧：${image.width}x${image.height}; " +
                "planes=${image.planes.joinToString { "${it.rowStride}/${it.pixelStride}" }}",
        )
    }

    private fun logConversionPerformance(frame: Long, conversionMs: Long, now: Long) {
        val frameIntervalMs = (1_000L / targetFps).coerceAtLeast(1L)
        val snapshot = synchronized(lock) {
            val periodic = frame % PERFORMANCE_LOG_INTERVAL_FRAMES == 0L
            val slow = conversionMs > frameIntervalMs &&
                now - lastSlowConversionLogAt >= SLOW_LOG_INTERVAL_MS
            if (!periodic && !slow) return
            if (slow) lastSlowConversionLogAt = now
            Triple(totalConversionMs / frame.coerceAtLeast(1L), peakConversionMs, slow)
        }
        note(
            "ImageReader 转换性能：frame=$frame current=${conversionMs}ms " +
                "avg=${snapshot.first}ms peak=${snapshot.second}ms budget=${frameIntervalMs}ms " +
                "slow=${snapshot.third}",
        )
    }

    private fun logConversionError(error: Throwable) {
        val count = synchronized(lock) { ++conversionErrors }
        if (count == 1L || count % ERROR_LOG_INTERVAL == 0L) {
            note("ImageReader 取帧失败#$count：${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private companion object {
        const val MAX_IMAGES = 4
        const val PANORAMA_STRIP_COUNT = 4
        const val FRAME_FRESH_MS = 1_500L
        const val ERROR_LOG_INTERVAL = 30L
        const val PERFORMANCE_LOG_INTERVAL_FRAMES = 300L
        const val SLOW_LOG_INTERVAL_MS = 30_000L
    }
}

/** Converts one AVM panorama strip and scales it while respecting vendor row/pixel strides. */
@Suppress("LongParameterList") // Conversion geometry is explicit to avoid per-frame wrapper allocations.
private fun yuv420ToBitmap(
    image: Image,
    outputWidth: Int,
    outputHeight: Int,
    stripIndex: Int,
    stripCount: Int,
    horizontalFlip: Boolean,
    pixels: IntArray = IntArray(outputWidth * outputHeight),
): Bitmap {
    require(image.format == ImageFormat.YUV_420_888 && image.planes.size >= 3) {
        "不支持的 AVM 图像格式：${image.format}; planes=${image.planes.size}"
    }
    require(stripCount > 0 && stripIndex in 0 until stripCount)
    require(pixels.size >= outputWidth * outputHeight) {
        "AVM 像素缓冲区不足：${pixels.size} < ${outputWidth * outputHeight}"
    }
    val sourceCrop = image.cropRect
    require(sourceCrop.width() >= stripCount && sourceCrop.height() > 0) {
        "AVM 图像裁剪区域无效：$sourceCrop; strips=$stripCount"
    }
    val strip = panoramaStripBounds(sourceCrop.left, sourceCrop.width(), stripIndex, stripCount)
    val stripLeft = strip.left
    val stripRight = strip.rightExclusive
    val stripWidth = strip.width
    val maxSourceX = stripRight - 1
    val maxSourceY = sourceCrop.bottom - 1
    val ySampler = PlaneSampler.create(image.planes[0], maxSourceX, maxSourceY)
    val uSampler = PlaneSampler.create(image.planes[1], maxSourceX / 2, maxSourceY / 2)
    val vSampler = PlaneSampler.create(image.planes[2], maxSourceX / 2, maxSourceY / 2)
    var output = 0
    for (row in 0 until outputHeight) {
        val sourceY = sourceCrop.top + row * sourceCrop.height() / outputHeight
        val chromaY = sourceY / 2
        for (column in 0 until outputWidth) {
            val sourceX = sourceColumn(stripLeft, stripWidth, column, outputWidth, horizontalFlip)
            val chromaX = sourceX / 2
            val y = ySampler.sample(sourceX, sourceY)
            val u = uSampler.sample(chromaX, chromaY)
            val v = vSampler.sample(chromaX, chromaY)
            pixels[output++] = yuvToArgb(y, u, v)
        }
    }
    return Bitmap.createBitmap(pixels, outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
}

/** Bounds are validated once per frame instead of once for every Y/U/V sample. */
private class PlaneSampler private constructor(
    private val plane: Image.Plane,
    private val base: Int,
) {
    fun sample(x: Int, y: Int): Int =
        plane.buffer.get(base + y * plane.rowStride + x * plane.pixelStride).toInt() and 0xff

    companion object {
        fun create(plane: Image.Plane, maxX: Int, maxY: Int): PlaneSampler {
            require(maxX >= 0 && maxY >= 0)
            val base = plane.buffer.position()
            val last = base + maxY * plane.rowStride + maxX * plane.pixelStride
            require(last in base until plane.buffer.limit()) {
                "AVM plane 越界：last=$last limit=${plane.buffer.limit()} " +
                    "size=${maxX + 1}x${maxY + 1} stride=${plane.rowStride}/${plane.pixelStride}"
            }
            return PlaneSampler(plane, base)
        }
    }
}

private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
    val c = (y - 16).coerceAtLeast(0)
    val d = u - 128
    val e = v - 128
    val red = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
    val green = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
    val blue = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
    return 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
}


internal data class PanoramaStripBounds(val left: Int, val rightExclusive: Int) {
    init { require(left >= 0 && rightExclusive > left) }
    val width: Int get() = rightExclusive - left
}

/** Equal-width panorama tiles; the final tile absorbs a non-divisible remainder without overflow. */
internal fun panoramaStripBounds(
    cropLeft: Int,
    cropWidth: Int,
    stripIndex: Int,
    stripCount: Int,
): PanoramaStripBounds {
    require(cropLeft >= 0 && cropWidth >= stripCount && stripCount > 0)
    require(stripIndex in 0 until stripCount)
    return PanoramaStripBounds(
        left = cropLeft + cropWidth * stripIndex / stripCount,
        rightExclusive = cropLeft + cropWidth * (stripIndex + 1) / stripCount,
    )
}

internal fun sourceColumn(
    cropLeft: Int,
    cropWidth: Int,
    outputColumn: Int,
    outputWidth: Int,
    horizontalFlip: Boolean,
): Int {
    require(cropWidth > 0 && outputWidth > 0 && outputColumn in 0 until outputWidth)
    val sampledColumn = if (horizontalFlip) outputWidth - 1 - outputColumn else outputColumn
    return cropLeft + sampledColumn * cropWidth / outputWidth
}
