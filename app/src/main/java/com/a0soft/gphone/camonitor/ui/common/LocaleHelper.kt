package com.a0soft.gphone.camonitor.ui.common

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.core.content.edit
import com.a0soft.gphone.camonitor.CamMonitorApp
import java.util.Locale

object LocaleHelper {
    const val LANGUAGE_FOLLOW_SYSTEM = ""
    const val LANGUAGE_EN = "en"
    const val LANGUAGE_ZH_CN = "zh-CN"
    const val LANGUAGE_ZH_TW = "zh-TW"
    const val LANGUAGE_JA = "ja"

    /**
     * Maps any Chinese tag to one of the two script variants this app ships.
     *
     * The previous code collapsed EVERY "zh*" tag to zh-TW, so a zh-CN device
     * could never reach the Simplified resources — and once Simplified became the
     * default resource set, that bug would have silently forced Traditional on
     * mainland users.
     */
    private fun normalizeChineseTag(tag: String): String {
        val lower = tag.lowercase()
        return when {
            lower.contains("hant") -> LANGUAGE_ZH_TW
            lower.endsWith("-tw") || lower.endsWith("-hk") || lower.endsWith("-mo") -> LANGUAGE_ZH_TW
            else -> LANGUAGE_ZH_CN // "zh", "zh-CN", "zh-SG", "zh-Hans"
        }
    }

    private const val PREFS_NAME = "cammonitor_locale_prefs"
    private const val KEY_APP_LANGUAGE = "key_app_language"

    fun Context.findActivity(): Activity? {
        var ctx = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    fun getTargetLocale(context: Context): Locale {
        val tag = getCurrentLanguageTag(context)
        return if (tag.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
            } else {
                Locale.getDefault()
            }
        } else {
            Locale.forLanguageTag(tag)
        }
    }

    fun getCurrentLanguageTag(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeManager = context.getSystemService(LocaleManager::class.java)
                val appLocales = localeManager.applicationLocales
                if (!appLocales.isEmpty) {
                    val primary = appLocales[0]
                    val tag = primary.toLanguageTag()
                    return if (tag.startsWith("zh", ignoreCase = true)) {
                        normalizeChineseTag(tag)
                    } else if (tag.startsWith("en", ignoreCase = true)) {
                        LANGUAGE_EN
                    } else if (tag.startsWith("ja", ignoreCase = true)) {
                        LANGUAGE_JA
                    } else {
                        tag
                    }
                }
            } catch (_: Exception) {}
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_APP_LANGUAGE, LANGUAGE_FOLLOW_SYSTEM) ?: LANGUAGE_FOLLOW_SYSTEM
    }

    fun setLanguage(context: Context, languageTag: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_APP_LANGUAGE, languageTag) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeManager = context.getSystemService(LocaleManager::class.java)
                if (languageTag.isEmpty()) {
                    localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
                } else {
                    localeManager.applicationLocales = LocaleList.forLanguageTags(languageTag)
                }
            } catch (_: Exception) {}
        }

        val targetLocale = if (languageTag.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
            } else {
                Locale.getDefault()
            }
        } else {
            Locale.forLanguageTag(languageTag)
        }

        // 1. Crucial: Update Application instance resources so CamMonitorApp.instance.getString(...) is updated immediately!
        try {
            val app = CamMonitorApp.instance
            applyLocaleToResources(app, targetLocale)
            applyLocaleToResources(app.applicationContext, targetLocale)
        } catch (_: Exception) {}

        // 2. Update context resources
        try {
            applyLocaleToResources(context.applicationContext, targetLocale)
        } catch (_: Exception) {}
        applyLocaleToResources(context, targetLocale)

        // 3. Immediately recreate the host Activity
        context.findActivity()?.recreate()
    }

    fun applyCurrentLocale(context: Context) {
        val locale = getTargetLocale(context)
        applyLocaleToResources(context, locale)
    }

    fun getLocalizedContext(context: Context): Context {
        val locale = getTargetLocale(context)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        }
        return context.createConfigurationContext(config)
    }

    fun applyLocaleToResources(context: Context, locale: Locale) {
        Locale.setDefault(locale)
        val res = context.resources
        val config = Configuration(res.configuration)
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        }
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)
    }

    fun openSystemLanguageSettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }
}
