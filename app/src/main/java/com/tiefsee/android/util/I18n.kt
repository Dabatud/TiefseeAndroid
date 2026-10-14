package com.tiefsee.android.util

import android.content.Context
import java.util.Locale

/** Локализация без ресурсов: English / Русский / 繁體中文 (как в Tiefsee). Язык меняется на лету. */
object I18n {
    var lang = "en"
        private set

    val languages = listOf("auto", "en", "ru", "zh")

    fun apply(@Suppress("UNUSED_PARAMETER") ctx: Context, pref: String) {
        lang = if (pref == "auto") {
            when (Locale.getDefault().language) {
                "ru", "uk", "be", "kk" -> "ru"
                "zh" -> "zh"
                else -> "en"
            }
        } else pref
    }

    fun t(key: String): String {
        val dict = when (lang) {
            "ru" -> Strings.ru
            "zh" -> Strings.zh
            else -> Strings.en
        }
        return dict[key] ?: Strings.en[key] ?: key
    }

    fun t(key: String, vararg args: Any): String = String.format(t(key), *args)
}
