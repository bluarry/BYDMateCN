// The vendor signatures are what reflection looks up, used or not.
@file:Suppress("UnusedParameter", "FunctionOnlyReturningConstant")

package android.hardware

import android.view.Surface

/**
 * Test stand-in for the vendor camera AvmCameraProbe reaches by reflection: the SDK has no such
 * class, so without this one every open in a unit test fails before it reaches the vendor calls.
 * The companion fields script the next camera; [last] is the one the probe holds.
 */
class AVMCamera(val id: Int) {

    /** Same shape as the vendor callback: the camera, the event type and its two arguments. */
    interface IEventCallback {
        fun onEvent(camera: Any?, type: Int, arg1: Int, arg2: Int)
    }

    private var callback: IEventCallback? = null
    private var bindingCount = 0

    fun setEventCallback(cb: IEventCallback) {
        callback = cb
    }

    fun addPreviewSurface(surface: Surface, index: Int): Boolean {
        calls += "add"
        addedIndexes += index
        if (!bindResult || bindingCount >= maxBindings) return false
        bindingCount++
        activeBindings += index to surface
        return true
    }

    fun setPreviewSurface(surface: Surface, index: Int): Boolean {
        setIndexes += index
        return bindResult
    }

    fun setPreviewSize(width: Int, height: Int): Boolean {
        calls += "size"
        previewSizes += width to height
        return sourceConfigResult
    }

    fun setCameraFps(fps: Int): Boolean {
        calls += "fps"
        cameraFps += fps
        return sourceConfigResult
    }

    fun startPreview(): Boolean {
        calls += "start"
        return startResult
    }

    fun rmPreviewSurface(surface: Surface, index: Int) {
        removedIndexes += index
        removeCallCount++
        if (removeFailures > 0 || removeCallCount in failRemoveCalls) {
            if (removeFailures > 0) removeFailures--
            error("remove refused")
        }
        bindingCount = (bindingCount - 1).coerceAtLeast(0)
        activeBindings.removeAll { it.first == index && it.second === surface }
    }

    fun stopPreview() {
        if (stopFailures > 0) {
            stopFailures--
            error("stop refused")
        }
    }

    fun close() { closeCount++ }

    /** One vendor event, as the camera service would deliver it. */
    fun fire(type: Int, arg1: Int = 0, arg2: Int = 0) {
        callback!!.onEvent(this, type, arg1, arg2)
    }

    companion object {
        var openError: Throwable? = null
        var startResult = true
        var bindResult = true
        var sourceConfigResult = true
        var maxBindings = Int.MAX_VALUE
        var openCount = 0
        var closeCount = 0
        val addedIndexes = mutableListOf<Int>()
        val setIndexes = mutableListOf<Int>()
        val removedIndexes = mutableListOf<Int>()
        val previewSizes = mutableListOf<Pair<Int, Int>>()
        val cameraFps = mutableListOf<Int>()
        val calls = mutableListOf<String>()
        val activeBindings = mutableListOf<Pair<Int, Surface>>()
        val failRemoveCalls = mutableSetOf<Int>()
        var removeCallCount = 0
        /** How many upcoming remove calls throw before one goes through. */
        var removeFailures = 0
        var stopFailures = 0
        var last: AVMCamera? = null

        @JvmStatic
        fun open(id: Int): AVMCamera {
            openError?.let { throw it }
            openCount++
            return AVMCamera(id).also { last = it }
        }

        fun reset() {
            openError = null
            startResult = true
            bindResult = true
            sourceConfigResult = true
            maxBindings = Int.MAX_VALUE
            openCount = 0
            closeCount = 0
            addedIndexes.clear()
            setIndexes.clear()
            removedIndexes.clear()
            previewSizes.clear()
            cameraFps.clear()
            calls.clear()
            activeBindings.clear()
            failRemoveCalls.clear()
            removeCallCount = 0
            removeFailures = 0
            stopFailures = 0
            last = null
        }
    }
}
