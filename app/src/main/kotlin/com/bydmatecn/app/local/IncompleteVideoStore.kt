package com.bydmatecn.app.local

import java.io.File

/** App-owned interrupted segments, serialized with normal media operations. */
open class IncompleteVideoStore(private val root: File) {
    private val lock = LocalMediaLocks.forDirectory(root)
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
