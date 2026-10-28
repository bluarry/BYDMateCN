package com.bydmatecn.app.local

import java.io.File

/** Only app-created video names are eligible for quota cleanup. Pending files are never published. */
class LocalVideoStore(private val root: File, private val quotaBytes: Long = 512L * 1024 * 1024) {
    init { require(quotaBytes > 0) }
    private val lock = LocalMediaLocks.forDirectory(root)
    fun <T> withFiles(action: (List<File>) -> T): T = synchronized(lock) { action(files()) }
    fun files(): List<File> = synchronized(lock) {
        root.listFiles().orEmpty().filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) &&
            it.name.startsWith("video-") && it.extension == "mp4" }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
    }
    fun pending(): File = synchronized(lock) {
        check(root.isDirectory || root.mkdirs())
        File.createTempFile("video-${System.currentTimeMillis()}-", ".pending", root)
    }
    fun publish(file: File, eventReason: String? = null): File = synchronized(lock) {
        require(file.parentFile?.canonicalFile == root.canonicalFile && file.name.startsWith("video-") && file.extension == "pending")
        require(!java.nio.file.Files.isSymbolicLink(file.toPath()) && file.length() in 1..quotaBytes)
        var total = files().sumOf { it.length() } + file.length()
        val reclaimable = files().filterNot { isProtected(it) }
        check(total - reclaimable.sumOf { it.length() } <= quotaBytes) { "视频配额已被保护事件占满，请先导出/解除保护" }
        for (old in reclaimable) {
            if (total <= quotaBytes) break
            val size = old.length(); check(old.delete()); total -= size
        }
        check(total <= quotaBytes) { "视频配额已被保护事件占满，请先导出/解除保护" }
        val target = File(root, file.name.removeSuffix(".pending") + ".mp4")
        check(!target.exists()) { "Video destination already exists" }
        check(file.renameTo(target))
        if (eventReason != null) {
            try { File(root, target.name + ".event").writeText(eventReason.take(200), Charsets.UTF_8) }
            catch (error: Exception) { target.delete(); File(root, target.name + ".event").delete(); throw error }
        }
        target
    }
    fun delete(file: File): Boolean = synchronized(lock) {
        files().firstOrNull { it.canonicalFile == file.canonicalFile }?.let { owned ->
            if (owned.delete()) { File(root, owned.name + ".event").delete(); true } else false
        } ?: false
    }
    fun isProtected(file: File): Boolean = synchronized(lock) { File(root, file.name + ".event").isFile }
    fun protect(file: File, reason: String): Boolean = synchronized(lock) {
        if (file !in files()) return false
        File(root, file.name + ".event").writeText(reason.take(200), Charsets.UTF_8); true
    }
    fun unprotect(file: File): Boolean = synchronized(lock) {
        if (file !in files()) return false
        File(root, file.name + ".event").delete()
    }
    /** Quarantine stale incomplete segments instead of deleting or claiming recovery. */
    fun quarantineStale(now: Long, ageMs: Long = 24*60*60*1000L): Int = synchronized(lock) {
        require(ageMs > 0)
        var moved = 0
        root.listFiles().orEmpty().filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) &&
            it.name.startsWith("video-") && it.extension == "pending" && now-it.lastModified() >= ageMs }.forEach {
                val target = File(root, it.name.removeSuffix(".pending") + ".incomplete")
                if (!target.exists() && it.renameTo(target)) moved++
            }
        moved
    }
    fun incomplete(): List<File> = synchronized(lock) { root.listFiles().orEmpty().filter {
        it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.name.startsWith("video-") && it.extension == "incomplete"
    } }
    fun deleteIncomplete(file: File): Boolean = synchronized(lock) { incomplete().firstOrNull { it == file }?.delete() ?: false }
}
