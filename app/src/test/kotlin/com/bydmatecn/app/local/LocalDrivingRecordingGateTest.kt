package com.bydmatecn.app.local
import org.junit.Assert.*
import org.junit.Test
class LocalDrivingRecordingGateTest {
    @Test fun requiresExplicitOptInForDrive() {
        assertFalse(localRecordingAllowed(4, 30, true, false, false))
        assertTrue(localRecordingAllowed(4, 30, true, false, true))
    }
    @Test fun alwaysRejectsReverseNeutralUnknownAndNegative() {
        for (gear in listOf(null, 2, 3)) assertFalse(localRecordingAllowed(gear, 0, true, false, true))
        assertFalse(localRecordingAllowed(4, null, true, false, true))
        assertFalse(localRecordingAllowed(4, -1, true, false, true))
    }
    @Test fun factoryCameraAndPermissionAlwaysWin() {
        assertFalse(localRecordingAllowed(4, 10, true, true, true))
        assertFalse(localRecordingAllowed(4, 10, false, false, true))
        assertTrue(localRecordingAllowed(1, 0, true, false, false))
        assertFalse(localRecordingAllowed(1, 1, true, false, true))
    }
}
