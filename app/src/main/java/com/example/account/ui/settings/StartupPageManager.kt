package com.example.account.ui.settings

import android.content.Context

enum class AppStartupPage(val storageValue: String) {
    HOME("home"),
    TRANSACTION("transaction");

    companion object {
        fun fromStorageValue(value: String?): AppStartupPage? {
            return values().firstOrNull { it.storageValue.equals(value, ignoreCase = true) }
        }
    }
}

object StartupPageManager {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_STARTUP_PAGE = "startup_page"

    fun currentStartupPage(context: Context): AppStartupPage {
        val savedStartupPage = prefs(context).getString(KEY_STARTUP_PAGE, null)
        return AppStartupPage.fromStorageValue(savedStartupPage) ?: AppStartupPage.HOME
    }

    fun updateStartupPage(context: Context, startupPage: AppStartupPage) {
        prefs(context).edit()
            .putString(KEY_STARTUP_PAGE, startupPage.storageValue)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
