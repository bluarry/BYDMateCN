package com.bydmatecn.app.local

import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded UTF-8 text journal. Only app-owned fixed filenames are touched. */
internal class LocalLogFile(private val directory: File, private val maxBytes: Long = 1024 * 1024) {
    init { require(maxBytes >= 1024) }
    private val current get() = File(directory, "local-events.log")
    private val previous get() = File(directory, "local-events.previous.log")
    @Synchronized fun append(module: String, message: String, time: Long = System.currentTimeMillis()) {
        check(directory.isDirectory || directory.mkdirs())
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(time))
        val text = "$timestamp [${clean(module).take(40)}] ${clean(message)}\n"
        // Truncate on Unicode code-point boundaries, including the newline in the cap.
        val body = text.dropLast(1)
        val bounded = StringBuilder()
        var used = 1L
        var offset = 0
        while (offset < body.length) {
            val cp = body.codePointAt(offset)
            val part = String(Character.toChars(cp))
            val size = part.toByteArray(Charsets.UTF_8).size
            if (used + size > maxBytes) break
            bounded.append(part); used += size; offset += Character.charCount(cp)
        }
        val bytes = (bounded.toString() + "\n").toByteArray(Charsets.UTF_8)
        if (current.length() + bytes.size > maxBytes) {
            if (previous.exists()) check(previous.delete())
            if (current.exists()) check(current.renameTo(previous))
        }
        current.appendBytes(bytes)
    }
    @Synchronized fun export(output: OutputStream) {
        output.write("BYDMateCN journal v1; oldest retained file first; not a full system log\n".toByteArray())
        for (file in listOf(previous, current)) if (file.isFile) {
            output.write(("--- ${file.name} ---\n").toByteArray())
            file.inputStream().use { it.copyTo(output) }
        }
    }
    companion object {
        private val secret = Regex("""(?i)(["']?(?:[a-z0-9_]*token|authorization|password|secret|vin)["']?\s*[:=]\s*)(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s,;]+)""")
        private val bearer = Regex("(?i)Bearer\\s+[^\\s,;]+")
        internal fun clean(value: String): String = bearer.replace(secret.replace(value, "$1[redacted]"), "Bearer [redacted]")
            .replace(Regex("[\\r\\n\\t\\p{Cntrl}]+"), " ").take(4000)
    }
}
