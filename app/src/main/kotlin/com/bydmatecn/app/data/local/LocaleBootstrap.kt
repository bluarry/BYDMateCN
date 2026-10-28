package com.bydmatecn.app.data.local

/** Used only when no language is saved; existing explicit language choices remain untouched. */
@Suppress("UNUSED_PARAMETER")
fun decideLanguage(setupCompleted: Boolean): String = LocalePreferences.DEFAULT_LANGUAGE
