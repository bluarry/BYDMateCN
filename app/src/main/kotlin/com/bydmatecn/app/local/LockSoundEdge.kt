package com.bydmatecn.app.local

/** A fresh valid baseline is required after every gap. Debounce and cooldown use monotonic time. */
class LockSoundEdge(private val debounceMs: Long = 500, private val cooldownMs: Long = 3000) {
    private var stable: Int? = null
    private var candidate: Int? = null
    private var since = 0L
    private var lastSample = -1L
    private var lastEvent = Long.MIN_VALUE / 2
    fun sample(lock: Int?, safe: Boolean, now: Long): Int? {
        if (!safe || lock !in 1..2 || (lastSample >= 0 && (now < lastSample || now-lastSample > 5000))) {
            stable = null; candidate = null; lastSample = now
            return null
        }
        lastSample = now
        if (candidate != lock) { candidate = lock; since = now; return null }
        if (now-since < debounceMs) return null
        if (stable == null) { stable = lock; return null }
        if (stable == lock) return null
        stable = lock
        if (now-lastEvent < cooldownMs) return null
        lastEvent = now
        return lock
    }
}
