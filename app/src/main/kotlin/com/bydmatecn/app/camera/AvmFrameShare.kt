package com.bydmatecn.app.camera

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.TextureView
import java.lang.ref.WeakReference

/**
 * In-process AVM frame hand-off. The blind-spot UI remains the only owner of its vendor camera
 * session; the recorder reads the already-rendered TextureView instead of opening a second AVM
 * handle. Access to [bitmap] must happen on the main thread, as required by TextureView.
 */
object AvmFrameShare {
    private data class Source(
        val view: WeakReference<TextureView>,
        val publishedAt: Long = SystemClock.elapsedRealtime(),
        var lastFrameAt: Long = 0L,
    )

    private val lock = Any()
    private val sources = mutableMapOf<Int, Source>()

    fun publish(index: Int, view: TextureView) = synchronized(lock) {
        sources[index] = Source(WeakReference(view))
    }

    fun frame(index: Int, view: TextureView) = synchronized(lock) {
        val source = sources[index]
        if (source?.view?.get() === view) source.lastFrameAt = SystemClock.elapsedRealtime()
    }

    fun unpublish(index: Int, view: TextureView) = synchronized(lock) {
        if (sources[index]?.view?.get() === view) sources.remove(index)
    }

    fun unpublish(view: TextureView) = synchronized(lock) {
        sources.entries.removeAll { it.value.view.get() === view }
    }

    /** A bound surface blocks a competing open even before its first honest frame arrives. */
    fun hasSource(index: Int): Boolean = synchronized(lock) { availableView(index) != null }

    /** Prefer the configured channel, then any other surface already fed by the same AVM session. */
    fun activeIndex(preferred: Int): Int? = synchronized(lock) {
        if (availableView(preferred) != null) preferred
        else sources.keys.sorted().firstOrNull { availableView(it) != null }
    }

    fun bitmap(index: Int, width: Int, height: Int): Bitmap? {
        val source = synchronized(lock) { sources[index] } ?: return null
        val view = source.view.get() ?: run {
            synchronized(lock) { sources.remove(index) }
            return null
        }
        if (!view.isAvailable || SystemClock.elapsedRealtime() - source.lastFrameAt > FRAME_FRESH_MS) return null
        return runCatching { view.getBitmap(width, height) }.getOrNull()
    }

    private fun availableView(index: Int): TextureView? {
        val view = sources[index]?.view?.get()
        if (view == null) sources.remove(index)
        val source = sources[index] ?: return null
        val now = SystemClock.elapsedRealtime()
        val recent = source.lastFrameAt > 0L && now - source.lastFrameAt <= FRAME_FRESH_MS
        val starting = source.lastFrameAt == 0L && now - source.publishedAt <= START_GRACE_MS
        return view?.takeIf { it.isAvailable && (recent || starting) }
    }

    private const val FRAME_FRESH_MS = 1_500L
    private const val START_GRACE_MS = 3_000L
}
