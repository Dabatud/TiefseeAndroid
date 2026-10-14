package com.tiefsee.android.ui

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.tiefsee.android.MainActivity
import com.tiefsee.android.data.FileOps
import com.tiefsee.android.meta.FileMeta
import com.tiefsee.android.meta.Metadata
import com.tiefsee.android.util.Bg
import com.tiefsee.android.util.I18n
import com.tiefsee.android.util.Palette
import com.tiefsee.android.util.dp
import com.tiefsee.android.util.dpf
import com.tiefsee.android.util.hbox
import com.tiefsee.android.util.icon
import com.tiefsee.android.util.iconButton
import com.tiefsee.android.util.label
import com.tiefsee.android.util.lp
import com.tiefsee.android.util.ripple
import com.tiefsee.android.util.rounded
import com.tiefsee.android.util.vbox
import java.io.File
import java.util.Locale
import java.util.concurrent.Future

/** Информационная панель (EXIF, AI-промпты, GPS) — как Information Panel в Tiefsee. */
class InfoPanel(private val act: MainActivity) {

    val scroll = ScrollView(act)
    private val content: LinearLayout = act.vbox()
    private var task: Future<*>? = null
    var currentFile: File? = null
        private set

    init {
        scroll.addView(content)
        scroll.isFillViewport = true
        content.setPadding(act.dp(14), act.dp(6), act.dp(14), act.dp(24))
    }

    fun setBottomPadding(px: Int) {
        content.setPadding(act.dp(14), act.dp(6), act.dp(14), act.dp(24) + px)
    }

    fun show(file: File) {
        if (file == currentFile && content.childCount > 0) return
        currentFile = file
        task?.cancel(true)
        content.removeAllViews()
        content.addView(ProgressBar(act).apply { isIndeterminate = true }, lp(com.tiefsee.android.util.WRAP).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = act.dp(24)
        })
        task = Bg.runCatching({ Metadata.read(file) { I18n.t(it) } }) { r ->
            if (currentFile != file) return@runCatching
            content.removeAllViews()
            r.onSuccess { render(it) }.onFailure {
                content.addView(act.label(I18n.t("error") + ": " + it.message, 14f, Palette.textDim))
            }
        }
    }

    fun reset() {
        currentFile = null
        task?.cancel(true)
        content.removeAllViews()
    }

    private fun render(m: FileMeta) {
        val ai = m.ai
        if (ai != null && ai.fields.isNotEmpty()) {
            header("robot", "${I18n.t("aiPrompt")} · ${ai.source}") {
                FileOps.copyText(act, ai.fields.joinToString("\n\n") { "${it.title}:\n${it.text}" })
            }
            val compact = act.vbox()
            for (f in ai.fields) {
                if (f.long) {
                    // Длинные значения — отдельная карточка с копированием
                    flushCompact(compact)
                    card(f.title, f.text, mono = f.title.contains("JSON"))
                } else compact.addView(row(f.title, f.text))
            }
            flushCompact(compact)
        }

        header("file", I18n.t("file"), null)
        val basic = act.vbox()
        for ((k, v) in m.basic) basic.addView(row(k, v))
        content.addView(group(basic))

        if (m.exif.isNotEmpty()) {
            header("camera", "EXIF", null)
            val ex = act.vbox()
            for ((k, v) in m.exif) ex.addView(row(k, v))
            content.addView(group(ex))
        }

        m.gps?.let { gps ->
            header("map", I18n.t("location"), null)
            val coords = String.format(Locale.US, "%.6f, %.6f", gps[0], gps[1])
            val box = act.hbox()
            box.addView(act.label(coords, 14f, Palette.text).apply { setTextIsSelectable(true) }, lp(0, com.tiefsee.android.util.WRAP, 1f))
            box.addView(act.iconButton("copy", I18n.t("copy")) { FileOps.copyText(act, coords) })
            box.addView(act.iconButton("open_in", I18n.t("openMap")) {
                FileOps.openUrl(act, "geo:${gps[0]},${gps[1]}?q=${gps[0]},${gps[1]}")
            })
            content.addView(group(box))
        }

        for ((k, v) in m.texts) card(k, v, mono = v.trimStart().startsWith("{"))
    }

    private fun flushCompact(compact: LinearLayout) {
        if (compact.childCount == 0) return
        val copy = act.vbox()
        while (compact.childCount > 0) {
            val c = compact.getChildAt(0)
            compact.removeViewAt(0)
            copy.addView(c)
        }
        content.addView(group(copy))
    }

    private fun header(iconName: String, title: String, onCopy: (() -> Unit)?) {
        val h = act.hbox()
        h.setPadding(0, act.dp(14), 0, act.dp(6))
        h.addView(android.widget.ImageView(act).apply { setImageDrawable(act.icon(iconName, Palette.accent)) }, lp(act.dp(20), act.dp(20)))
        h.addView(act.label(title, 13f, Palette.accent, bold = true).apply {
            setPadding(act.dp(8), 0, 0, 0)
            isAllCaps = true
            letterSpacing = 0.05f
        }, lp(0, com.tiefsee.android.util.WRAP, 1f))
        if (onCopy != null) {
            h.addView(act.label(I18n.t("copyAll"), 13f, Palette.accent).apply {
                setPadding(act.dp(10), act.dp(6), act.dp(10), act.dp(6))
                background = ripple(null, act.dpf(14f))
                setOnClickListener { onCopy() }
            })
        }
        content.addView(h)
    }

    private fun group(inner: View): View {
        inner.background = rounded(Palette.surface2, act.dpf(12f))
        inner.setPadding(act.dp(12), act.dp(6), act.dp(12), act.dp(6))
        inner.layoutParams = lp().apply { bottomMargin = act.dp(4) }
        return inner
    }

    private fun row(k: String, v: String): View {
        val r = act.vbox()
        r.setPadding(0, act.dp(6), 0, act.dp(6))
        r.addView(act.label(k, 12f, Palette.textDim))
        r.addView(act.label(v, 14f, Palette.text).apply { setTextIsSelectable(true) })
        r.setOnLongClickListener { FileOps.copyText(act, v); true }
        return r
    }

    private fun card(title: String, text: String, mono: Boolean = false) {
        val box = act.vbox()
        val head = act.hbox()
        head.addView(act.label(title, 12f, Palette.textDim, bold = true), lp(0, com.tiefsee.android.util.WRAP, 1f))
        head.addView(act.iconButton("copy", I18n.t("copy"), Palette.textDim) { FileOps.copyText(act, text) }.apply {
            layoutParams = LinearLayout.LayoutParams(act.dp(36), act.dp(36))
        })
        box.addView(head)
        val tv: TextView = act.label(text, if (mono) 12f else 14f, Palette.text).apply {
            setTextIsSelectable(true)
            if (mono) typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.15f)
        }
        // Очень длинные JSON сворачиваем
        if (mono && text.length > 1500) {
            tv.maxLines = 12
            val more = act.label(I18n.t("showAll"), 13f, Palette.accent).apply {
                setPadding(0, act.dp(6), 0, act.dp(4))
                setOnClickListener {
                    tv.maxLines = Int.MAX_VALUE
                    visibility = View.GONE
                }
            }
            box.addView(tv)
            box.addView(more)
        } else box.addView(tv)
        box.setPadding(act.dp(12), act.dp(2), act.dp(4), act.dp(10))
        box.background = rounded(Palette.surface2, act.dpf(12f))
        content.addView(box, lp().apply { bottomMargin = act.dp(6) })
    }
}
