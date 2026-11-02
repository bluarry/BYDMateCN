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

    @Test fun readGapCannotBecomeEvent() {
        val edge = LockSoundEdge()
        edge.sample(1, true, 0)
        edge.sample(1, true, 250)
        assertNull(edge.sample(2, true, 7_000))
        edge.sample(2, true, 7_250)
        assertNull(edge.sample(2, true, 7_500))
    }

    @Test fun flashConfirmsLockWithoutWaitingForDebounce() {
        val detector = LockSoundEventDetector()
        detector.sample(LockSoundSample(1, null, 0, 0), 0)
        detector.sample(LockSoundSample(1, null, 0, 0), 250)
        val result = detector.sample(LockSoundSample(2, 9, 0, 0), 500)
        assertEquals(LockSoundEvent.LOCK, result?.event)
    }

    @Test fun lockThenImmediateUnlockBothFire() {
        val detector = LockSoundEventDetector()
        detector.sample(LockSoundSample(1, null, 0, 0), 0)
        detector.sample(LockSoundSample(1, null, 0, 0), 250)
        detector.sample(LockSoundSample(2, null, 0, 0), 500)
        assertEquals(LockSoundEvent.LOCK, detector.sample(LockSoundSample(2, null, 0, 0), 750)?.event)
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
}
