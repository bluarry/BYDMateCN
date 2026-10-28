package com.bydmatecn.app.local

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalVideoStoreTest {
    private fun withRoot(test: (File) -> Unit) {
        val root = Files.createTempDirectory("local-video-test").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    @Test fun pendingIsHiddenUntilPublished() = withRoot { root ->
        val store = LocalVideoStore(root, 10)
        val pending = store.pending().apply { writeBytes(ByteArray(3)) }
        assertTrue(store.files().isEmpty())
        val video = store.publish(pending)
        assertEquals("mp4", video.extension); assertFalse(pending.exists())
        assertEquals(listOf(video), store.files())
    }
    @Test fun quotaReclaimsOnlyOwnedVideos() = withRoot { root ->
        val store = LocalVideoStore(root, 6)
        val unrelated = File(root, "keep.mp4").apply { writeText("keep") }
        val old = store.publish(store.pending().apply { writeBytes(ByteArray(4)) }).apply { setLastModified(1) }
        val next = store.publish(store.pending().apply { writeBytes(ByteArray(4)) })
        assertFalse(old.exists()); assertTrue(next.exists()); assertTrue(unrelated.exists())
    }
    @Test fun oversizedDoesNotDestroyExistingVideo() = withRoot { root ->
        val store = LocalVideoStore(root, 5)
        val video = store.publish(store.pending().apply { writeBytes(ByteArray(3)) })
        try { store.publish(store.pending().apply { writeBytes(ByteArray(6)) }); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(video.exists())
    }
    @Test fun emptyFileRejected() = withRoot { root ->
        val store = LocalVideoStore(root)
        try { store.publish(store.pending()); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun cannotPublishOutsideRoot() = withRoot { root ->
        val store = LocalVideoStore(File(root, "videos"))
        val foreign = File(root, "video-foreign.pending").apply { writeText("keep") }
        try { store.publish(foreign); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(foreign.exists())
    }
    @Test fun deleteUnknownFileRefused() = withRoot { root ->
        val store = LocalVideoStore(root)
        val unknown = File(root, "other.mp4").apply { writeText("keep") }
        assertFalse(store.delete(unknown)); assertTrue(unknown.exists())
    }
    @Test fun ownedDeleteWorksAcrossStoreInstances() = withRoot { root ->
        val store = LocalVideoStore(root)
        val video = store.publish(store.pending().apply { writeBytes(ByteArray(3)) })
        assertTrue(LocalVideoStore(root).delete(video)); assertTrue(store.files().isEmpty())
    }
    @Test fun duplicatePendingNamesNeverOverwrite() = withRoot { root ->
        val store = LocalVideoStore(root)
        assertNotEquals(store.pending(), store.pending())
    }
    @Test fun protectedEventIsNotEvicted() = withRoot { root ->
        val store=LocalVideoStore(root,6)
        val video=store.publish(store.pending().apply { writeBytes(ByteArray(4)) })
        assertTrue(store.protect(video,"event"))
        try { store.publish(store.pending().apply { writeBytes(ByteArray(4)) }); fail() } catch (_: IllegalStateException) {}
        assertTrue(video.exists()); assertTrue(store.unprotect(video))
    }
    @Test fun staleIncompleteIsQuarantinedNotPublished() = withRoot { root ->
        val store=LocalVideoStore(root)
        store.pending().apply { writeText("partial"); setLastModified(1) }
        assertEquals(1,store.quarantineStale(2000,1000)); assertTrue(store.files().isEmpty())
        assertEquals(1,store.incomplete().size)
    }
    @Test fun invalidQuotaRejected() = withRoot { root ->
        try { LocalVideoStore(root, 0); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun publishEventIsProtectedImmediately() = withRoot { root ->
        val store = LocalVideoStore(root)
        val file = store.publish(store.pending().apply { writeText("video") }, "event")
        assertTrue(store.isProtected(file))
        assertEquals("event", File(root, file.name + ".event").readText())
    }
    @Test fun impossibleProtectedQuotaDoesNotDeleteOtherVideos() = withRoot { root ->
        val store = LocalVideoStore(root, 10)
        val protected = store.publish(store.pending().apply { writeBytes(ByteArray(7)) }, "event")
        val other = store.publish(store.pending().apply { writeBytes(ByteArray(2)) })
        try { store.publish(store.pending().apply { writeBytes(ByteArray(4)) }); fail() }
        catch (_: IllegalStateException) {}
        assertTrue(protected.exists()); assertTrue(other.exists())
    }
}
