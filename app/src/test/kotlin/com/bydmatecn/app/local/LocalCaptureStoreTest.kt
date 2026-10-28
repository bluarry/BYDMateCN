package com.bydmatecn.app.local

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LocalCaptureStoreTest {
    private fun withStore(test: (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("local-captures-test").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    @Test fun savesAndPublishesWithoutPendingFiles() = withStore { root ->
        val store = LocalCaptureStore(root, 10)
        val file = store.saveJpeg(byteArrayOf(1, 2, 3), 1)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertEquals(listOf(file), store.captures())
        assertEquals(1, root.listFiles()!!.size)
    }
    @Test fun quotaDeletesOldestOwnedFileOnly() = withStore { root ->
        val unrelated = java.io.File(root, "keep.jpg").apply { writeText("keep") }
        val store = LocalCaptureStore(root, 6)
        val oldest = store.saveJpeg(ByteArray(4), 1).apply { setLastModified(1) }
        val newest = store.saveJpeg(ByteArray(4), 2)
        assertFalse(oldest.exists())
        assertTrue(newest.exists())
        assertTrue(unrelated.exists())
    }
    @Test fun rejectsOversizedBeforeDeletingExistingCapture() = withStore { root ->
        val store = LocalCaptureStore(root, 4)
        val existing = store.saveJpeg(ByteArray(4), 1)
        try { store.saveJpeg(ByteArray(5), 2); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        assertTrue(existing.exists())
    }
    @Test fun timestampsDoNotOverwrite() = withStore { root ->
        val store = LocalCaptureStore(root, 20)
        assertNotEquals(store.saveJpeg(ByteArray(2), 1), store.saveJpeg(ByteArray(2), 1))
    }
    @Test fun parkedGateFailsClosed() {
        assertTrue(localCameraAllowed(1, 0, true, false))
        assertFalse(localCameraAllowed(null, 0, true, false))
        assertFalse(localCameraAllowed(1, null, true, false))
        assertFalse(localCameraAllowed(2, 0, true, false))
        assertFalse(localCameraAllowed(1, 1, true, false))
        assertFalse(localCameraAllowed(1, 0, false, false))
        assertFalse(localCameraAllowed(1, 0, true, true))
    }
}
