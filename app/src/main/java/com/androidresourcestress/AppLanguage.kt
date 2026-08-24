package com.androidresourcestress

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

enum class AppLanguage(val languageTag: String) {
    SYSTEM(""),
    SIMPLIFIED_CHINESE("zh-CN"),
    ENGLISH("en"),
    ;

    companion object {
        fun parse(value: String?): AppLanguage = entries.firstOrNull { it.name == value }
            ?: SYSTEM
    }
}

object AppLocaleController {
    fun wrap(context: Context): Context {
        val language = AppPreferences(context).language
        if (language == AppLanguage.SYSTEM) return context
        val locale = Locale.forLanguageTag(language.languageTag)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLocales(LocaleList(locale))
        }
        return context.createConfigurationContext(configuration)
    }

    fun apply(activity: Activity, language: AppLanguage) {
        AppPreferences(activity).language = language
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(language.languageTag)
        } else {
            activity.recreate()
        }
    }
}

abstract class LocalizedActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleController.wrap(newBase))
    }
}
