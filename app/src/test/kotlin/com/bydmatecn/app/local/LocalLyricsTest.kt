package com.bydmatecn.app.local
import org.junit.Assert.*
import org.junit.Test
class LocalLyricsTest {
    @Test fun multipleTimestampsAndFraction() {
        val lines=LocalLyrics.parse("[00:01.2][00:02.34]hello\n[00:03.456]world")
        assertEquals(listOf(1200L,2340L,3456L),lines.map { it.milliseconds })
        assertEquals("hello",LocalLyrics.at(lines,2000))
    }
    @Test fun offsetAndInvalidSeconds() {
        val lines=LocalLyrics.parse("[offset:-500]\n[00:01]ok\n[00:99]bad")
        assertEquals(1,lines.size); assertEquals(500L,lines[0].milliseconds)
        assertEquals("",LocalLyrics.at(lines,0))
    }
}
