package com.tiefsee.android.ui

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.tiefsee.android.BuildConfig
import com.tiefsee.android.MainActivity
import com.tiefsee.android.data.Archives
import com.tiefsee.android.data.FileOps
import com.tiefsee.android.data.Prefs
import com.tiefsee.android.formats.Thumbs
import com.tiefsee.android.meta.ImageSearch
import com.tiefsee.android.util.Dialogs
import com.tiefsee.android.util.I18n
import com.tiefsee.android.util.MATCH
import com.tiefsee.android.util.Palette
import com.tiefsee.android.util.WRAP
import com.tiefsee.android.util.dialogTheme
import com.tiefsee.android.util.dp
import com.tiefsee.android.util.dpf
import com.tiefsee.android.util.hbox
import com.tiefsee.android.util.icon
import com.tiefsee.android.util.label
import com.tiefsee.android.util.lp
import com.tiefsee.android.util.ripple
import com.tiefsee.android.util.rounded
import com.tiefsee.android.util.toast
import com.tiefsee.android.util.vbox
import java.io.File

/** Настройки (аналог Setting window). */
class SettingsScreen(act: MainActivity) : Screen(act) {

    override val root = act.vbox()
    private val bar = TopBar(act)
    private val scroll = ScrollView(act)
    private val list = act.vbox()
    private var group: LinearLayout? = null

    init {
        root.setBackgroundColor(Palette.bg)
        bar.setTitle(I18n.t("settings"))
        root.addView(bar.view, lp())
        scroll.addView(list)
        root.addView(scroll, lp(MATCH, 0, 1f))
        build()
    }

    private fun build() {
        list.removeAllViews()
        list.setPadding(act.dp(14), act.dp(4), act.dp(14), act.dp(24))

        section("palette", I18n.t("appearance"))
        choice(I18n.t("language"), I18n.languages, I18n.languages.map { langName(it) }, Prefs.language) {
            Prefs.language = it; act.rebuildUi()
        }
        choice(I18n.t("theme"), listOf("dark", "light", "system"), listOf(I18n.t("themeDark"), I18n.t("themeLight"), I18n.t("themeSystem")), Prefs.theme) {
            Prefs.theme = it; act.rebuildUi()
        }
        accentRow()
        choice(I18n.t("viewerBg"), listOf("black", "theme", "checker", "white"),
            listOf(I18n.t("bgBlack"), I18n.t("bgTheme"), I18n.t("bgChecker"), I18n.t("bgWhite")), Prefs.viewerBackground) {
            Prefs.viewerBackground = it
        }

        section("image", I18n.t("viewer"))
        choice(I18n.t("zoomMode"), listOf("fit", "fitShrink", "width", "height", "original"),
            listOf(I18n.t("zoomFit"), I18n.t("zoomFitShrink"), I18n.t("zoomWidth"), I18n.t("zoomHeight"), I18n.t("zoomOriginal")), Prefs.zoomMode) {
            Prefs.zoomMode = it
        }
        toggle(I18n.t("smoothing"), I18n.t("smoothingHint"), Prefs.smoothing) { Prefs.smoothing = it }
        toggle(I18n.t("fileStrip"), null, Prefs.showFileStrip) { Prefs.showFileStrip = it }
        toggle(I18n.t("aiBadge"), I18n.t("aiBadgeHint"), Prefs.showAiBadge) { Prefs.showAiBadge = it }
        toggle(I18n.t("keepScreenOn"), null, Prefs.keepScreenOn) { Prefs.keepScreenOn = it }
        toggle(I18n.t("confirmDelete"), null, Prefs.confirmDelete) { Prefs.confirmDelete = it }
        choice(I18n.t("slideshowInterval"), listOf("2", "3", "4", "6", "10", "20"),
            listOf("2", "3", "4", "6", "10", "20").map { I18n.t("seconds", it.toInt()) }, Prefs.slideshowSeconds.toString()) {
            Prefs.slideshowSeconds = it.toInt()
        }
        toggle(I18n.t("videoAutoplay"), null, Prefs.videoAutoplay) { Prefs.videoAutoplay = it }
        toggle(I18n.t("videoLoop"), null, Prefs.videoLoop) { Prefs.videoLoop = it }
        toggle(I18n.t("bulkGap"), null, Prefs.bulkGap) { Prefs.bulkGap = it }

        section("folder", I18n.t("files"))
        toggle(I18n.t("folderPreviews"), null, Prefs.folderPreviews) { Prefs.folderPreviews = it }
        toggle(I18n.t("showHidden"), null, Prefs.showHidden) { Prefs.showHidden = it }
        toggle(I18n.t("showAllFiles"), I18n.t("showAllFilesHint"), Prefs.showAllFiles) { Prefs.showAllFiles = it }
        toggle(I18n.t("openLastFolder"), null, Prefs.openLastFolder) { Prefs.openLastFolder = it }
        choice(I18n.t("sortFolders"), listOf("name", "date", "size"),
            listOf(I18n.t("sort_name"), I18n.t("sort_date"), I18n.t("sort_size")), Prefs.dirSortBy) { Prefs.dirSortBy = it }
        toggle(I18n.t("sortFoldersDesc"), null, Prefs.dirSortDesc) { Prefs.dirSortDesc = it }
        choice(I18n.t("gridSize"), listOf("80", "110", "150", "200"),
            listOf(I18n.t("gridSmall"), I18n.t("gridMedium"), I18n.t("gridLarge"), I18n.t("gridHuge")), nearest(Prefs.gridSize)) {
            Prefs.gridSize = it.toInt()
        }

        section("image_search", I18n.t("imageSearch"))
        enginesRow()
        list.addView(act.label(I18n.t("imageSearchNote"), 12f, Palette.textDim).apply {
            setPadding(act.dp(6), act.dp(6), act.dp(6), 0)
        })

        section("archive", I18n.t("storage"))
        action(I18n.t("clearThumbs"), sizeOf(File(act.cacheDir, "thumbs"))) {
            Thumbs.clearDisk(); act.toast(I18n.t("done")); build()
        }
        action(I18n.t("clearArchive"), sizeOf(File(act.cacheDir, "archive"))) {
            Archives.clearCache(act)
            File(act.cacheDir, "shared").deleteRecursively()
            act.toast(I18n.t("done")); build()
        }

        section("info", I18n.t("about"))
        action("Tiefsee for Android", BuildConfig.VERSION_NAME) {
            FileOps.openUrl(act, "https://github.com/hbl917070/Tiefsee4")
        }
        list.addView(act.label(I18n.t("aboutText"), 12f, Palette.textDim).apply {
            setPadding(act.dp(6), act.dp(8), act.dp(6), 0)
        })
    }

    private fun nearest(v: Int): String = listOf(80, 110, 150, 200).minByOrNull { Math.abs(it - v) }.toString()

    private fun langName(code: String) = when (code) {
        "auto" -> I18n.t("langAuto")
        "en" -> "English"
        "ru" -> "Русский"
        "zh" -> "繁體中文"
        else -> code
    }

    private fun sizeOf(dir: File): String {
        val total = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return com.tiefsee.android.data.FileTypes.humanSize(total)
    }

    // ---------- Конструкторы строк ----------

    private fun section(icon: String, title: String) {
        val h = act.hbox()
        h.setPadding(act.dp(6), act.dp(18), 0, act.dp(8))
        h.addView(ImageView(act).apply { setImageDrawable(act.icon(icon, Palette.accent)) }, lp(act.dp(20), act.dp(20)))
        h.addView(act.label(title, 13f, Palette.accent, bold = true).apply {
            setPadding(act.dp(10), 0, 0, 0)
            isAllCaps = true
            letterSpacing = 0.05f
        })
        list.addView(h)
        val g = act.vbox()
        g.background = rounded(Palette.surface, act.dpf(14f))
        list.addView(g, lp())
        group = g
    }

    private fun addRow(v: View) {
        val g = group ?: list
        if (g.childCount > 0) g.addView(View(act).apply { setBackgroundColor(Palette.divider) }, lp(MATCH, 1).apply {
            leftMargin = act.dp(16)
        })
        g.addView(v, lp())
    }

    private fun rowBase(title: String, sub: String?): Pair<LinearLayout, TextView> {
        val row = act.hbox()
        row.setPadding(act.dp(16), act.dp(12), act.dp(12), act.dp(12))
        row.minimumHeight = act.dp(56)
        row.background = ripple(null, act.dpf(14f))
        val texts = act.vbox()
        texts.addView(act.label(title, 15f, Palette.text))
        val s = act.label(sub ?: "", 13f, Palette.textDim)
        if (sub == null) s.visibility = View.GONE
        texts.addView(s)
        row.addView(texts, lp(0, WRAP, 1f))
        return row to s
    }

    private fun choice(title: String, keys: List<String>, labels: List<String>, current: String, onPick: (String) -> Unit) {
        val idx = keys.indexOf(current).coerceAtLeast(0)
        val (row, sub) = rowBase(title, labels.getOrNull(idx))
        row.setOnClickListener {
            val i = keys.indexOf(currentValue(keys, current, row)).coerceAtLeast(0)
            Dialogs.choice(act, title, labels, i) { w ->
                row.tag = keys[w]
                sub.text = labels[w]
                onPick(keys[w])
            }
        }
        addRow(row)
    }

    private fun currentValue(keys: List<String>, initial: String, row: View): String = (row.tag as? String)?.takeIf { it in keys } ?: initial

    private fun toggle(title: String, hint: String?, value: Boolean, onChange: (Boolean) -> Unit) {
        val (row, _) = rowBase(title, hint)
        val sw = Switch(act)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, b -> onChange(b) }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        addRow(row)
    }

    private fun action(title: String, sub: String?, onClick: () -> Unit) {
        val (row, _) = rowBase(title, sub)
        row.setOnClickListener { onClick() }
        addRow(row)
    }

    private fun accentRow() {
        val (row, _) = rowBase(I18n.t("accent"), null)
        val dots = act.hbox()
        for (c in Palette.accents) {
            val d = View(act)
            d.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                if (c == Prefs.accent) setStroke(act.dp(3), Palette.text)
            }
            d.setOnClickListener { Prefs.accent = c; act.rebuildUi() }
            dots.addView(d, lp(act.dp(26), act.dp(26)).apply { leftMargin = act.dp(6) })
        }
        row.addView(dots)
        addRow(row)
    }

    private fun enginesRow() {
        val (row, sub) = rowBase(I18n.t("searchEngines"), "")
        fun refresh() {
            sub.text = ImageSearch.engines.filter { it.id in Prefs.searchEngines }.joinToString(", ") { it.name }.ifEmpty { "—" }
            sub.visibility = View.VISIBLE
        }
        refresh()
        row.setOnClickListener {
            val engines = ImageSearch.engines
            val checked = BooleanArray(engines.size) { engines[it].id in Prefs.searchEngines }
            AlertDialog.Builder(act, dialogTheme())
                .setTitle(I18n.t("searchEngines"))
                .setMultiChoiceItems(engines.map { it.name }.toTypedArray(), checked) { _, w, on -> checked[w] = on }
                .setPositiveButton(I18n.t("ok")) { _, _ ->
                    Prefs.searchEngines = engines.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                    refresh()
                }
                .setNegativeButton(I18n.t("cancel"), null)
                .show()
        }
        addRow(row)
    }

    override fun onInsets(top: Int, bottom: Int, left: Int, right: Int) {
        bar.view.setPadding(left + act.dp(4), top, right + act.dp(4), 0)
        scroll.setPadding(left, 0, right, bottom)
        scroll.clipToPadding = false
    }
}
