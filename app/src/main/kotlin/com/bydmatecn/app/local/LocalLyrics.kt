package com.bydmatecn.app.local

/** Bounded local LRC parser; handles multiple timestamps and signed offset, no online lookup. */
object LocalLyrics {
    data class Line(val milliseconds: Long, val text: String)
    fun parse(source: String): List<Line> {
        require(source.length <= 256*1024)
        val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(source)
            ?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-60_000,60_000) ?: 0
        return source.lineSequence().take(5000).flatMap { line ->
            val matches = timestamp.findAll(line).toList()
            val text = timestamp.replace(line, "").trim().take(500)
            matches.mapNotNull {
                val seconds = it.groupValues[2].toInt()
                if (seconds >= 60) null else {
                    val fraction = it.groupValues[3].padEnd(3,'0').take(3).toIntOrNull() ?: 0
                    Line(maxOf(0, (it.groupValues[1].toLong()*60+seconds)*1000+fraction+offset),text)
                }
            }.asSequence()
        }.sortedBy { it.milliseconds }.toList()
    }
    fun at(lines: List<Line>, milliseconds: Long): String = lines.lastOrNull { it.milliseconds <= milliseconds }?.text ?: ""
}
