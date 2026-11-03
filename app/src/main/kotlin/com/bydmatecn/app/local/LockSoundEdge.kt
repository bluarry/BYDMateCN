package com.bydmatecn.app.local

/** Raw body-controller values used to recognise one physical lock/unlock operation. */
data class LockSoundSample(
    val lockFL: Int?,
    val turnSignalFlash: Int?,
    val doorFL: Int?,
    val powerState: Int?,
    val gear: Int? = null,
    val speedKph: Float? = null,
    val motion: VehicleMotionState = VehicleMotionState.UNKNOWN,
)

enum class LockSoundEvent(val rawLock: Int) { UNLOCK(1), LOCK(2) }

data class LockSoundDetection(
    val event: LockSoundEvent,
    val reason: String,
    val suppressed: Boolean = false,
)

/**
 * Recognises body lock operations from a debounced lock edge and the vehicle flash pulse.
 * A short helper outage does not erase the previous lock state: ignition-off commonly makes
 * some FIDs disappear briefly exactly while the body controller performs the lock operation.
 */
class LockSoundEventDetector(
    private val debounceMs: Long = 250,
    private val recoveryWindowMs: Long = 24 * 60 * 60_000L,
    private val sameEventGuardMs: Long = 4_000,
    initialLock: Int? = null,
) {
    private var stableLock: Int? = initialLock?.takeIf { it in 1..2 }
    private var candidateLock: Int? = stableLock
    private var candidateSince = 0L
    private var lastValidAt = -1L
    private var flashBurstActive = false
    private var lastFlashAt = -1L
    private var lastPowerState: Int? = null
    private var ignitionOffUnlockUntil = -1L
    private val lastEventAt = mutableMapOf<LockSoundEvent, Long>()

    fun reset() {
        stableLock = null
        candidateLock = null
        lastValidAt = -1L
        flashBurstActive = false
        lastFlashAt = -1L
        lastPowerState = null
        ignitionOffUnlockUntil = -1L
        lastEventAt.clear()
    }

    fun sample(sample: LockSoundSample, now: Long): LockSoundDetection? {
        observePowerState(sample.powerState, now)
        expireBaseline(now)
        val lock = sample.lockFL?.takeIf { it in 1..2 } ?: return missingLockSample(sample, now)
        lastValidAt = now
        updateFlashBurst(sample.turnSignalFlash, now)
        return flashConfirmation(lock, sample.turnSignalFlash, sample.motion, now) ?: debouncedEdge(lock, sample.motion, now)
    }

    private fun observePowerState(powerState: Int?, now: Long) {
        val current = powerState?.takeIf { it in 0..2 } ?: return
        val previous = lastPowerState
        val ignitionJustTurnedOff = current == POWER_OFF && previous?.let { it != POWER_OFF } == true
        if (ignitionJustTurnedOff && stableLock == LockSoundEvent.LOCK.rawLock) {
            ignitionOffUnlockUntil = now + IGNITION_OFF_UNLOCK_GUARD_MS
        }
        lastPowerState = current
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

    private fun flashConfirmation(
        lock: Int,
        flash: Int?,
        motion: VehicleMotionState,
        now: Long,
    ): LockSoundDetection? {
        if (flash != LOCK_FLASH_RAW || flashBurstActive) return null
        flashBurstActive = true
        val previous = stableLock
        stableLock = lock
        candidateLock = lock
        return when {
            previous != null && previous != lock && lock == LockSoundEvent.UNLOCK.rawLock ->
                emit(lock, now, "flash=9 unlock confirmation")
            previous != null && previous != lock && motion == VehicleMotionState.PARKED ->
                emit(lock, now, "flash=9 parked lock confirmation")
            previous == lock && lock == LockSoundEvent.LOCK.rawLock && motion == VehicleMotionState.PARKED ->
                emit(lock, now, "flash=9 physical-lock confirmation")
            else -> null
        }
    }

    private fun debouncedEdge(lock: Int, motion: VehicleMotionState, now: Long): LockSoundDetection? {
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
        return when {
            lock == LockSoundEvent.UNLOCK.rawLock -> emit(lock, now, "LockFL $previous->$lock")
            motion == VehicleMotionState.PARKED -> emit(lock, now, "LockFL $previous->$lock; parked")
            else -> null // A moving/unknown lock edge is usually speed auto-lock; wait for parked flash=9.
        }
    }
    private fun emit(lock: Int, now: Long, reason: String): LockSoundDetection? {
        val event = if (lock == 2) LockSoundEvent.LOCK else LockSoundEvent.UNLOCK
        if (event == LockSoundEvent.LOCK) {
            // A deliberate lock inside the guard window proves the following unlock is a new action.
            ignitionOffUnlockUntil = -1L
        } else if (ignitionOffUnlockUntil >= 0 && now <= ignitionOffUnlockUntil) {
            ignitionOffUnlockUntil = -1L
            return LockSoundDetection(
                event,
                "$reason; ignition-off automatic unlock within ${IGNITION_OFF_UNLOCK_GUARD_MS}ms",
                suppressed = true,
            )
        } else if (ignitionOffUnlockUntil >= 0) {
            ignitionOffUnlockUntil = -1L
        }
        val previousAt = lastEventAt[event]
        if (previousAt != null && now >= previousAt && now - previousAt < sameEventGuardMs) return null
        lastEventAt[event] = now
        return LockSoundDetection(event, reason)
    }

    private companion object {
        const val LOCK_FLASH_RAW = 9
        const val FLASH_BURST_GAP_MS = 2_000L
        const val POWER_OFF = 0
        const val IGNITION_OFF_UNLOCK_GUARD_MS = 2_000L
    }
}

/** Compatibility adapter retained for existing callers and focused edge tests. */
class LockSoundEdge(private val debounceMs: Long = 250) {
    private val detector = LockSoundEventDetector(debounceMs = debounceMs)
    fun sample(lock: Int?, safe: Boolean, now: Long): Int? {
        if (!safe) { detector.reset(); return null }
        return detector.sample(
            LockSoundSample(lock, null, null, null, motion = VehicleMotionState.PARKED),
            now,
        )?.event?.rawLock
    }
}
