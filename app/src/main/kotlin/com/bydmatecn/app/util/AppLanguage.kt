package com.bydmatecn.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.bydmatecn.app.data.automation.AutomationEngine
import com.bydmatecn.app.data.local.LocalePreferences
import com.bydmatecn.app.service.TrackingService
import com.bydmatecn.app.ui.overlay.ListeningOverlay
import com.bydmatecn.app.ui.widget.WidgetController

/** App UI languages as (code, native name); shared by the Settings picker and the first-run wizard. */
val APP_LANGUAGES: List<Pair<String, String>> = listOf(
    "zh" to "简体中文",
    "en" to "English",
    "ru" to "Русский",
    "pt" to "Português",
    "pl" to "Polski",
    "be" to "Беларуская",
)

/**
 * Persists [lang] and applies it at runtime. MainActivity listens to [LocalePreferences]
 * and recomposes without Activity.recreate().
 */
fun applyAppLanguage(appContext: Context, localePreferences: LocalePreferences, lang: String) {
    localePreferences.setLanguage(lang)
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
    // Force overlay teardown so the next attach picks up the new locale.
    // applicationContext keeps a stale Configuration after setApplicationLocales,
    // which leaves the floating widget rendering against the old language.
    WidgetController.relocale(appContext)  // C-5: pass context from VM, not from widgetView
    // The voice orb dialog may be on screen: swap its captions in place.
    ListeningOverlay.relocale(appContext)
    // Channel names live in the system settings: re-register them under the new language.
    val strings = appContext.localizedContext(lang)
    TrackingService.NotificationChannels.create(appContext, strings)
    AutomationEngine.createConfirmChannel(appContext, strings)
}

/** Picker names stay Chinese until the user explicitly chooses another UI language. */
fun appLanguageLabels(current: String): List<String> = if (current.startsWith("zh"))
    listOf("简体中文", "英语", "俄语", "葡萄牙语", "波兰语", "白俄罗斯语")
else APP_LANGUAGES.map { it.second }
