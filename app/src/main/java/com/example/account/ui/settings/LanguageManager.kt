package com.example.account.ui.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

enum class AppLanguage(val languageTag: String) {
    SIMPLIFIED_CHINESE("zh-CN"),
    TRADITIONAL_CHINESE("zh-TW"),
    ENGLISH("en");

    companion object {
        fun fromLanguageTag(value: String?): AppLanguage? {
            return values().firstOrNull { it.languageTag.equals(value, ignoreCase = true) }
        }
    }
}

object LanguageManager {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_LANGUAGE = "language"

    fun applySavedLanguage(context: Context) {
        val savedLanguage = prefs(context).getString(KEY_LANGUAGE, null)
        val language = AppLanguage.fromLanguageTag(savedLanguage) ?: return
        applyLanguage(language)
    }

    fun currentLanguage(context: Context): AppLanguage {
        val savedLanguage = prefs(context).getString(KEY_LANGUAGE, null)
        val saved = AppLanguage.fromLanguageTag(savedLanguage)
        if (saved != null) {
            return saved
        }
        val locale = AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault()
        return inferLanguage(locale)
    }

    fun updateLanguage(context: Context, language: AppLanguage) {
        prefs(context).edit()
            .putString(KEY_LANGUAGE, language.languageTag)
            .apply()
        applyLanguage(language)
    }

    private fun applyLanguage(language: AppLanguage) {
        val targetLocales = LocaleListCompat.forLanguageTags(language.languageTag)
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() == targetLocales.toLanguageTags()) {
            return
        }
        AppCompatDelegate.setApplicationLocales(targetLocales)
    }

    private fun inferLanguage(locale: Locale): AppLanguage {
        if (locale.language.equals("zh", ignoreCase = true)) {
            val region = locale.country.uppercase(Locale.ROOT)
            val script = locale.script.lowercase(Locale.ROOT)
            val isTraditional = script == "hant" || region == "TW" || region == "HK" || region == "MO"
            return if (isTraditional) {
                AppLanguage.TRADITIONAL_CHINESE
            } else {
                AppLanguage.SIMPLIFIED_CHINESE
            }
        }
        return AppLanguage.ENGLISH
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
