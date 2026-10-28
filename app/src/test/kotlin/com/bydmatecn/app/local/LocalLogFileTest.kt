package com.bydmatecn.app.local

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLogFileTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun dump(log: LocalLogFile): String = ByteArrayOutputStream().also { log.export(it) }.toString("UTF-8")

    @Test fun persistsAcrossNewInstanceWithChineseAndTimestamp() {
        val dir = temporary.newFolder()
        LocalLogFile(dir).append("相机", "打开失败：权限不足", 0)
        val text = dump(LocalLogFile(dir))
        assertTrue(text.contains("[相机] 打开失败：权限不足"))
        assertTrue(text.contains("1970-01-01T"))
    }
    @Test fun rotatesOldestFirstAndRetainsOnlyTwoFiles() {
        val dir = temporary.newFolder()
        val log = LocalLogFile(dir, 1024)
        for (i in 1..4) log.append("test", "event$i " + "x".repeat(800))
        val text = dump(log)
        assertFalse(text.contains("event1")); assertFalse(text.contains("event2"))
        assertTrue(text.indexOf("event3") < text.indexOf("event4"))
        assertEquals(2, dir.listFiles()!!.size)
        assertTrue(dir.listFiles()!!.all { it.length() <= 1024 })
    }
    @Test fun singleHugeUnicodeEntryStaysWithinByteCap() {
        val dir = temporary.newFolder()
        val log = LocalLogFile(dir, 1024)
        log.append("test", "中文😀".repeat(2000))
        val file = File(dir, "local-events.log")
        assertTrue(file.length() <= 1024)
        assertFalse(file.readText().contains('\uFFFD'))
        assertTrue(file.readText().endsWith("\n"))
    }
    @Test fun redactsCredentialsIncludingJsonAndNormalizesLines() {
        val clean = LocalLogFile.clean("""token=abc; password=123, Bearer xyz; {"access_token":"a b", "vin":"VIN123"}
next""")
        listOf("abc", "123", "xyz", "a b", "VIN123").forEach { assertFalse(clean.contains(it)) }
        assertFalse(clean.contains('\n'))
        assertTrue(clean.contains("[redacted]"))
    }
    @Test fun unrelatedFileIsNeverRemoved() {
        val dir = temporary.newFolder()
        val unrelated = File(dir, "keep.txt").apply { writeText("keep") }
        val log = LocalLogFile(dir, 1024)
        repeat(8) { log.append("test", "x".repeat(900)) }
        assertEquals("keep", unrelated.readText())
    }
    @Test fun emptyExportDoesNotCreateDirectories() {
        val dir = File(temporary.root, "missing")
        assertTrue(dump(LocalLogFile(dir)).contains("not a full system log"))
        assertFalse(dir.exists())
    }
    @Test fun concurrentAppendsRemainWholeLines() {
        val dir = temporary.newFolder()
        val log = LocalLogFile(dir)
        val threads = (1..6).map { n -> Thread { repeat(40) { log.append("thread$n", "event$it") } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        val lines = File(dir, "local-events.log").readLines()
        assertEquals(240, lines.size)
        assertTrue(lines.all { it.contains(Regex("\\[thread[1-6]\\] event[0-9]+$")) })
    }
}
