package com.bydmatecn.app.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderEventProtectionTest {
    @Test fun postRollIsConsumedOnlyOnce() {
        val state = RecorderEventProtection()
        state.armPostRoll("驻车震动事件")
        assertTrue(state.hasPostRoll())
        assertEquals("驻车震动事件", state.consumePostRoll())
        assertFalse(state.hasPostRoll())
        assertEquals(null, state.consumePostRoll())
    }

    @Test fun newerEventReplacesPendingPostRollReason() {
        val state = RecorderEventProtection()
        state.armPostRoll("开门")
        state.armPostRoll("震动")
        assertEquals("震动", state.consumePostRoll())
    }
}
