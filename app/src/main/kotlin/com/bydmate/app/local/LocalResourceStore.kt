package com.bydmate.app.local

import java.io.File
import java.io.InputStream

/** Copy bounded user-selected resources; never trust display names as paths. */
class LocalResourceStore(private val root: File) {
    @Synchronized
    fun importAudio(input: InputStream, extension: String): File {
        val suffix = extension.lowercase(java.util.Locale.ROOT)
        require(suffix in AUDIO_TYPES) { "Unsupported audio extension" }
        check(root.isDirectory || root.mkdirs()) { "Cannot create audio directory" }
        check(files().sumOf { it.length() } < MAX_TOTAL_BYTES) { "Audio quota reached" }
        val pending = File.createTempFile("audio-", ".pending", root)
        try {
            pending.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_FILE_BYTES) { "Audio exceeds 32 MiB" }
                    output.write(buffer, 0, read)
                }
                require(total > 0) { "Empty audio" }
                require(files().sumOf { it.length() } + total <= MAX_TOTAL_BYTES) { "Audio quota exceeded" }
            }
            val target = File(root, pending.name.removeSuffix(".pending") + ".$suffix")
            check(pending.renameTo(target)) { "Cannot publish audio" }
            return target
        } finally { pending.delete() }
    }
    @Synchronized
    fun files(): List<File> = root.listFiles().orEmpty().filter {
        it.isFile && it.name.startsWith("audio-") && it.extension in AUDIO_TYPES
    }.sortedBy { it.name }

    @Synchronized
    fun delete(file: File): Boolean {
        val owned = files().firstOrNull { it.canonicalFile == file.canonicalFile } ?: return false
        return owned.delete()
    }

    companion object {
        val AUDIO_TYPES = setOf("mp3", "wav", "m4a", "ogg", "aac", "flac")
        const val MAX_FILE_BYTES = 32L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
    }
}
