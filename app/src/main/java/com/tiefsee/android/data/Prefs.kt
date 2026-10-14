package com.tiefsee.android.data

import android.content.Context
import android.content.SharedPreferences

/** Все настройки приложения (аналог Setting window в Tiefsee). */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun putStr(key: String, v: String) = sp.edit().putString(key, v).apply()
    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    /** auto | en | ru | zh */
    var language: String
        get() = str("language", "auto")
        set(v) = putStr("language", v)

    /** dark | light | system */
    var theme: String
        get() = str("theme", "dark")
        set(v) = putStr("theme", v)

    var accent: Int
        get() = int("accent", 0xFF8B5CF6.toInt())
        set(v) = putInt("accent", v)

    /** black | theme | checker | white */
    var viewerBackground: String
        get() = str("viewerBg", "black")
        set(v) = putStr("viewerBg", v)

    /** fit | width | height | original */
    var zoomMode: String
        get() = str("zoomMode", "fit")
        set(v) = putStr("zoomMode", v)

    /** Сглаживание при увеличении (false = пиксельный режим, как "pixelated" в Tiefsee). */
    var smoothing: Boolean
        get() = bool("smoothing", true)
        set(v) = putBool("smoothing", v)

    /** name | date | size | type, + направление */
    var sortBy: String
        get() = str("sortBy", "name")
        set(v) = putStr("sortBy", v)

    var sortDesc: Boolean
        get() = bool("sortDesc", false)
        set(v) = putBool("sortDesc", v)

    var dirSortBy: String
        get() = str("dirSortBy", "name")
        set(v) = putStr("dirSortBy", v)

    var dirSortDesc: Boolean
        get() = bool("dirSortDesc", false)
        set(v) = putBool("dirSortDesc", v)

    var showHidden: Boolean
        get() = bool("showHidden", false)
        set(v) = putBool("showHidden", v)

    var showAllFiles: Boolean
        get() = bool("showAllFiles", false)
        set(v) = putBool("showAllFiles", v)

    /** Размер плитки в браузере, dp */
    var gridSize: Int
        get() = int("gridSize", 110)
        set(v) = putInt("gridSize", v)

    var folderPreviews: Boolean
        get() = bool("folderPreviews", true)
        set(v) = putBool("folderPreviews", v)

    var slideshowSeconds: Int
        get() = int("slideshowSec", 4)
        set(v) = putInt("slideshowSec", v)

    var keepScreenOn: Boolean
        get() = bool("keepScreenOn", true)
        set(v) = putBool("keepScreenOn", v)

    var showFileStrip: Boolean
        get() = bool("fileStrip", true)
        set(v) = putBool("fileStrip", v)

    var bulkColumns: Int
        get() = int("bulkColumns", 1)
        set(v) = putInt("bulkColumns", v)

    var bulkGap: Boolean
        get() = bool("bulkGap", true)
        set(v) = putBool("bulkGap", v)

    var videoAutoplay: Boolean
        get() = bool("videoAutoplay", true)
        set(v) = putBool("videoAutoplay", v)

    var videoLoop: Boolean
        get() = bool("videoLoop", true)
        set(v) = putBool("videoLoop", v)

    var confirmDelete: Boolean
        get() = bool("confirmDelete", true)
        set(v) = putBool("confirmDelete", v)

    var openLastFolder: Boolean
        get() = bool("openLastFolder", true)
        set(v) = putBool("openLastFolder", v)

    var lastFolder: String
        get() = str("lastFolder", "")
        set(v) = putStr("lastFolder", v)

    /** Включённые поисковики через запятую. */
    var searchEngines: Set<String>
        get() = str("engines", "saucenao,yandex,ascii2d,google,bing").split(',').filter { it.isNotBlank() }.toSet()
        set(v) = putStr("engines", v.joinToString(","))

    var favorites: List<String>
        get() = str("favorites", "").split('\n').filter { it.isNotBlank() }
        set(v) = putStr("favorites", v.joinToString("\n"))

    var recent: List<String>
        get() = str("recent", "").split('\n').filter { it.isNotBlank() }
        set(v) = putStr("recent", v.joinToString("\n"))

    fun addRecent(path: String) {
        val list = recent.toMutableList()
        list.remove(path)
        list.add(0, path)
        recent = list.take(20)
    }

    fun toggleFavorite(path: String): Boolean {
        val list = favorites.toMutableList()
        val added = if (list.remove(path)) false else { list.add(path); true }
        favorites = list
        return added
    }

    /** Плашка «AI» на картинках с данными генерации. */
    var showAiBadge: Boolean
        get() = bool("aiBadge", true)
        set(v) = putBool("aiBadge", v)

    /** Подсказка о жестах показана. */
    var hintShown: Boolean
        get() = bool("hintShown", false)
        set(v) = putBool("hintShown", v)
}
