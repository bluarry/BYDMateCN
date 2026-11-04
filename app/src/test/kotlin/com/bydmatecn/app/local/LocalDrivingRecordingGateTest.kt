package com.bydmatecn.app.local
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class LocalDrivingRecordingGateTest {
    @Test fun requiresExplicitOptInForDrive() {
        assertFalse(localRecordingAllowed(4, 30, false))
        assertTrue(localRecordingAllowed(4, 30, true))
    }
    @Test fun alwaysRejectsReverseNeutralUnknownAndNegative() {
        for (gear in listOf(null, 2, 3)) assertFalse(localRecordingAllowed(gear, 0, true))
        assertFalse(localRecordingAllowed(4, null, true))
        assertFalse(localRecordingAllowed(4, -1, true))
    }
    @Test fun parkingAndDrivingSafetyStillApplyWithoutFactoryCameraExclusion() {
        assertTrue(localRecordingAllowed(1, 0, false))
        assertFalse(localRecordingAllowed(1, 1, true))
        assertFalse(localRecordingAllowed(4, 10, false))
        assertTrue(localRecordingAllowed(4, 10, true))
    }
}
