package dev.scrcpyandroid

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/** UI languages. [tag] is the BCP 47 tag; [label] is the language's own name (never translated). */
enum class AppLanguage(val tag: String, val label: String) {
    EN("en", "English"),
    ZH("zh", "中文"),
    ;

    companion object {
        /** English unless a known tag was saved. */
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: EN
    }
}

/**
 * The app's own language choice, independent of the system language (default English).
 * Applied per activity through [wrap] in attachBaseContext; changing it recreates the activity.
 */
object LanguageStore {
    const val PREFS = "settings"
    private const val KEY = "language"

    fun get(context: Context): AppLanguage =
        AppLanguage.fromTag(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    /** Synchronous, so an activity recreated right after sees the new value. */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun set(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).commit()
    }

    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(get(base).tag)))
        return base.createConfigurationContext(config)
    }
}
