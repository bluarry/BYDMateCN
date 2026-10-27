package com.bydmate.app.local

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Private app-owned storage only. Unknown files and directories are never deleted. */
class LocalCaptureStore(private val root: File, private val quotaBytes: Long = 512L * 1024 * 1024) {
    init { require(quotaBytes > 0) }
    private val lock = locks.computeIfAbsent(root.canonicalPath) { Any() }
    fun <T> withCaptures(action: (List<File>) -> T): T = synchronized(lock) { action(captures()) }

    fun saveJpeg(bytes: ByteArray, timestamp: Long): File = synchronized(lock) {
        require(bytes.isNotEmpty() && bytes.size.toLong() <= quotaBytes)
        check(root.isDirectory || root.mkdirs()) { "Cannot create capture directory" }
        trimTo(quotaBytes - bytes.size)
        val temporary = File.createTempFile("capture-$timestamp-", ".pending", root)
        try {
            temporary.outputStream().use { it.write(bytes) }
            val destination = File(root, temporary.name.removeSuffix(".pending") + ".jpg")
            check(temporary.renameTo(destination)) { "Cannot publish capture" }
            return destination
        } finally {
            temporary.delete()
        }
    }

    fun captures(): List<File> = synchronized(lock) { root.listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("capture-") && it.extension == "jpg" }
        .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
    }

    companion object { private val locks = ConcurrentHashMap<String, Any>() }

    private fun trimTo(budget: Long) {
        val files = captures()
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= budget) break
            val size = file.length()
            check(file.delete()) { "Cannot reclaim capture storage" }
            total -= size
        }
        check(total <= budget) { "Capture quota exceeded" }
    }
}
