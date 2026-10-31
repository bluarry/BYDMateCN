package com.bydmatecn.app.local

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** Worker-confined, silent 640x360/5FPS H.264. Actual Image plane strides are respected. */
class LocalVideoRecorder(private val store: LocalVideoStore) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var pending: File? = null
    private var track = -1
    private var started = false
    private var frames = 0L
    private var originUs = 0L
    private var lastPtsUs = -1L
    val active: Boolean get() = codec != null
    fun start() {
        check(!active)
        val file = store.pending(); pending = file
        var committed = false
        try {
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = encoder
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 700_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            encoder.start(); frames = 0; originUs = android.os.SystemClock.elapsedRealtimeNanos() / 1000; lastPtsUs = -1; track = -1; started = false

            committed = true
        } finally { if (!committed) { abort() } }
    }
    fun frame(bitmap: Bitmap) {
        val encoder = checkNotNull(codec)
        drain(false)
        val index = encoder.dequeueInputBuffer(100_000)
        check(index >= 0) { "编码器未接受画面" }
        val image = encoder.getInputImage(index) ?: error("编码器不支持可读 YUV 输入平面")
        // Preserve aspect ratio (especially dual AVM) instead of stretching into 16:9.
        val scaled = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val scale = minOf(WIDTH.toFloat() / bitmap.width, HEIGHT.toFloat() / bitmap.height)
        val width = bitmap.width * scale; val height = bitmap.height * scale
        android.graphics.Canvas(scaled).apply {
            drawColor(android.graphics.Color.BLACK)
            drawBitmap(bitmap, null, android.graphics.RectF((WIDTH-width)/2, (HEIGHT-height)/2,
                (WIDTH+width)/2, (HEIGHT+height)/2), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        try {
            writeImage(image, scaled)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            image.close()
        }
        val pts = maxOf(lastPtsUs + 1, android.os.SystemClock.elapsedRealtimeNanos() / 1000 - originUs)
        encoder.queueInputBuffer(index, 0, WIDTH * HEIGHT * 3 / 2, pts, 0)
        lastPtsUs = pts
        frames++; drain(false)
    }
    fun finish(eventReason: String? = null): File? {
        if (!active) return null
        var committed = false
        try {
            val encoder = checkNotNull(codec)
            val index = encoder.dequeueInputBuffer(1_000_000)
            check(index >= 0) { "编码器无法收尾" }
            encoder.queueInputBuffer(index, 0, 0, maxOf(0, lastPtsUs + 1), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            check(started && frames > 0) { "没有可保存的视频帧" }
            muxer?.stop(); muxer?.release(); muxer = null
            codec?.stop(); codec?.release(); codec = null
            val file = store.publish(checkNotNull(pending), eventReason); pending = null
            committed = true
            return file
                } finally { if (!committed) { abort() } }
    }
    fun abort() {
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        runCatching { if (started) muxer?.stop() }; runCatching { muxer?.release() }; muxer = null
        pending?.delete(); pending = null; started = false
    }
    private fun drain(end: Boolean) {
        val encoder = checkNotNull(codec)
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (end) 100_000 else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!started); track = checkNotNull(muxer).addTrack(encoder.outputFormat)
                    muxer?.start(); started = true
                }
                index >= 0 -> {
                    writeOutput(encoder, index, info)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER && !end -> return
            }
            if (end) check(System.nanoTime() < deadline) { "编码器收尾超时" }
        }
    }
    private fun writeImage(image: Image, bitmap: Bitmap) {
        check(image.format == android.graphics.ImageFormat.YUV_420_888 && image.planes.size == 3)
        check(image.width >= WIDTH && image.height >= HEIGHT && image.cropRect.left == 0 && image.cropRect.top == 0) {
            "编码器输入裁切不支持：${image.cropRect}"
        }
        val pixels = IntArray(WIDTH * HEIGHT)
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        val planes = image.planes
        val bases = planes.map { it.buffer.position() }
        fun put(plane: Int, x: Int, y: Int, value: Int) {
            val p = planes[plane]
            p.buffer.put(bases[plane] + y * p.rowStride + x * p.pixelStride, value.coerceIn(0, 255).toByte())
        }
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val pixel = pixels[y * WIDTH + x]; val r = pixel shr 16 and 255; val g = pixel shr 8 and 255; val b = pixel and 255
            put(0, x, y, ((66*r + 129*g + 25*b + 128) shr 8) + 16)
            if (x % 2 == 0 && y % 2 == 0) {
                put(1, x/2, y/2, ((-38*r - 74*g + 112*b + 128) shr 8) + 128)
                put(2, x/2, y/2, ((112*r - 94*g - 18*b + 128) shr 8) + 128)
            }
        }
    }
    companion object { const val WIDTH = 640; const val HEIGHT = 360; const val FPS = 5 }
    private fun writeOutput(encoder: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(started)
                            val buffer = checkNotNull(encoder.getOutputBuffer(index))
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer?.writeSampleData(track, buffer, info)
                        }

        } finally { encoder.releaseOutputBuffer(index, false) }
    }
}
