package com.bydmatecn.app.local

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Media-only transactional restore. No accounts, databases, permissions or activation data. */
@Suppress("TooManyFunctions") // Transactional ZIP validation keeps all media rules in one boundary.
class LocalMediaBackup(
    private val filesRoot: File,
    private val cacheRoot: File,
    videoDirectories: Map<VehicleRecorderMode, File> = emptyMap(),
) {
    private val dashcamDirectory = videoDirectories[VehicleRecorderMode.DASHCAM]
        ?: File(filesRoot, "local-dashcam-videos")
    private val sentryDirectory = videoDirectories[VehicleRecorderMode.PARKING_SENTRY]
        ?: File(filesRoot, "local-sentry-videos")
    private val folders = mapOf(
        "audio" to File(filesRoot, "local-audio"),
        "captures" to File(filesRoot, "local-captures"),
        "dashcam-videos" to dashcamDirectory,
        "sentry-videos" to sentryDirectory,
        // Read old archives into dashcam storage; legacy archives never proved sentry ownership.
        "videos" to dashcamDirectory,
    )

    fun export(output: OutputStream): Int {
        val audio = LocalResourceStore(folders.getValue("audio"))
        val captures = LocalCaptureStore(folders.getValue("captures"))
        val dashcam = LocalVideoStore(dashcamDirectory)
        val sentry = LocalVideoStore(sentryDirectory)
        return audio.withFiles { sounds ->
            captures.withCaptures { images ->
                dashcam.withFiles { dashcamFiles ->
                    sentry.withFiles { sentryFiles ->
                        val entries = sounds.map { "audio/${it.name}" to it } +
                            images.map { "captures/${it.name}" to it } +
                            videoEntries("dashcam-videos", dashcam, dashcamFiles) +
                            videoEntries("sentry-videos", sentry, sentryFiles)
                        ZipOutputStream(output).use { zip ->
                            entries.forEach { (name, file) ->
                                zip.putNextEntry(ZipEntry(name))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                        entries.size
                    }
                }
            }
        }
    }

    private fun videoEntries(prefix: String, store: LocalVideoStore, files: List<File>): List<Pair<String, File>> =
        files.map { "$prefix/${it.name}" to it } + files.filter(store::isProtected).map {
            "$prefix/${it.name}.event" to File(it.parentFile, it.name + ".event")
        }

    fun restore(input: InputStream): Int = withDirectoryLocks(folders.values.distinct()) { restoreLocked(input) }

    private fun <T> withDirectoryLocks(directories: List<File>, block: () -> T): T {
        fun locked(index: Int): T = if (index >= directories.size) block() else {
            synchronized(LocalMediaLocks.forDirectory(directories[index])) { locked(index + 1) }
        }
        return locked(0)
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
        val directory = folders[pieces[0]] ?: error("Unknown backup type")
        val name = pieces[1]
        val extension = name.substringAfterLast('.', "")
        val valid = when (pieces[0]) {
            "audio" -> name.startsWith("audio-") && extension in LocalResourceStore.AUDIO_TYPES
            "captures" -> name.startsWith("capture-") && extension == "jpg"
            "videos", "dashcam-videos", "sentry-videos" ->
                name.startsWith("video-") && (extension == "mp4" || name.endsWith(".mp4.event"))
            else -> false
        }
        require(valid) { "Not application media" }
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
                require(bytes <= limit && previous + bytes <= 1152L * 1024 * 1024) {
                    "Backup exceeds extraction limit"
                }
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
        folders.values.distinct().forEach { directory ->
            val quota = if (directory == folders["audio"]) LocalResourceStore.MAX_TOTAL_BYTES else 512L * 1024 * 1024
            val existing = directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
            val incoming = staged.filter { it.second.parentFile == directory }.sumOf { it.first.length() }
            require(existing + incoming <= quota) { "${directory.name}超出配额" }
        }
    }

    private fun publishStaged(staged: List<Pair<File, File>>): Int {
        val published = mutableListOf<File>()
        var committed = false
        try {
            staged.forEach { (temp, destination) ->
                java.nio.file.Files.move(temp.toPath(), destination.toPath())
                published += destination
            }
            committed = true
        } finally {
            if (!committed) published.forEach { it.delete() }
        }
        return published.size
    }
}
