package com.tiefsee.android.ui

import android.text.TextUtils
import android.util.Base64
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.tiefsee.android.MainActivity
import com.tiefsee.android.data.FileOps
import com.tiefsee.android.data.FileTypes
import com.tiefsee.android.util.Bg
import com.tiefsee.android.util.I18n
import com.tiefsee.android.util.MATCH
import com.tiefsee.android.util.Palette
import com.tiefsee.android.util.WRAP
import com.tiefsee.android.util.dp
import com.tiefsee.android.util.flp
import com.tiefsee.android.util.iconButton
import com.tiefsee.android.util.lp
import com.tiefsee.android.util.vbox
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Просмотр DOCX/PPTX: текст, заголовки, жирный/курсив, таблицы и картинки (как docx/pptx reader в Tiefsee). */
class OfficeScreen(act: MainActivity, private val file: File) : Screen(act) {

    override val root = act.vbox()
    private val bar = TopBar(act)
    private val web = WebView(act)
    private val spinner = ProgressBar(act).apply { isIndeterminate = true }

    init {
        root.setBackgroundColor(Palette.bg)
        bar.setTitle(file.name)
        bar.add(act.iconButton("share", I18n.t("share")) { FileOps.share(act, listOf(file)) })
        bar.add(act.iconButton("open_in", I18n.t("openWith")) { FileOps.openWith(act, file) })
        root.addView(bar.view, lp())
        val body = FrameLayout(act)
        web.setBackgroundColor(Palette.bg)
        web.settings.javaScriptEnabled = false
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        body.addView(web, flp())
        body.addView(spinner, flp(WRAP, WRAP, Gravity.CENTER))
        root.addView(body, lp(MATCH, 0, 1f))

        Bg.runCatching({ if (FileTypes.ext(file.name) == "pptx") pptx() else docx() }) { r ->
            spinner.visibility = View.GONE
            val content = r.getOrElse { "<p>${I18n.t("cannotOpen")}: ${TextUtils.htmlEncode(it.message ?: "")}</p>" }
            web.loadDataWithBaseURL(null, page(content), "text/html", "utf-8", null)
        }
    }

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    private fun page(content: String) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{background:${hex(Palette.bg)};color:${hex(Palette.text)};font-family:sans-serif;line-height:1.55;padding:12px 16px 48px;margin:0}
table{border-collapse:collapse;margin:8px 0;display:block;overflow:auto}td{border:1px solid ${hex(Palette.surface2)};padding:4px 8px;vertical-align:top}
img{max-width:100%;height:auto}.slide{background:${hex(Palette.surface)};border-radius:12px;padding:12px 16px;margin:0 0 14px}
.slide h3{margin:0 0 8px;color:${hex(Palette.accent)};font-size:13px;text-transform:uppercase}</style></head><body>$content</body></html>"""

    private fun esc(s: String) = TextUtils.htmlEncode(s)

    // ---------- DOCX ----------

    private fun docx(): String {
        ZipFile(file).use { zip ->
            val rels = rels(zip, "word/_rels/document.xml.rels", "word/")
            val entry = zip.getEntry("word/document.xml") ?: throw IllegalStateException("document.xml")
            return zip.getInputStream(entry).use { parseDocx(it, zip, rels) }
        }
    }

    private fun parseDocx(input: InputStream, zip: ZipFile, rels: Map<String, String>): String {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        val out = StringBuilder()
        val para = StringBuilder()
        var style = ""
        var bold = false
        var italic = false
        var inTable = 0
        var listItem = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            val n = p.name ?: ""
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (n) {
                    "w:p" -> { para.setLength(0); style = ""; listItem = false }
                    "w:pStyle" -> style = p.getAttributeValue(null, "w:val") ?: ""
                    "w:numPr" -> listItem = true
                    "w:r" -> { bold = false; italic = false }
                    "w:b" -> bold = p.getAttributeValue(null, "w:val") != "0"
                    "w:i" -> italic = p.getAttributeValue(null, "w:val") != "0"
                    "w:t" -> {
                        var t = esc(p.nextText())
                        if (bold) t = "<b>$t</b>"
                        if (italic) t = "<i>$t</i>"
                        para.append(t)
                    }
                    "w:tab" -> para.append("&emsp;")
                    "w:br" -> para.append("<br>")
                    "a:blip" -> {
                        val id = p.getAttributeValue(null, "r:embed")
                        rels[id]?.let { para.append(imageTag(zip, it)) }
                    }
                    "w:tbl" -> { inTable++; out.append("<table>") }
                    "w:tr" -> out.append("<tr>")
                    "w:tc" -> out.append("<td>")
                }
                XmlPullParser.END_TAG -> when (n) {
                    "w:p" -> {
                        val s = style.lowercase()
                        val tag = when {
                            s.startsWith("heading1") || s == "title" -> "h1"
                            s.startsWith("heading2") || s == "subtitle" -> "h2"
                            s.startsWith("heading3") -> "h3"
                            s.startsWith("heading") -> "h4"
                            else -> "p"
                        }
                        if (inTable > 0) out.append(para).append("<br>")
                        else if (listItem && tag == "p") out.append("<p>• ").append(para).append("</p>")
                        else out.append("<$tag>").append(para).append("</$tag>")
                    }
                    "w:tc" -> out.append("</td>")
                    "w:tr" -> out.append("</tr>")
                    "w:tbl" -> { inTable--; out.append("</table>") }
                }
            }
        }
        return out.toString()
    }

    // ---------- PPTX ----------

    private fun pptx(): String {
        ZipFile(file).use { zip ->
            val slides = zip.entries().toList().map { it.name }
                .filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { it.removePrefix("ppt/slides/slide").removeSuffix(".xml").toInt() }
            val out = StringBuilder()
            for ((i, name) in slides.withIndex()) {
                val relName = name.replace("ppt/slides/", "ppt/slides/_rels/") + ".rels"
                val rels = rels(zip, relName, "ppt/slides/")
                out.append("<div class='slide'><h3>${I18n.t("slide")} ${i + 1}</h3>")
                zip.getInputStream(zip.getEntry(name)).use { out.append(parsePptxSlide(it, zip, rels)) }
                out.append("</div>")
            }
            return out.toString()
        }
    }

    private fun parsePptxSlide(input: InputStream, zip: ZipFile, rels: Map<String, String>): String {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        val out = StringBuilder()
        val para = StringBuilder()
        var bold = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            val n = p.name ?: ""
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (n) {
                    "a:p" -> para.setLength(0)
                    "a:rPr" -> bold = p.getAttributeValue(null, "b") == "1"
                    "a:t" -> { val t = esc(p.nextText()); para.append(if (bold) "<b>$t</b>" else t) }
                    "a:br" -> para.append("<br>")
                    "a:blip" -> p.getAttributeValue(null, "r:embed")?.let { id -> rels[id]?.let { out.append(imageTag(zip, it)) } }
                }
                XmlPullParser.END_TAG -> if (n == "a:p" && para.isNotBlank()) out.append("<p>").append(para).append("</p>")
            }
        }
        return out.toString()
    }

    // ---------- общее ----------

    /** id → путь внутри архива. */
    private fun rels(zip: ZipFile, path: String, baseDir: String): Map<String, String> {
        val e = zip.getEntry(path) ?: return emptyMap()
        val map = HashMap<String, String>()
        zip.getInputStream(e).use { input ->
            val p = Xml.newPullParser()
            p.setInput(input, null)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.getAttributeValue(null, "Id") ?: continue
                    val target = p.getAttributeValue(null, "Target") ?: continue
                    if (p.getAttributeValue(null, "TargetMode") == "External") continue
                    map[id] = normalize(if (target.startsWith("/")) target.removePrefix("/") else baseDir + target)
                }
            }
        }
        return map
    }

    private fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (s in path.split('/')) {
            when (s) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                ".", "" -> {}
                else -> parts.add(s)
            }
        }
        return parts.joinToString("/")
    }

    private fun imageTag(zip: ZipFile, path: String): String {
        val e = zip.getEntry(path) ?: return ""
        if (e.size > 8 * 1024 * 1024) return ""
        val ext = FileTypes.ext(path)
        val mime = when (ext) { "png" -> "image/png"; "gif" -> "image/gif"; "jpg", "jpeg" -> "image/jpeg"; "svg" -> "image/svg+xml"; "webp" -> "image/webp"; "bmp" -> "image/bmp"; else -> return "" }
        val b64 = zip.getInputStream(e).use { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
        return "<img src=\"data:$mime;base64,$b64\">"
    }

    override fun onInsets(top: Int, bottom: Int, left: Int, right: Int) {
        bar.view.setPadding(left + act.dp(4), top, right + act.dp(4), 0)
        web.setPadding(left, 0, right, bottom)
    }

    override fun onDestroy() = web.destroy()
}
