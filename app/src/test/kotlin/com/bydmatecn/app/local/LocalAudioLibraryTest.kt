package com.bydmatecn.app.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LocalAudioLibraryTest {
    @Test fun pathsPersistAndRemovalNeverDeletesAudio() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("local-audio-directories", Context.MODE_PRIVATE).edit().clear().commit()
        val root = File(context.cacheDir, "library-test").apply { mkdirs() }
        try {
            val a = File(root, "a").apply { mkdirs() }
            val b = File(root, "b").apply { mkdirs() }
            val file = File(a, "unlock.wav").apply { writeText("sample") }
            val library = LocalAudioLibrary(context)
            library.add(a.path)
            library.add(a.path)
            library.add(b.path)
            assertEquals(2, LocalAudioLibrary(context).directories().size)
            assertEquals(file.canonicalFile, library.resolve(file.path))
            library.remove(a.canonicalFile)
            assertNull(LocalAudioLibrary(context).resolve(file.path))
            assertTrue(file.exists())
            library.add(a.path)
            assertEquals(file.canonicalFile, LocalAudioLibrary(context).resolve(file.path))
        } finally {
            root.deleteRecursively()
            context.getSharedPreferences("local-audio-directories", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
