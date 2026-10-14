package com.tiefsee.android.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.tiefsee.android.data.Prefs

/** Цветовая схема. Тёмная тема повторяет оформление Tiefsee (#1F272B). */
object Palette {
    var dark = true; private set
    var bg = 0; private set
    var surface = 0; private set
    var surface2 = 0; private set
    var text = 0; private set
    var textDim = 0; private set
    var divider = 0; private set
    var accent = 0; private set
    var onAccent = Color.WHITE; private set
    var barBg = 0; private set
    var danger = 0xFFE5484D.toInt(); private set

    fun apply(ctx: Context) {
        dark = when (Prefs.theme) {
            "light" -> false
            "system" -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
        accent = Prefs.accent
        if (dark) {
            bg = 0xFF161C1F.toInt()
            surface = 0xFF1F272B.toInt()
            surface2 = 0xFF2A3439.toInt()
            text = 0xFFF1F3F4.toInt()
            textDim = 0xFF9AA5AB.toInt()
            divider = 0x22FFFFFF
            barBg = 0xE61F272B.toInt()
        } else {
            bg = 0xFFF4F5F7.toInt()
            surface = 0xFFFFFFFF.toInt()
            surface2 = 0xFFE9ECEF.toInt()
            text = 0xFF1B1F23.toInt()
            textDim = 0xFF5F6B73.toInt()
            divider = 0x22000000
            barBg = 0xF0FFFFFF.toInt()
        }
        onAccent = if (luminance(accent) > 0.6) Color.BLACK else Color.WHITE
    }

    fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun luminance(c: Int): Double {
        val r = Color.red(c) / 255.0; val g = Color.green(c) / 255.0; val b = Color.blue(c) / 255.0
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    val accents = intArrayOf(
        0xFF8B5CF6.toInt(), // фиолетовый (логотип)
        0xFF00C8FF.toInt(), // голубой (Tiefsee)
        0xFF22C55E.toInt(),
        0xFFF59E0B.toInt(),
        0xFFEF4444.toInt(),
        0xFFEC4899.toInt(),
        0xFF64748B.toInt(),
    )
}
