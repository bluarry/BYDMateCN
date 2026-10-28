package com.bydmatecn.app.local
import org.junit.Assert.*
import org.junit.Test
class AudioPlaybackPolicyTest {
    @Test fun transientLossBeforePreparationMustNotStart() {
        val p = AudioPlaybackPolicy(); p.begin(); p.transientLoss()
        assertFalse(p.mayPlay); p.gain(); assertTrue(p.mayPlay)
    }
    @Test fun userPauseSurvivesGain() {
        val p = AudioPlaybackPolicy(); p.begin(); p.transientLoss(); p.pause(); p.gain()
        assertFalse(p.mayPlay); p.resume(); assertTrue(p.mayPlay)
    }
    @Test fun resumeDoesNotStealLostFocus() {
        val p = AudioPlaybackPolicy(); p.begin(); p.pause(); p.transientLoss(); p.resume()
        assertFalse(p.mayPlay)
    }
    @Test fun volumeRemainsDuckedWhenSliderChanges() {
        val p = AudioPlaybackPolicy(); p.begin(); p.duck()
        assertEquals(.16f, p.effectiveVolume(80), .0001f)
        p.gain(); assertEquals(.8f, p.effectiveVolume(80), .0001f)
    }
    @Test fun stopResetsIntentAndFocus() {
        val p = AudioPlaybackPolicy(); p.begin(); p.duck(); p.stop(); p.gain()
        assertFalse(p.mayPlay); assertFalse(p.ducked)
    }
}
