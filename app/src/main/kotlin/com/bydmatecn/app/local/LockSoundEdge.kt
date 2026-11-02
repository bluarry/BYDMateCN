package com.bydmatecn.app.local

/** Raw body-controller values used to recognise one physical lock/unlock operation. */
data class LockSoundSample(
    val lockFL: Int?,
    val turnSignalFlash: Int?,
    val doorFL: Int?,
    val powerState: Int?,
)

enum class LockSoundEvent(val rawLock: Int) { UNLOCK(1), LOCK(2) }

data class LockSoundDetection(val event: LockSoundEvent, val reason: String)

/**
 * Recognises body lock operations from a debounced lock edge and the vehicle flash pulse.
 * A short helper outage does not erase the previous lock state: ignition-off commonly makes
 * some FIDs disappear briefly exactly while the body controller performs the lock operation.
 */
class LockSoundEventDetector(
    private val debounceMs: Long = 250,
    private val recoveryWindowMs: Long = 3_000,
    private val sameEventGuardMs: Long = 4_000,
) {
    private var stableLock: Int? = null
    private var candidateLock: Int? = null
    private var candidateSince = 0L
    private var lastValidAt = -1L
    private var flashBurstActive = false
    private var lastFlashAt = -1L
    private val lastEventAt = mutableMapOf<LockSoundEvent, Long>()

    fun reset() {
        stableLock = null
        candidateLock = null
        lastValidAt = -1L
        flashBurstActive = false
        lastFlashAt = -1L
        lastEventAt.clear()
    }

    fun sample(sample: LockSoundSample, now: Long): LockSoundDetection? {
        expireBaseline(now)
        val lock = sample.lockFL?.takeIf { it in 1..2 } ?: return missingLockSample(sample, now)
        lastValidAt = now
        updateFlashBurst(sample.turnSignalFlash, now)
        return flashConfirmation(lock, sample.turnSignalFlash, now) ?: debouncedEdge(lock, now)
    }

    private fun expireBaseline(now: Long) {
        if (lastValidAt < 0 || (now >= lastValidAt && now - lastValidAt <= recoveryWindowMs)) return
        stableLock = null
        candidateLock = null
    }

    private fun missingLockSample(sample: LockSoundSample, now: Long): LockSoundDetection? {
        updateFlashBurst(sample.turnSignalFlash, now)
        return null
    }

    private fun updateFlashBurst(flash: Int?, now: Long) {
        if (flash != LOCK_FLASH_RAW && lastFlashAt >= 0 && now - lastFlashAt > FLASH_BURST_GAP_MS) {
            flashBurstActive = false
        }
        if (flash == LOCK_FLASH_RAW) lastFlashAt = now
    }

    private fun flashConfirmation(lock: Int, flash: Int?, now: Long): LockSoundDetection? {
        if (flash != LOCK_FLASH_RAW || flashBurstActive) return null
        flashBurstActive = true
        val previous = stableLock
        stableLock = lock
        candidateLock = lock
        return if (previous != null && previous != lock) emit(lock, now, "flash=9 confirmation") else null
    }

    private fun debouncedEdge(lock: Int, now: Long): LockSoundDetection? {
        if (candidateLock != lock) {
            candidateLock = lock
            candidateSince = now
            return null
        }
        if (now - candidateSince < debounceMs) return null
        val previous = stableLock
        if (previous == null) {
            stableLock = lock
            return null
        }
        if (previous == lock) return null
        stableLock = lock
        return emit(lock, now, "LockFL $previous->$lock")
    }
    private fun emit(lock: Int, now: Long, reason: String): LockSoundDetection? {
        val event = if (lock == 2) LockSoundEvent.LOCK else LockSoundEvent.UNLOCK
        val previousAt = lastEventAt[event]
        if (previousAt != null && now >= previousAt && now - previousAt < sameEventGuardMs) return null
        lastEventAt[event] = now
        return LockSoundDetection(event, reason)
    }

    private companion object {
        const val LOCK_FLASH_RAW = 9
        const val FLASH_BURST_GAP_MS = 2_000L
    }
}

/** Compatibility adapter retained for existing callers and focused edge tests. */
class LockSoundEdge(private val debounceMs: Long = 250) {
    private val detector = LockSoundEventDetector(debounceMs = debounceMs)
    fun sample(lock: Int?, safe: Boolean, now: Long): Int? {
        if (!safe) { detector.reset(); return null }
        return detector.sample(LockSoundSample(lock, null, null, null), now)?.event?.rawLock
    }
}
