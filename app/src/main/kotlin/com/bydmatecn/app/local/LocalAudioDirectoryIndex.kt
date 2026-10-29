package com.bydmatecn.app.local

import java.io.File
import java.util.Locale

/** Read-only index of immediate audio files; no recursion, copies or source-file deletion. */
class LocalAudioDirectoryIndex(private val directories: List<File>) {
    fun files(): List<File> = directories.flatMap { directory ->
        directory.listFiles().orEmpty().filter { file ->
            file.isFile && file.canRead() && file.extension.lowercase(Locale.ROOT) in LocalResourceStore.AUDIO_TYPES &&
                !java.nio.file.Files.isSymbolicLink(file.toPath())
        }.map { it.canonicalFile }
    }.distinctBy { it.path }.sortedBy { it.path }

    fun resolve(path: String?): File? {
        if (path.isNullOrBlank() || !File(path).isAbsolute) return null
        val file = File(path)
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) return null
        val canonical = file.canonicalFile
        return canonical.takeIf {
            it.isFile && it.canRead() && it.extension.lowercase(Locale.ROOT) in LocalResourceStore.AUDIO_TYPES &&
                directories.any { directory -> directory.canonicalFile == it.parentFile }
        }
    }
}
