package com.bydmatecn.app.local
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.zip.*
class LocalMediaBackupTest {
    private fun withRoot(action: (File)->Unit) {
        val root=Files.createTempDirectory("backup-test").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
    private fun zip(name: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry(name)); it.write(byteArrayOf(1,2,3)); it.closeEntry() }
    }.toByteArray()
    @Test fun exportRestoreRoundTrip() = withRoot { root ->
        val source=File(root,"source"); val dest=File(root,"dest")
        File(source,"local-audio").mkdirs(); File(source,"local-audio/audio-test.wav").writeBytes(byteArrayOf(1,2,3))
        val out=ByteArrayOutputStream()
        assertEquals(1,LocalMediaBackup(source,File(root,"cache1")).export(out))
        assertEquals(1,LocalMediaBackup(dest,File(root,"cache2")).restore(out.toByteArray().inputStream()))
        assertArrayEquals(byteArrayOf(1,2,3),File(dest,"local-audio/audio-test.wav").readBytes())
    }
    @Test fun traversalRejected() = withRoot { root ->
        try { LocalMediaBackup(root,File(root,"cache")).restore(zip("videos/../escape.mp4").inputStream()); fail() } catch (_: IllegalArgumentException) {}
        assertFalse(File(root,"escape.mp4").exists())
    }
    @Test fun sameNameNeverOverwritten() = withRoot { root ->
        val dir=File(root,"local-audio").apply { mkdirs() }
        val file=File(dir,"audio-test.wav").apply { writeText("keep") }
        try { LocalMediaBackup(root,File(root,"cache")).restore(zip("audio/audio-test.wav").inputStream()); fail() } catch (_: IllegalArgumentException) {}
        assertEquals("keep",file.readText())
    }
    @Test fun protectedVideoRoundTrip() = withRoot { root ->
        val source = File(root, "source")
        val store = LocalVideoStore(File(source, "local-videos"))
        val pending = store.pending().apply { writeBytes(byteArrayOf(1,2,3)) }
        val file = store.publish(pending)
        assertTrue(store.protect(file, "door-open"))
        val out = ByteArrayOutputStream()
        assertEquals(2, LocalMediaBackup(source, File(root,"c1")).export(out))
        val dest = File(root,"dest")
        assertEquals(2, LocalMediaBackup(dest, File(root,"c2")).restore(out.toByteArray().inputStream()))
        val restored = LocalVideoStore(File(dest,"local-videos"))
        assertTrue(restored.isProtected(restored.files().single()))
    }
    @Test fun orphanMarkerRejectedWithoutPublication() = withRoot { root ->
        try { LocalMediaBackup(root, File(root,"cache")).restore(zip("videos/video-a.mp4.event").inputStream()); fail() }
        catch (_: IllegalArgumentException) {}
        assertTrue(File(root,"local-videos").listFiles().orEmpty().isEmpty())
    }
    @Test fun invalidLateEntryLeavesNoPartialMedia() = withRoot { root ->
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            listOf("audio/audio-good.wav", "videos/../bad.mp4").forEach {
                zip.putNextEntry(ZipEntry(it)); zip.write(byteArrayOf(1)); zip.closeEntry()
            }
        }
        try { LocalMediaBackup(root,File(root,"cache")).restore(out.toByteArray().inputStream()); fail() }
        catch (_: IllegalArgumentException) {}
        assertTrue(File(root,"local-audio").listFiles().orEmpty().isEmpty())
    }
}
