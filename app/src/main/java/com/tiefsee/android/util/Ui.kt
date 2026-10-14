package com.tiefsee.android.util

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun View.dp(v: Int): Int = context.dp(v)

fun Context.icon(name: String, tint: Int = Palette.text): Drawable? {
    val id = resources.getIdentifier("ic_$name", "drawable", packageName)
    if (id == 0) return null
    val d = getDrawable(id)?.mutate() ?: return null
    d.setTint(tint)
    return d
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

fun ripple(content: Drawable?, radius: Float = 0f, oval: Boolean = false): RippleDrawable {
    val mask = if (oval) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
    else rounded(Color.WHITE, radius)
    val rippleColor = if (Palette.dark) 0x33FFFFFF else 0x22000000
    return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
}

fun Context.iconButton(name: String, desc: String, tint: Int = Palette.text, onClick: (View) -> Unit): ImageButton =
    ImageButton(this).apply {
        setImageDrawable(icon(name, tint))
        contentDescription = desc
        tooltipText = desc
        background = ripple(null, oval = true)
        scaleType = ImageView.ScaleType.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        setOnClickListener(onClick)
        setOnLongClickListener { context.toast(desc); true }
    }

fun Context.label(
    txt: CharSequence = "",
    size: Float = 15f,
    color: Int = Palette.text,
    bold: Boolean = false,
    lines: Int = 0,
): TextView = TextView(this).apply {
    text = txt
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
}

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun flp(w: Int = MATCH, h: Int = MATCH, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)

fun View.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
    (layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(l, t, r, b)
    return this
}

/** Кнопка-«пилюля» с текстом. */
fun Context.pillButton(txt: String, primary: Boolean = false, onClick: (View) -> Unit): TextView =
    label(txt, 14f, if (primary) Palette.onAccent else Palette.text, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = ripple(rounded(if (primary) Palette.accent else Palette.surface2, dpf(22f)), dpf(22f))
        setOnClickListener(onClick)
    }

fun dialogTheme(): Int =
    if (Palette.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert

object Dialogs {
    fun confirm(ctx: Context, title: String, message: String, ok: String = I18n.t("ok"), onOk: () -> Unit) {
        AlertDialog.Builder(ctx, dialogTheme())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .setNegativeButton(I18n.t("cancel"), null)
            .show()
    }

    fun input(ctx: Context, title: String, initial: String, selectUntil: Int = -1, onOk: (String) -> Unit) {
        val edit = EditText(ctx).apply {
            setText(initial)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            if (selectUntil > 0) setSelection(0, selectUntil.coerceAtMost(initial.length)) else selectAll()
        }
        val box = FrameLayout(ctx).apply {
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
            addView(edit)
        }
        val dlg = AlertDialog.Builder(ctx, dialogTheme())
            .setTitle(title)
            .setView(box)
            .setPositiveButton(I18n.t("ok")) { _, _ -> onOk(edit.text.toString()) }
            .setNegativeButton(I18n.t("cancel"), null)
            .create()
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        edit.requestFocus()
    }

    fun choice(ctx: Context, title: String, items: List<String>, checked: Int = -1, onPick: (Int) -> Unit) {
        val b = AlertDialog.Builder(ctx, dialogTheme()).setTitle(title)
        if (checked >= 0) {
            b.setSingleChoiceItems(items.toTypedArray(), checked) { d, w -> d.dismiss(); onPick(w) }
        } else {
            b.setItems(items.toTypedArray()) { _, w -> onPick(w) }
        }
        b.setNegativeButton(I18n.t("cancel"), null).show()
    }

    fun message(ctx: Context, title: String, message: String) {
        AlertDialog.Builder(ctx, dialogTheme())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(I18n.t("ok"), null)
            .show()
    }
}

/** Фоновые потоки + главный поток. */
object Bg {
    val main = Handler(Looper.getMainLooper())
    val io: ExecutorService = Executors.newFixedThreadPool(3)
    val single: ExecutorService = Executors.newSingleThreadExecutor()

    fun <T> run(work: () -> T, done: (T) -> Unit): Future<*> = io.submit {
        val r = try {
            Result.success(work())
        } catch (t: Throwable) {
            Result.failure(t)
        }
        main.post {
            r.onSuccess(done).onFailure { it.printStackTrace() }
        }
    }

    fun <T> runCatching(work: () -> T, done: (Result<T>) -> Unit): Future<*> = io.submit {
        val r = try {
            Result.success(work())
        } catch (t: Throwable) {
            Result.failure(t)
        }
        main.post { done(r) }
    }

    fun ui(block: () -> Unit) = main.post(block)
}

fun View.setBg(color: Int) = setBackgroundColor(color)
fun colorDrawable(c: Int) = ColorDrawable(c)

/** Полупрозрачная плашка «AI» в скруглённом прямоугольнике. */
fun Context.aiBadge(small: Boolean = false): TextView = label("AI", if (small) 10f else 12f, android.graphics.Color.WHITE, bold = true).apply {
    letterSpacing = 0.08f
    gravity = Gravity.CENTER
    setPadding(dp(if (small) 6 else 9), dp(if (small) 1 else 3), dp(if (small) 6 else 9), dp(if (small) 1 else 3))
    background = rounded(0x8C000000.toInt(), dpf(if (small) 6f else 9f), 0x55FFFFFF, dp(1))
}
