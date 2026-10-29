package com.bydmatecn.app.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LocalComposeUiTest {
    @Test fun screenWidthAndSectionBoundaries() {
        assertEquals(2, dashboardColumns(1020))
        assertEquals(1, dashboardColumns(360))
        val ui = LocalUiState()
        ui.section("音频库")
        ui.button("扫描") {}
        ui.section("锁车与解锁")
        ui.button("布防") {}
        assertEquals(listOf(0, 1), ui.sections.map { it.start })
        assertEquals(setOf(0, 1), ui.actionIndices)
    }
    @Test fun statusUpdatesRetainJournalCallback() {
        val updates = mutableListOf<String>()
        val text = LocalText("就绪") { updates.add(it) }
        text.text = "开始播放"
        text.text = "驻车条件丢失，已停止"
        assertEquals("驻车条件丢失，已停止", text.text)
        assertEquals(listOf("开始播放", "驻车条件丢失，已停止"), updates)
    }
    @Test fun directoryDialogReplacementDoesNotEraseSuccessor() {
        val host = TestHost(ApplicationProvider.getApplicationContext())
        val next = LocalDialogBuilder(host).setTitle("输入路径")
        val first = LocalDialogBuilder(host).setItems(arrayOf("手动输入")) { _, _ -> next.show() }
        first.show()
        assertSame(first, host.ui.dialog)
        host.ui.dialog = null
        first.selected?.invoke(0)
        assertSame(next, host.ui.dialog)
    }
    private class TestHost(context: Context) : android.content.ContextWrapper(context), LocalComposeHost {
        override val ui = LocalUiState()
    }
}
