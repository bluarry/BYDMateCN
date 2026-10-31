package com.bydmatecn.app.local

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Media-only transactional restore. No accounts, databases, permissions or activation data. */
class LocalMediaBackup(private val filesRoot: File, private val cacheRoot: File) {
    private val folders = mapOf("audio" to "local-audio", "captures" to "local-captures", "videos" to "local-videos")
    fun export(output: OutputStream): Int {
        val audio = LocalResourceStore(File(filesRoot, "local-audio"))
        val captures = LocalCaptureStore(File(filesRoot, "local-captures"))
        val videos = LocalVideoStore(File(filesRoot, "local-videos"))
        return audio.withFiles { sounds -> captures.withCaptures { images -> videos.withFiles { movies ->
            val entries = sounds.map { "audio/${it.name}" to it } + images.map { "captures/${it.name}" to it } + movies.map { "videos/${it.name}" to it } +
                movies.filter { videos.isProtected(it) }.map { "videos/${it.name}.event" to File(it.parentFile, it.name + ".event") }
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, file) -> zip.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            }
            entries.size
        } } }
    }
    fun restore(input: InputStream): Int =
        synchronized(LocalMediaLocks.forDirectory(File(filesRoot, "local-audio"))) {
            synchronized(LocalMediaLocks.forDirectory(File(filesRoot, "local-captures"))) {
                synchronized(LocalMediaLocks.forDirectory(File(filesRoot, "local-videos"))) { restoreLocked(input) }
            }
        }
    private fun restoreLocked(input: InputStream): Int {
        check(cacheRoot.isDirectory || cacheRoot.mkdirs())
        val stage = java.nio.file.Files.createTempDirectory(cacheRoot.toPath(), "media-restore-").toFile()
        try {
            val staged = readArchive(input, stage)
            validateStaged(staged)
            return publishStaged(staged)
        } finally {
            stage.listFiles().orEmpty().forEach { it.delete() }
            stage.delete()
        }
    }

    private fun readArchive(input: InputStream, stage: File): List<Pair<File, File>> {
        val staged = mutableListOf<Pair<File, File>>()
        var total = 0L
        val names = mutableSetOf<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && names.add(entry.name)) { "Duplicate or directory entry" }
                require(staged.size < 5000) { "Too many backup entries" }
                val destination = destination(entry.name)
                val temporary = File(stage, "entry-${staged.size}")
                val bytes = copyEntry(zip, temporary, entryLimit(entry.name), total)
                total += bytes
                staged += temporary to destination
                zip.closeEntry()
            }
        }
        return staged
    }

    private fun destination(path: String): File {
        val pieces = path.split('/')
        require(pieces.size == 2 && pieces[1].matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid backup path" }
        val folder = folders[pieces[0]] ?: error("Unknown backup type")
        val name = pieces[1]
        val extension = name.substringAfterLast('.', "")
        val valid = when (pieces[0]) {
            "audio" -> name.startsWith("audio-") && extension in LocalResourceStore.AUDIO_TYPES
            "captures" -> name.startsWith("capture-") && extension == "jpg"
            "videos" -> name.startsWith("video-") && (extension == "mp4" || name.endsWith(".mp4.event"))
            else -> false
        }
        require(valid) { "Not application media" }
        val directory = File(filesRoot, folder)
        check(directory.isDirectory || directory.mkdirs())
        require(!java.nio.file.Files.isSymbolicLink(directory.toPath()))
        return File(directory, name).also { require(!it.exists()) { "Existing media must not be overwritten" } }
    }

    private fun entryLimit(path: String): Long = when {
        path.endsWith(".event") -> 1024L
        path.startsWith("audio/") -> LocalResourceStore.MAX_FILE_BYTES
        else -> 512L * 1024 * 1024
    }

    private fun copyEntry(input: InputStream, file: File, limit: Long, previous: Long): Long {
        var bytes = 0L
        file.outputStream().use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                require(bytes <= limit && previous + bytes <= 1152L * 1024 * 1024) { "Backup exceeds extraction limit" }
                output.write(buffer, 0, count)
            }
        }
        require(bytes > 0) { "Empty media entry" }
        return bytes
    }

    private fun validateStaged(staged: List<Pair<File, File>>) {
            staged.filter { it.second.name.endsWith(".event") }.forEach { (_, marker) ->
                require(staged.any { it.second == File(marker.parentFile, marker.name.removeSuffix(".event")) }) {
                    "事件标记缺少对应视频"
                }
            }
            folders.values.forEach { folder ->
                val quota = if (folder == "local-audio") LocalResourceStore.MAX_TOTAL_BYTES else 512L*1024*1024
                val existing = File(filesRoot, folder).listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
                require(existing + staged.filter { it.second.parentFile?.name == folder }.sumOf { it.first.length() } <= quota) { "${folder}超出配额" }
            }

    }

    private fun publishStaged(staged: List<Pair<File, File>>): Int {
            val published = mutableListOf<File>()
            var committed = false
            try {
                staged.forEach { (temp, dest) ->
                    // Same-volume app cache -> private files: move completed bytes, never expose a partial media file.
                    // Shared canonical locks prevent app import/gallery/quota races; default move refuses overwrite.
                    java.nio.file.Files.move(temp.toPath(), dest.toPath())
                    published += dest
                }

                committed = true
        } finally { if (!committed) { published.forEach { it.delete() } } }
            return published.size
    }
}
