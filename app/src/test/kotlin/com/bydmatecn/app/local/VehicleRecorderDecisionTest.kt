package com.bydmatecn.app.local

import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleRecorderDecisionTest {
    @Test fun dashcamRequiresDrivePowerAndDriveGear() {
        assertEquals(VehicleRecorderMode.DASHCAM, decide(base.copy(dashcamEnabled = true, powerState = 2, gear = 4, speedKph = 30f, locked = 1)).mode)
        assertEquals(VehicleRecorderMode.OFF, decide(base.copy(dashcamEnabled = true, powerState = 1, gear = 4, speedKph = 30f, locked = 1)).mode)
        assertEquals(VehicleRecorderMode.OFF, decide(base.copy(dashcamEnabled = true, powerState = 2, gear = 1, speedKph = 0f, locked = 1)).mode)
        assertEquals(VehicleRecorderMode.OFF, decide(base.copy(dashcamEnabled = true, powerState = 2, gear = 2, speedKph = 5f, locked = 1)).mode)
    }

    @Test fun sentryRequiresOffParkStoppedAndLocked() {
        assertEquals(VehicleRecorderMode.PARKING_SENTRY, decide(base.copy(sentryEnabled = true, powerState = 0, gear = 1, speedKph = 0f, locked = 2)).mode)
        assertEquals(VehicleRecorderMode.OFF, decide(base.copy(sentryEnabled = true, powerState = 0, gear = 1, speedKph = 0f, locked = 1)).mode)
        assertEquals(VehicleRecorderMode.OFF, decide(base.copy(sentryEnabled = true, powerState = 2, gear = 1, speedKph = 0f, locked = 2)).mode)
        assertEquals(VehicleRecorderMode.OFF, decide(base.copy(sentryEnabled = true, powerState = 0, gear = 1, speedKph = 0f, locked = null)).mode)
    }

    @Test fun unreadableStateStopsRecording() {
        assertEquals(VehicleRecorderMode.OFF, decide(base.copy(dashcamEnabled = true, sentryEnabled = true, powerState = null, gear = 4, speedKph = 30f, locked = 2)).mode)
    }

    private fun decide(input: VehicleRecorderInput) = decideVehicleRecorderMode(input)

    private val base = VehicleRecorderInput(
        dashcamEnabled = false,
        sentryEnabled = false,
        powerState = 0,
        gear = 1,
        speedKph = 0f,
        locked = 1,
    )

}
