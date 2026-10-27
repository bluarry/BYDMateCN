package com.bydmate.app.local
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
class LocalResourceStoreTest {
    private fun runTest(test: (File, LocalResourceStore) -> Unit) {
        val root = Files.createTempDirectory("local-audio-test").toFile()
        try { test(root, LocalResourceStore(root)) } finally { root.deleteRecursively() }
    }
    @Test fun importUsesGeneratedNameAndDeleteOnlyOwnedFile() = runTest { root, store ->
        val saved = store.importAudio(byteArrayOf(1, 2).inputStream(), "MP3")
        assertEquals("mp3", saved.extension); assertArrayEquals(byteArrayOf(1, 2), saved.readBytes())
        val unrelated = File(root, "keep.mp3").apply { writeText("keep") }
        assertFalse(store.delete(unrelated)); assertTrue(unrelated.exists())
        assertTrue(store.delete(saved)); assertTrue(store.files().isEmpty())
    }
    @Test fun rejectedImportDoesNotLeavePendingFile() = runTest { root, store ->
        try { store.importAudio(byteArrayOf().inputStream(), "wav"); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(root.listFiles()!!.isEmpty())
        try { store.importAudio(byteArrayOf(1).inputStream(), "../mp3"); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(root.listFiles()!!.isEmpty())
    }
}
