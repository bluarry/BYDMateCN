package com.bydmatecn.app.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmatecn.app.BuildConfig
import com.bydmatecn.app.R
import com.bydmatecn.app.data.local.LocalePreferences
import com.bydmatecn.app.ui.settings.DonationReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en")
class ChineseDefaultTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun clearLanguage() { context.getSharedPreferences(LocalePreferences.FILE, 0).edit().remove(LocalePreferences.KEY_LANG).commit() }
    @Test fun unsetLanguageUsesChineseEvenOnEnglishSystem() {
        assertEquals("zh", context.appLanguageTag())
        assertEquals("已保存", AppStrings(context).get(R.string.settings_saved))
        assertEquals("工具", context.appLocalizedContext().getString(R.string.nav_tab_local))
    }
    @Test fun explicitLanguageSwitchRemainsAvailable() {
        LocalePreferences(context).setLanguage("ru")
        assertEquals("Сохранено", AppStrings(context).get(R.string.settings_saved))
        LocalePreferences(context).setLanguage("en")
        assertEquals("Saved", AppStrings(context).get(R.string.settings_saved))
        LocalePreferences(context).setLanguage("zh")
        assertEquals("已保存", AppStrings(context).get(R.string.settings_saved))
    }
    @Test fun defaultLanguagePickerOnlyHasChineseLabels() {
        assertEquals("zh", APP_LANGUAGES.first().first)
        assertEquals(listOf("简体中文", "英语", "俄语", "葡萄牙语", "波兰语", "白俄罗斯语"), appLanguageLabels("zh"))
        assertTrue(appLanguageLabels("en").contains("Русский"))
    }
    @Test fun donationsAreHiddenIncludingAutomaticPrompt() {
        assertFalse(BuildConfig.DONATIONS_ENABLED)
        assertFalse(DonationReminder.enabled)
        assertFalse(DonationReminder.shouldShow(context, "new-version"))
    }
}
