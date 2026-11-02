package com.bydmatecn.app.local

import android.content.Context

/** Persistent user controls for the bounded local verification journal. */
object LocalLogSettings {
    private const val PREFS = "local-verification-log"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_RETENTION_DAYS = "retention_days"
    private val allowedDays = setOf(1, 3, 7)

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun retentionDays(context: Context): Int {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_RETENTION_DAYS, 1)
        return stored.takeIf { it in allowedDays } ?: 1
    }

    fun setRetentionDays(context: Context, days: Int) {
        require(days in allowedDays)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_RETENTION_DAYS, days).apply()
    }
}
