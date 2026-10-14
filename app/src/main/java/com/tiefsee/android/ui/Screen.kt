package com.tiefsee.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.tiefsee.android.MainActivity
import com.tiefsee.android.util.I18n
import com.tiefsee.android.util.Palette
import com.tiefsee.android.util.hbox
import com.tiefsee.android.util.iconButton
import com.tiefsee.android.util.label
import com.tiefsee.android.util.lp
import com.tiefsee.android.util.vbox
import com.tiefsee.android.util.dp

/** Экран приложения (вместо Fragment — без зависимостей). */
abstract class Screen(val act: MainActivity) {
    abstract val root: View

    /** Светлые системные панели (тёмные значки). */
    open val lightSystemBars: Boolean get() = !Palette.dark

    open fun onInsets(top: Int, bottom: Int, left: Int, right: Int) {}
    open fun onBack(): Boolean = false
    open fun onShow() {}
    open fun onHide() {}
    open fun onDestroy() {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onConfigChanged() {}
    open fun onKey(code: Int, event: KeyEvent): Boolean = false
}

/** Верхняя панель: [назад] Заголовок/подзаголовок [кнопки...] */
class TopBar(act: MainActivity, showBack: Boolean = true, onBack: (() -> Unit)? = null) {
    val view: LinearLayout = act.hbox()
    val title: TextView = act.label("", 17f, Palette.text, bold = true, lines = 1)
    val subtitle: TextView = act.label("", 12f, Palette.textDim, lines = 1)
    val actions: LinearLayout = act.hbox()
    val back = act.iconButton("back", I18n.t("back")) { if (onBack != null) onBack() else act.goBack() }

    init {
        view.setPadding(act.dp(4), 0, act.dp(4), 0)
        view.minimumHeight = act.dp(56)
        if (showBack) view.addView(back)
        val titles = act.vbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(if (showBack) 4 else 12), 0, act.dp(4), 0)
            addView(title)
            addView(subtitle)
        }
        subtitle.visibility = View.GONE
        view.addView(titles, lp(0, act.dp(56), 1f))
        view.addView(actions)
        view.setBackgroundColor(Palette.surface)
        view.elevation = act.dp(2).toFloat()
    }

    fun setTitle(t: String, sub: String? = null) {
        title.text = t
        subtitle.text = sub ?: ""
        subtitle.visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun add(v: View): TopBar {
        actions.addView(v)
        return this
    }

    /** Отступ под статус-бар. */
    fun inset(top: Int, left: Int, right: Int) {
        view.setPadding(left + view.context.resources.displayMetrics.density.times(4).toInt(), top, right + view.context.resources.displayMetrics.density.times(4).toInt(), 0)
    }
}

/** Шахматный фон для прозрачных изображений (как в Tiefsee). */
class CheckerDrawable(cell: Int, c1: Int, c2: Int) : Drawable() {
    private val paint = Paint()

    init {
        val bmp = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        p.color = c1
        c.drawRect(0f, 0f, cell * 2f, cell * 2f, p)
        p.color = c2
        c.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
        c.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, p)
        paint.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun draw(canvas: Canvas) = canvas.drawRect(bounds, paint)
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
