package com.bydmatecn.app.local

import org.junit.Assert.*
import org.junit.Test

class FirmwareAudioRouteTest {
    @Test fun contextsMatchFirmware() {
        assertEquals(11002, FirmwareAudioRoute.EXTERIOR_VOICE.contextId)
        assertEquals(11003, FirmwareAudioRoute.LOCK_PREVIEW.contextId)
        assertEquals(11001, FirmwareAudioRoute.LOCK_EVENT.contextId)
    }
    @Test fun gateFailsClosed() {
        assertTrue(firmwareAudioAllowed(1, 0, true))
        assertFalse(firmwareAudioAllowed(null, 0, true))
        assertFalse(firmwareAudioAllowed(1, null, true))
        assertFalse(firmwareAudioAllowed(1, 1, true))
        assertFalse(firmwareAudioAllowed(2, 0, true))
        assertFalse(firmwareAudioAllowed(1, 0, false))
    }
}
