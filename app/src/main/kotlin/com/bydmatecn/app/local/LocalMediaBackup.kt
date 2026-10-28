package com.bydmatecn.app.local

import java.io.*
import java.util.zip.*

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
        val staged = mutableListOf<Pair<File, File>>()
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                val names = mutableSetOf<String>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && names.add(entry.name)) { "目录或重复条目不支持" }
                    require(staged.size < 5000) { "备份条目过多" }
                    val pieces = entry.name.split('/')
                    require(pieces.size == 2 && pieces[1].matches(Regex("[A-Za-z0-9._-]+"))) { "非法备份路径" }
                    val folder = folders[pieces[0]] ?: error("未知备份类型")
                    val name = pieces[1]
                    val extension = name.substringAfterLast('.', "")
                    require(when (pieces[0]) {
                        "audio" -> name.startsWith("audio-") && extension in LocalResourceStore.AUDIO_TYPES
                        "captures" -> name.startsWith("capture-") && extension == "jpg"
                        "videos" -> name.startsWith("video-") && (extension == "mp4" || name.endsWith(".mp4.event"))
                        else -> false
                    }) { "备份文件不是本应用媒体" }
                    val destDir = File(filesRoot, folder)
                    check(destDir.isDirectory || destDir.mkdirs())
                    require(!java.nio.file.Files.isSymbolicLink(destDir.toPath()))
                    val dest = File(destDir, name)
                    require(!dest.exists()) { "同名文件已存在，请不要覆盖现有媒体" }
                    val temp = File(stage, "entry-${staged.size}")
                    val limit = if (name.endsWith(".event")) 1024L else if (pieces[0] == "audio") LocalResourceStore.MAX_FILE_BYTES else 512L*1024*1024
                    var bytes = 0L
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = zip.read(buffer); if (n < 0) break
                            bytes += n; total += n
                            require(bytes <= limit && total <= 1152L*1024*1024) { "备份解压超过限额" }
                            output.write(buffer, 0, n)
                        }
                    }
                    require(bytes > 0) { "空媒体条目" }
                    staged += temp to dest
                    zip.closeEntry()
                }
            }
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
            val published = mutableListOf<File>()
            try {
                staged.forEach { (temp, dest) ->
                    // Same-volume app cache -> private files: move completed bytes, never expose a partial media file.
                    // Shared canonical locks prevent app import/gallery/quota races; default move refuses overwrite.
                    java.nio.file.Files.move(temp.toPath(), dest.toPath())
                    published += dest
                }
            } catch (error: Exception) { published.forEach { it.delete() }; throw error }
            return published.size
        } finally { stage.listFiles().orEmpty().forEach { it.delete() }; stage.delete() }
    }
}
