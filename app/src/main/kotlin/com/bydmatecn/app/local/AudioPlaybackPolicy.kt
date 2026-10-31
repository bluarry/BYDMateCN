package com.bydmatecn.app.local

/** Android-independent focus policy; preparation never overrides a focus loss or user pause. */
class AudioPlaybackPolicy {
    var wantsPlayback = false
        private set
    var focusAvailable = false
        private set
    var ducked = false
        private set
    val mayPlay: Boolean get() = wantsPlayback && focusAvailable
    fun begin() { wantsPlayback = true; focusAvailable = true; ducked = false }
    fun pause() { wantsPlayback = false }
    fun resume() { wantsPlayback = true }
    fun transientLoss() { focusAvailable = false; ducked = false }
    fun duck() { ducked = true }
    fun gain() { focusAvailable = true; ducked = false }
    fun stop() { wantsPlayback = false; focusAvailable = false; ducked = false }
    fun effectiveVolume(percent: Int): Float {
        require(percent in 0..100)
        return percent / 100f * if (ducked) .2f else 1f
    }
}
