package com.bydmatecn.app.local
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class LocalMediaActionSpecTest {
    @Test fun supportedCommandsOnly() {
        assertTrue(LocalMediaActionSpec.valid("local_camera", "snapshot"))
        assertFalse(LocalMediaActionSpec.valid("local_camera", "open"))
        assertTrue(LocalMediaActionSpec.valid("local_audio", "{\"command\":\"stop\"}"))
        assertTrue(LocalMediaActionSpec.valid("local_audio", "{\"command\":\"exterior\",\"file\":\"audio-test.wav\"}"))
    }
    @Test fun absoluteDirectoryReferencesAreValidated() {
        assertTrue(LocalMediaActionSpec.valid("local_audio", "{\"command\":\"play\",\"file\":\"/storage/emulated/0/Music/解锁.MP3\"}"))
        assertFalse(LocalMediaActionSpec.valid("local_audio", "{\"command\":\"play\",\"file\":\"/Music/../secret.wav\"}"))
    }
    @Test fun rejectsTraversalMissingFileAndBrokenJson() {
        for (value in listOf("", "{}", "{\"command\":\"play\"}",
            "{\"command\":\"play\",\"file\":\"../audio-test.wav\"}",
            "{\"command\":\"play\",\"file\":\"audio-test.exe\"}")) {
            assertFalse(LocalMediaActionSpec.valid("local_audio", value))
        }
        assertFalse(LocalMediaActionSpec.valid("unknown", "snapshot"))
    }
}
