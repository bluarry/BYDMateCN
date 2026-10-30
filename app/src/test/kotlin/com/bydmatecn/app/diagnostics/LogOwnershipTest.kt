package com.bydmatecn.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogOwnershipTest {
    @Test fun isolatesOriginalAppAndRetainsOwnHelper() {
        val filter = LogOwnership(27720, "com.bydmatecn.app")
        assertFalse(filter.keep("10-29 21:00:00.100 D/TrackingService( 7288): original app"))
        assertTrue(filter.keep("10-29 21:00:00.100 D/LocalAudio(27720): own app"))
        assertFalse(filter.keep("10-29 21:00:00.100 D/bydmate_helper(30100): original helper"))
        assertTrue(filter.keep("10-29 21:00:00.100 I/bydmatecn_helper(30101): ready"))
        assertTrue(filter.keep("10-29 21:00:00.101 D/HelperBinderRx(30101): own helper"))
        assertFalse(filter.keep("10-29 21:00:00.101 D/HelperBinderRx(30100): original helper"))
    }
    @Test fun systemMessagesMustNameExactPackage() {
        val filter = LogOwnership(10, "com.bydmatecn.app")
        assertTrue(filter.keep("10-29 21:00:00.100 W/ActivityManager( 1000): denied com.bydmatecn.app"))
        assertFalse(filter.keep("10-29 21:00:00.100 W/ActivityManager( 1000): denied com.bydmate.app"))
        assertFalse(filter.keep("10-29 21:00:00.100 W/ActivityManager( 1000): denied com.bydmatecn.appfake"))
        assertTrue(filter.keep("--------- beginning of main"))
    }
}
