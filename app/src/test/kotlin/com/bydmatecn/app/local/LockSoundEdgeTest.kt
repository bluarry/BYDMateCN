package com.bydmatecn.app.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockSoundEdgeTest {
    @Test fun drivingGateDoesNotPermitReverse() {
        assertTrue(localRecordingAllowed(4, 30, true, false, true))
        assertFalse(localRecordingAllowed(4, 30, true, false, false))
        assertFalse(localRecordingAllowed(2, 0, true, false, true))
        assertFalse(localRecordingAllowed(4, null, true, false, true))
        assertFalse(localRecordingAllowed(4, 30, true, true, true))
    }

    @Test fun baselineNeverPlays() {
        val edge = LockSoundEdge()
        assertNull(edge.sample(2, true, 0))
        assertNull(edge.sample(2, true, 500))
    }

    @Test fun stableTransitionFiresOnce() {
        val edge = LockSoundEdge()
        edge.sample(1, true, 0)
        edge.sample(1, true, 250)
        assertNull(edge.sample(2, true, 500))
        assertEquals(2, edge.sample(2, true, 750))
        assertNull(edge.sample(2, true, 2_000))
    }

    @Test fun bouncingDoesNotFire() {
        val edge = LockSoundEdge()
        edge.sample(1, true, 0)
        edge.sample(1, true, 250)
        edge.sample(2, true, 300)
        assertNull(edge.sample(1, true, 350))
        assertNull(edge.sample(1, true, 650))
    }

    @Test fun unsafeResetsBaseline() {
        val edge = LockSoundEdge()
        edge.sample(1, true, 0)
        edge.sample(1, true, 250)
        edge.sample(null, false, 500)
        edge.sample(2, true, 750)
        assertNull(edge.sample(2, true, 1_000))
    }

    @Test fun immediateOppositeTransitionIsNotSuppressed() {
        val edge = LockSoundEdge()
        edge.sample(1, true, 0)
        edge.sample(1, true, 250)
        edge.sample(2, true, 500)
        assertEquals(2, edge.sample(2, true, 750))
        edge.sample(1, true, 800)
        assertEquals(1, edge.sample(1, true, 1_050))
    }

    @Test fun helperGapPreservesBaselineForLaterUnlock() {
        val detector = LockSoundEventDetector()
        detector.sample(LockSoundSample(2, null, null, null), 0)
        detector.sample(LockSoundSample(2, null, null, null), 250)
        detector.sample(LockSoundSample(null, null, null, null), 60 * 60_000L)
        detector.sample(LockSoundSample(1, null, null, null), 60 * 60_000L + 250)
        assertEquals(
            LockSoundEvent.UNLOCK,
            detector.sample(LockSoundSample(1, null, null, null), 60 * 60_000L + 500)?.event,
        )
    }

    @Test fun parkedFlashConfirmsPhysicalLockAfterDrivingAutoLock() {
        val detector = LockSoundEventDetector()
        detector.sample(LockSoundSample(1, null, null, 2, 4, 30f, VehicleMotionState.MOVING), 0)
        detector.sample(LockSoundSample(1, null, null, 2, 4, 30f, VehicleMotionState.MOVING), 250)
        detector.sample(LockSoundSample(2, null, null, 2, 4, 30f, VehicleMotionState.MOVING), 500)
        assertNull(detector.sample(LockSoundSample(2, null, null, 2, 4, 30f, VehicleMotionState.MOVING), 750))
        assertEquals(
            LockSoundEvent.LOCK,
            detector.sample(LockSoundSample(2, 9, null, 0, 1, 0f, VehicleMotionState.PARKED), 10_000)?.event,
        )
    }

    @Test fun flashConfirmsLockWithoutWaitingForDebounce() {
        val detector = LockSoundEventDetector()
        detector.sample(LockSoundSample(1, null, 0, 0), 0)
        detector.sample(LockSoundSample(1, null, 0, 0), 250)
        val result = detector.sample(
            LockSoundSample(2, 9, 0, 0, motion = VehicleMotionState.PARKED),
            500,
        )
        assertEquals(LockSoundEvent.LOCK, result?.event)
    }

    @Test fun lockThenImmediateUnlockBothFire() {
        val detector = LockSoundEventDetector()
        detector.sample(LockSoundSample(1, null, 0, 0), 0)
        detector.sample(LockSoundSample(1, null, 0, 0), 250)
        detector.sample(LockSoundSample(2, null, 0, 0, motion = VehicleMotionState.PARKED), 500)
        assertEquals(
            LockSoundEvent.LOCK,
            detector.sample(
                LockSoundSample(2, null, 0, 0, motion = VehicleMotionState.PARKED),
                750,
            )?.event,
        )
        detector.sample(LockSoundSample(1, null, 0, 0), 800)
        assertEquals(LockSoundEvent.UNLOCK, detector.sample(LockSoundSample(1, null, 0, 0), 1_050)?.event)
    }

    @Test fun shortHelperGapStillAllowsUnlockRecovery() {
        val detector = LockSoundEventDetector()
        detector.sample(LockSoundSample(2, null, 0, 0), 0)
        detector.sample(LockSoundSample(2, null, 0, 0), 250)
        detector.sample(LockSoundSample(null, null, null, null), 1_000)
        detector.sample(LockSoundSample(1, null, null, 1), 2_000)
        assertEquals(
            LockSoundEvent.UNLOCK,
            detector.sample(LockSoundSample(1, null, null, 1), 2_250)?.event,
        )
    }

    @Test fun movingAutoLockNeverEmits() {
        val detector = LockSoundEventDetector()
        detector.sample(LockSoundSample(1, null, null, 2, 4, 30f, VehicleMotionState.MOVING), 0)
        detector.sample(LockSoundSample(1, null, null, 2, 4, 30f, VehicleMotionState.MOVING), 250)
        detector.sample(LockSoundSample(2, null, null, 2, 4, 30f, VehicleMotionState.MOVING), 500)
        assertNull(detector.sample(LockSoundSample(2, null, null, 2, 4, 30f, VehicleMotionState.MOVING), 750))
    }

    @Test fun unknownLockEdgeWaitsForPhysicalLockFlash() {
        val detector = LockSoundEventDetector(initialLock = 1)
        detector.sample(LockSoundSample(2, null, null, null, motion = VehicleMotionState.UNKNOWN), 0)
        assertNull(detector.sample(LockSoundSample(2, null, null, null, motion = VehicleMotionState.UNKNOWN), 250))
        assertEquals(
            LockSoundEvent.LOCK,
            detector.sample(LockSoundSample(2, 9, null, 0, 1, 0f, VehicleMotionState.PARKED), 1_000)?.event,
        )
    }

    @Test fun persistedLockedBaselineDetectsUnlockAfterServiceRestart() {
        val detector = LockSoundEventDetector(initialLock = 2)
        detector.sample(LockSoundSample(1, null, null, null), 0)
        assertEquals(
            LockSoundEvent.UNLOCK,
            detector.sample(LockSoundSample(1, null, null, null), 250)?.event,
        )
    }
    @Test fun ignitionOffAutomaticUnlockIsSuppressedForTwoSeconds() {
        val detector = LockSoundEventDetector(initialLock = 2)
        detector.sample(LockSoundSample(2, null, 0, 2, motion = VehicleMotionState.PARKED), 0)
        detector.sample(LockSoundSample(1, null, 0, 0, motion = VehicleMotionState.PARKED), 250)
        val result = detector.sample(
            LockSoundSample(1, null, 0, 0, motion = VehicleMotionState.PARKED),
            500,
        )
        assertEquals(LockSoundEvent.UNLOCK, result?.event)
        assertTrue(result?.suppressed == true)
    }

    @Test fun ignitionOffFlashUnlockIsAlsoSuppressed() {
        val detector = LockSoundEventDetector(initialLock = 2)
        detector.sample(LockSoundSample(2, null, 0, 2, motion = VehicleMotionState.PARKED), 0)
        val result = detector.sample(
            LockSoundSample(1, 9, 0, 0, motion = VehicleMotionState.PARKED),
            250,
        )
        assertEquals(LockSoundEvent.UNLOCK, result?.event)
        assertTrue(result?.suppressed == true)
    }

    @Test fun unlockAfterIgnitionOffGuardStillPlays() {
        val detector = LockSoundEventDetector(initialLock = 2)
        detector.sample(LockSoundSample(2, null, 0, 2, motion = VehicleMotionState.PARKED), 0)
        detector.sample(LockSoundSample(2, null, 0, 0, motion = VehicleMotionState.PARKED), 100)
        detector.sample(LockSoundSample(1, null, 0, 0, motion = VehicleMotionState.PARKED), 2_500)
        val result = detector.sample(
            LockSoundSample(1, null, 0, 0, motion = VehicleMotionState.PARKED),
            2_750,
        )
        assertEquals(LockSoundEvent.UNLOCK, result?.event)
        assertFalse(result?.suppressed == true)
    }

    @Test fun lockInsideIgnitionOffGuardAllowsFollowingUnlock() {
        val detector = LockSoundEventDetector(initialLock = 2)
        detector.sample(LockSoundSample(2, null, 0, 2, motion = VehicleMotionState.PARKED), 0)
        detector.sample(LockSoundSample(1, null, 0, 0, motion = VehicleMotionState.PARKED), 100)
        val automaticUnlock = detector.sample(
            LockSoundSample(1, null, 0, 0, motion = VehicleMotionState.PARKED),
            350,
        )
        assertTrue(automaticUnlock?.suppressed == true)

        detector.sample(LockSoundSample(2, null, 0, 0, motion = VehicleMotionState.PARKED), 500)
        assertEquals(
            LockSoundEvent.LOCK,
            detector.sample(LockSoundSample(2, null, 0, 0, motion = VehicleMotionState.PARKED), 750)?.event,
        )
        detector.sample(LockSoundSample(1, null, 0, 0, motion = VehicleMotionState.PARKED), 800)
        val realUnlock = detector.sample(
            LockSoundSample(1, null, 0, 0, motion = VehicleMotionState.PARKED),
            1_050,
        )
        assertEquals(LockSoundEvent.UNLOCK, realUnlock?.event)
        assertFalse(realUnlock?.suppressed == true)
    }

    @Test fun unlockWhileVehiclePowerIsOnIsSuppressed() {
        val detector = LockSoundEventDetector(initialLock = 2)
        detector.sample(LockSoundSample(1, null, 0, 2, motion = VehicleMotionState.PARKED), 0)
        val result = detector.sample(
            LockSoundSample(1, null, 0, 2, motion = VehicleMotionState.PARKED),
            250,
        )
        assertEquals(LockSoundEvent.UNLOCK, result?.event)
        assertTrue(result?.suppressed == true)
        assertTrue(result?.reason?.contains("powerState=0") == true)
    }

    @Test fun unlockWhileVehiclePowerIsOffIsAllowed() {
        val detector = LockSoundEventDetector(initialLock = 2)
        detector.sample(LockSoundSample(1, null, 0, 0, motion = VehicleMotionState.PARKED), 0)
        val result = detector.sample(
            LockSoundSample(1, null, 0, 0, motion = VehicleMotionState.PARKED),
            250,
        )
        assertEquals(LockSoundEvent.UNLOCK, result?.event)
        assertFalse(result?.suppressed == true)
    }

}
