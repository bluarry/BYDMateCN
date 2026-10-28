package com.bydmatecn.app.local
import org.junit.Assert.*
import org.junit.Test
class LockSoundEdgeTest {
    @Test fun drivingGateDoesNotPermitReverse() {
        assertTrue(localRecordingAllowed(4, 30, true, false, true))
        assertFalse(localRecordingAllowed(4, 30, true, false, false))
        assertFalse(localRecordingAllowed(2, 0, true, false, true))
        assertFalse(localRecordingAllowed(4, null, true, false, true))
        assertFalse(localRecordingAllowed(4, 30, true, true, true))
    }
    @Test fun baselineNeverPlays() { val e=LockSoundEdge(); assertNull(e.sample(2,true,0)); assertNull(e.sample(2,true,500)) }
    @Test fun stableTransitionFiresOnce() { val e=LockSoundEdge(); e.sample(1,true,0); e.sample(1,true,500); assertNull(e.sample(2,true,1000)); assertEquals(2,e.sample(2,true,1500)); assertNull(e.sample(2,true,2000)) }
    @Test fun bouncingDoesNotFire() { val e=LockSoundEdge(); e.sample(1,true,0); e.sample(1,true,500); e.sample(2,true,600); assertNull(e.sample(1,true,700)); assertNull(e.sample(1,true,1300)) }
    @Test fun unsafeResetsBaseline() { val e=LockSoundEdge(); e.sample(1,true,0); e.sample(1,true,500); e.sample(null,false,1000); e.sample(2,true,1500); assertNull(e.sample(2,true,2000)) }
    @Test fun cooldownSuppressesFastUnlock() { val e=LockSoundEdge(); e.sample(1,true,0); e.sample(1,true,500); e.sample(2,true,1000); e.sample(2,true,1500); e.sample(1,true,2000); assertNull(e.sample(1,true,2500)) }
    @Test fun readGapCannotBecomeEvent() { val e=LockSoundEdge(); e.sample(1,true,0); e.sample(1,true,500); assertNull(e.sample(2,true,7000)); e.sample(2,true,7500); assertNull(e.sample(2,true,8000)) }
}
