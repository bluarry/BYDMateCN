package com.bydmatecn.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleBootstrapTest {

    @Test
    fun `unset upgrader defaults to Chinese`() {
        assertEquals("zh", decideLanguage(setupCompleted = true))
    }

    @Test
    fun `fresh install defaults to Chinese`() {
        assertEquals("zh", decideLanguage(setupCompleted = false))
    }
}
