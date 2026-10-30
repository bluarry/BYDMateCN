package com.bydmatecn.app.local

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCatalogTest {
    @Test fun recorderAndDiagnosticsHaveRealRoutes() {
        assertEquals("camera", localTools.single { it.id == "recorder" }.route)
        assertEquals("native:diagnostics", localTools.single { it.id == "diagnostics" }.route)
        assertEquals(localTools.size, localTools.map { it.id }.distinct().size)
        org.junit.Assert.assertTrue(localTools.all { it.accent in 0..3 })
    }
    @Test fun noPlaceholderTools() {
        org.junit.Assert.assertTrue(localTools.none {
            it.id in setOf(
                "front", "traffic", "bluetooth", "bluetooth_unlock", "touch",
                "advanced", "wheel", "status", "guard", "sentry",
            )
        })
    }
    @Test fun telephonyIsNotAMediaSink() {
        org.junit.Assert.assertFalse(ordinaryAudioOutput(18))
        org.junit.Assert.assertFalse(ordinaryAudioOutput(1))
        org.junit.Assert.assertTrue(ordinaryAudioOutput(2))
    }
    @Test fun mirrorReusesBlindSpotRoute() {
        assertEquals("blind_spot", localTools.single { it.id == "mirror" }.route)
    }
}
