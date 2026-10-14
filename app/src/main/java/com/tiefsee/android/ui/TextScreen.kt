package com.tiefsee.android.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import com.tiefsee.android.MainActivity
import com.tiefsee.android.data.FileOps
import com.tiefsee.android.util.Bg
import com.tiefsee.android.util.Dialogs
import com.tiefsee.android.util.I18n
import com.tiefsee.android.util.MATCH
import com.tiefsee.android.util.Palette
import com.tiefsee.android.util.dp
import com.tiefsee.android.util.flp
import com.tiefsee.android.util.icon
import com.tiefsee.android.util.iconButton
import com.tiefsee.android.util.lp
import com.tiefsee.android.util.toast
import com.tiefsee.android.util.vbox
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Текстовый редактор и Markdown-редактор с предпросмотром (как MD editor / text editor в Tiefsee). */
class TextScreen(act: MainActivity, private val file: File, private val markdown: Boolean) : Screen(act) {

    override val root = act.vbox()
    private val bar = TopBar(act, onBack = { act.goBack() })
    private val body = FrameLayout(act)
    private val edit = EditText(act)
    private var web: WebView? = null
    private var preview = markdown
    private var dirty = false
    private var charset: Charset = Charsets.UTF_8
    private var fontSize = 14f
    private var wrap = true
    private var readOnly = false
    private lateinit var saveBtn: ImageButton
    private lateinit var modeBtn: ImageButton

    init {
        root.setBackgroundColor(Palette.bg)
        bar.setTitle(file.name)
        if (markdown) {
            modeBtn = act.iconButton("edit", I18n.t("edit")) { setPreview(!preview) }
            bar.add(modeBtn)
        }
        saveBtn = act.iconButton("save", I18n.t("save")) { save() }
        saveBtn.alpha = 0.35f
        bar.add(saveBtn)
        bar.add(act.iconButton("more", I18n.t("more")) { menu(it) })
        root.addView(bar.view, lp())

        edit.setTextColor(Palette.text)
        edit.setBackgroundColor(Color.TRANSPARENT)
        edit.typeface = Typeface.MONOSPACE
        edit.gravity = Gravity.TOP or Gravity.START
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        edit.setHorizontallyScrolling(false)
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        edit.setPadding(act.dp(14), act.dp(10), act.dp(14), act.dp(40))
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!loading) setDirty(true)
            }
        })
        body.addView(edit, flp())
        root.addView(body, lp(MATCH, 0, 1f))
        load()
    }

    private var loading = true

    private fun load() {
        Bg.runCatching({
            val max = 8L * 1024 * 1024
            val bytes = if (file.length() > max) file.inputStream().use { it.readNBytesCompat(max.toInt()) } else file.readBytes()
            val cs = detectCharset(bytes)
            Triple(String(bytes, cs).removePrefix("﻿"), cs, file.length() > max)
        }) { r ->
            r.onSuccess { (text, cs, truncated) ->
                charset = cs
                loading = true
                edit.setText(text)
                loading = false
                readOnly = truncated
                if (truncated) {
                    edit.keyListener = null
                    act.toast(I18n.t("fileTooLarge"))
                }
                bar.setTitle(file.name, cs.displayName() + if (readOnly) " · " + I18n.t("readOnly") else "")
                if (markdown) setPreview(true)
            }.onFailure {
                loading = false
                act.toast(I18n.t("cannotOpen"))
            }
        }
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    /** UTF-8 (с BOM/без), UTF-16 BOM, иначе — региональная кодировка (cp1251 / GBK). */
    private fun detectCharset(b: ByteArray): Charset {
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b, 0, minOf(b.size, 1 shl 20)))
            return Charsets.UTF_8
        } catch (_: CharacterCodingException) {
            // Возможно, файл обрезан посреди символа — проверяем всё целиком только если файл маленький.
        }
        val name = when (I18n.lang) { "zh" -> "GBK"; else -> "windows-1251" }
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.ISO_8859_1)
    }

    private fun setDirty(d: Boolean) {
        dirty = d
        saveBtn.alpha = if (d) 1f else 0.35f
    }

    private fun save() {
        if (!dirty || readOnly) return
        val text = edit.text.toString()
        Bg.runCatching({ file.writeText(text, charset) }) { r ->
            r.onSuccess { setDirty(false); act.toast(I18n.t("saved")); FileOps.scan(act, file) }
                .onFailure { act.toast(I18n.t("saveFailed") + ": " + it.message) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setPreview(on: Boolean) {
        preview = on
        if (markdown) modeBtn.setImageDrawable(act.icon(if (on) "edit" else "eye"))
        if (!on) {
            web?.visibility = View.GONE
            edit.visibility = View.VISIBLE
            return
        }
        val wv = web ?: WebView(act).also { w ->
            w.settings.javaScriptEnabled = true
            w.settings.allowFileAccess = true
            w.setBackgroundColor(Palette.bg)
            w.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    if (u.scheme == "http" || u.scheme == "https" || u.scheme == "mailto") {
                        try { act.startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) { }
                        return true
                    }
                    if (u.scheme == "file") {
                        val f = File(u.path ?: return true)
                        if (f.exists()) act.openFile(f)
                        return true
                    }
                    return true
                }
            }
            body.addView(w, flp())
            web = w
        }
        edit.visibility = View.GONE
        wv.visibility = View.VISIBLE
        val bg = String.format("#%06X", Palette.bg and 0xFFFFFF)
        val fg = String.format("#%06X", Palette.text and 0xFFFFFF)
        val dim = String.format("#%06X", Palette.surface2 and 0xFFFFFF)
        val acc = String.format("#%06X", Palette.accent and 0xFFFFFF)
        val md = JSONObject.quote(edit.text.toString())
        val html = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{background:$bg;color:$fg;font-family:sans-serif;line-height:1.6;padding:12px 16px 48px;margin:0;word-wrap:break-word}
a{color:$acc} img{max-width:100%} hr{border:0;border-top:1px solid $dim}
pre,code{background:$dim;border-radius:6px;font-family:monospace;font-size:.92em}
code{padding:2px 5px} pre{padding:10px;overflow:auto} pre code{padding:0;background:none}
blockquote{margin:0;padding:2px 14px;border-left:4px solid $acc;opacity:.85}
table{border-collapse:collapse;display:block;overflow:auto} td,th{border:1px solid $dim;padding:6px 10px}
h1,h2{border-bottom:1px solid $dim;padding-bottom:.25em}
</style></head><body><div id="c"></div>
<script src="file:///android_asset/marked.min.js"></script>
<script>document.getElementById('c').innerHTML = marked.parse($md);</script>
</body></html>"""
        val base = Uri.fromFile(file.parentFile ?: file).toString() + "/"
        wv.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
    }

    private fun menu(anchor: View) {
        val theme = if (Palette.dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light
        val pm = android.widget.PopupMenu(android.view.ContextThemeWrapper(act, theme), anchor, Gravity.END)
        pm.menu.add(0, 1, 0, I18n.t("fontBigger"))
        pm.menu.add(0, 2, 0, I18n.t("fontSmaller"))
        pm.menu.add(0, 3, 0, I18n.t("wordWrap")).apply { isCheckable = true; isChecked = wrap }
        pm.menu.add(0, 4, 0, I18n.t("share"))
        pm.menu.add(0, 5, 0, I18n.t("openWith"))
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> { fontSize = (fontSize + 2).coerceAtMost(30f); edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize) }
                2 -> { fontSize = (fontSize - 2).coerceAtLeast(8f); edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize) }
                3 -> { wrap = !wrap; edit.setHorizontallyScrolling(!wrap) }
                4 -> FileOps.share(act, listOf(file))
                5 -> FileOps.openWith(act, file)
            }
            true
        }
        pm.show()
    }

    override fun onInsets(top: Int, bottom: Int, left: Int, right: Int) {
        bar.view.setPadding(left + act.dp(4), top, right + act.dp(4), 0)
        body.setPadding(left, 0, right, bottom)
    }

    override fun onBack(): Boolean {
        if (dirty) {
            Dialogs.confirm(act, I18n.t("unsaved"), I18n.t("unsavedText"), I18n.t("discard")) {
                setDirty(false)
                act.pop()
            }
            return true
        }
        return false
    }

    override fun onKey(code: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed && code == KeyEvent.KEYCODE_S) { save(); return true }
        return false
    }

    override fun onDestroy() {
        web?.destroy()
    }
}
