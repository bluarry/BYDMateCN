package com.bydmatecn.app.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalAudioDirectoryIndexTest {
    @Test fun indexesMultiplePathsAndPreservesDuplicateNames() {
        val root = Files.createTempDirectory("audio-index").toFile()
        try {
            val a = File(root, "one").apply { mkdirs() }
            val b = File(root, "two").apply { mkdirs() }
            val first = File(a, "unlock.MP3").apply { writeText("a") }
            val second = File(b, "unlock.mp3").apply { writeText("b") }
            File(a, "notes.txt").writeText("ignore")
            File(a, "child").apply { mkdirs(); File(this, "nested.wav").writeText("ignore") }
            val index = LocalAudioDirectoryIndex(listOf(a, a, b))
            assertEquals(2, index.files().size)
            assertEquals(first.canonicalFile, index.resolve(first.path))
            assertEquals(second.canonicalFile, index.resolve(second.path))
            assertNull(index.resolve("unlock.mp3"))
            assertNull(LocalAudioDirectoryIndex(listOf(a)).resolve(second.path))
            assertTrue(first.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun removedFilesAndUnselectedDirectoriesAreUnavailable() {
        val root = Files.createTempDirectory("audio-index").toFile()
        try {
            val audio = File(root, "lock.wav").apply { writeText("sample") }
            val index = LocalAudioDirectoryIndex(listOf(root))
            assertEquals(1, index.files().size)
            audio.delete()
            assertNull(index.resolve(audio.path))
            assertTrue(index.files().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
