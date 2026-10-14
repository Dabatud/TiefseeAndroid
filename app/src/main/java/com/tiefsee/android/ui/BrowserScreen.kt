package com.tiefsee.android.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.tiefsee.android.BuildConfig
import com.tiefsee.android.MainActivity
import com.tiefsee.android.data.Entry
import com.tiefsee.android.data.FileLister
import com.tiefsee.android.data.FileOps
import com.tiefsee.android.data.FileTypes
import com.tiefsee.android.data.Kind
import com.tiefsee.android.data.Prefs
import com.tiefsee.android.data.Storage
import com.tiefsee.android.formats.Imaging
import com.tiefsee.android.formats.Thumbs
import com.tiefsee.android.meta.Metadata
import com.tiefsee.android.util.aiBadge
import com.tiefsee.android.util.Bg
import com.tiefsee.android.util.Dialogs
import com.tiefsee.android.util.I18n
import com.tiefsee.android.util.MATCH
import com.tiefsee.android.util.Palette
import com.tiefsee.android.util.WRAP
import com.tiefsee.android.util.dp
import com.tiefsee.android.util.dpf
import com.tiefsee.android.util.flp
import com.tiefsee.android.util.hbox
import com.tiefsee.android.util.icon
import com.tiefsee.android.util.iconButton
import com.tiefsee.android.util.label
import com.tiefsee.android.util.lp
import com.tiefsee.android.util.ripple
import com.tiefsee.android.util.rounded
import com.tiefsee.android.util.toast
import com.tiefsee.android.util.vbox
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Файловый браузер: панель папок + панель файлов с миниатюрами (Folder Panel / File Panel из Tiefsee). */
class BrowserScreen(act: MainActivity, startDir: File, private val highlight: File? = null) : Screen(act) {

    var currentDir: File = startDir
        private set

    private class HistoryItem(val dir: File, val pos: Int)

    private val history = ArrayList<HistoryItem>()
    private var items: List<Entry> = emptyList()
    private var allItems: List<Entry> = emptyList()
    private var filter = ""

    override val root = FrameLayout(act)
    private val column = act.vbox()
    private val bar = TopBar(act, showBack = false)
    private val searchRow = act.hbox()
    private val searchEdit = EditText(act)
    private val grid = GridView(act)
    private val empty: TextView = act.label("", 15f, Palette.textDim).apply { gravity = Gravity.CENTER }
    private val loading = ProgressBar(act).apply { isIndeterminate = true; visibility = View.GONE }
    private val adapter = GridAdapter()

    private val drawerScrim = View(act)
    private val drawer = ScrollView(act)
    private val drawerContent = act.vbox()
    private var drawerOpen = false
    private var insetsArr = IntArray(4)

    private val folderPreview = ConcurrentHashMap<String, String>() // путь папки -> путь картинки ("" если нет)
    private var listGen = 0

    init {
        root.setBackgroundColor(Palette.bg)
        root.addView(column, flp())

        val menuBtn = act.iconButton("menu", I18n.t("menu")) { toggleDrawer() }
        bar.view.addView(menuBtn, 0)
        bar.title.setOnClickListener { jumpUpDialog() }
        bar.subtitle.setOnClickListener { jumpUpDialog() }
        bar.add(act.iconButton("search", I18n.t("search")) { toggleSearch() })
        bar.add(act.iconButton("sort", I18n.t("sort")) { sortDialog() })
        val more = act.iconButton("more", I18n.t("more")) { showMore(it) }
        bar.add(more)
        column.addView(bar.view, lp())

        buildSearchRow()
        column.addView(searchRow, lp())

        val body = FrameLayout(act)
        grid.adapter = adapter
        grid.isFastScrollEnabled = true
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.clipToPadding = false
        grid.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        grid.setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { open(it) } }
        grid.setOnItemLongClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { itemMenu(it) }; true }
        setupPinch()
        body.addView(grid, flp())
        body.addView(empty, flp(MATCH, WRAP, Gravity.CENTER))
        body.addView(loading, flp(WRAP, WRAP, Gravity.CENTER))
        column.addView(body, lp(MATCH, 0, 1f))

        buildDrawer()
        load(startDir, highlight)
    }

    // ---------- Загрузка списка ----------

    private fun load(dir: File, focus: File? = null, restorePos: Int = -1) {
        currentDir = dir
        val gen = ++listGen
        bar.setTitle(dir.name.ifEmpty { "/" }, Storage.displayPath(act, dir))
        if (items.isEmpty()) loading.visibility = View.VISIBLE
        Bg.runCatching({ FileLister.list(dir) }) { r ->
            if (gen != listGen) return@runCatching
            loading.visibility = View.GONE
            r.onSuccess { listing ->
                allItems = listing.dirs + listing.files
                applyFilter()
                when {
                    focus != null -> items.indexOfFirst { it.file == focus }.takeIf { it >= 0 }?.let { grid.setSelection(it) }
                    restorePos >= 0 -> grid.setSelection(restorePos.coerceAtMost(items.size - 1))
                    else -> grid.setSelection(0)
                }
                Prefs.lastFolder = dir.absolutePath
            }.onFailure {
                allItems = emptyList()
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val q = filter.trim().lowercase()
        items = if (q.isEmpty()) allItems else allItems.filter { it.name.lowercase().contains(q) }
        adapter.notifyDataSetChanged()
        empty.text = when {
            !currentDir.canRead() -> I18n.t("noAccess")
            q.isNotEmpty() -> I18n.t("nothingFound")
            else -> I18n.t("emptyFolder")
        }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    fun refresh() {
        val pos = grid.firstVisiblePosition
        load(currentDir, restorePos = pos)
    }

    private fun navigate(dir: File) {
        history.add(HistoryItem(currentDir, grid.firstVisiblePosition))
        if (filter.isNotEmpty()) { filter = ""; searchEdit.setText(""); searchRow.visibility = View.GONE }
        load(dir)
    }

    private fun open(e: Entry) {
        when (e.kind) {
            Kind.DIR -> navigate(e.file)
            Kind.IMAGE, Kind.SPECIAL, Kind.SVG, Kind.VIDEO -> {
                val media = items.filter { it.kind.isMedia }.map { it.file }
                act.openFile(e.file, media)
            }
            else -> act.openFile(e.file)
        }
    }

    // ---------- Сетка ----------

    private fun cellSize(): Int = act.dp(Prefs.gridSize)

    private fun updateColumns() {
        val w = root.width - insetsArr[2] - insetsArr[3]
        if (w <= 0) return
        val cols = (w / cellSize()).coerceIn(2, 12)
        grid.numColumns = cols
        grid.horizontalSpacing = act.dp(4)
        grid.verticalSpacing = act.dp(4)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPinch() {
        val det = ScaleGestureDetector(act, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            var acc = 1f
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { acc = 1f; return true }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                acc *= detector.scaleFactor
                if (acc > 1.25f || acc < 0.8f) {
                    Prefs.gridSize = (Prefs.gridSize * acc).toInt().coerceIn(60, 320)
                    acc = 1f
                    updateColumns()
                }
                return true
            }
        })
        grid.setOnTouchListener { _, ev -> det.onTouchEvent(ev); det.isInProgress }
        root.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or2, _ -> if (r - l != or2 - ol) updateColumns() }
    }

    private inner class Cell : FrameLayout(act) {
        val image = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val typeIcon = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val folderBadge = ImageView(act)
        val name: TextView = act.label("", 12f, Color.WHITE, lines = 2).apply {
            setPadding(act.dp(6), act.dp(14), act.dp(6), act.dp(5))
            setShadowLayer(3f, 0f, 1f, 0xAA000000.toInt())
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
        }
        val badge: TextView = act.label("", 10f, Color.WHITE, bold = true).apply {
            setPadding(act.dp(5), act.dp(1), act.dp(5), act.dp(1))
            background = rounded(0x99000000.toInt(), act.dpf(6f))
        }
        val ai: TextView = act.aiBadge(small = true).apply { visibility = View.GONE }
        var request: Thumbs.Request? = null
        var boundPath: String? = null

        init {
            background = rounded(Palette.surface2, act.dpf(8f))
            clipToOutline = true
            addView(typeIcon, flp(act.dp(40), act.dp(40), Gravity.CENTER))
            addView(image, flp())
            addView(folderBadge, flp(act.dp(22), act.dp(22), Gravity.TOP or Gravity.START).apply { setMargins(act.dp(6), act.dp(6), 0, 0) })
            addView(badge, flp(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, act.dp(6), act.dp(6), 0) })
            addView(ai, flp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(act.dp(6), act.dp(6), 0, 0) })
            addView(name, flp(MATCH, WRAP, Gravity.BOTTOM))
            foreground = ripple(null, act.dpf(8f))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        }
    }

    private inner class GridAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? Cell) ?: Cell()
            val e = items[position]
            bind(cell, e)
            return cell
        }
    }

    private fun bind(cell: Cell, e: Entry) {
        if (cell.boundPath == e.path && cell.image.drawable != null) return
        cell.request?.cancel()
        cell.request = null
        cell.boundPath = e.path
        cell.name.text = e.name
        cell.image.setImageDrawable(null)
        cell.typeIcon.setImageDrawable(act.icon(FileTypes.iconName(e.kind), if (e.isDir) Palette.accent else Palette.textDim))
        cell.folderBadge.visibility = View.GONE
        cell.ai.visibility = View.GONE
        val px = (cellSize() * 1.1f).toInt().coerceAtMost(act.dp(220))

        if (e.isDir) {
            cell.badge.visibility = View.GONE
            if (!Prefs.folderPreviews) return
            cell.folderBadge.setImageDrawable(act.icon("folder", Color.WHITE))
            val cached = folderPreview[e.path]
            if (cached != null) {
                if (cached.isNotEmpty()) loadThumb(cell, e.path, File(cached), px, folder = true)
            } else {
                Bg.io.execute {
                    val img = FileLister.firstImage(e.file)
                    folderPreview[e.path] = img?.absolutePath ?: ""
                    if (img != null) Bg.ui { if (cell.boundPath == e.path) loadThumb(cell, e.path, img, px, folder = true) }
                }
            }
            return
        }

        val ext = FileTypes.ext(e.name)
        val showBadge = e.kind != Kind.IMAGE || ext == "gif"
        cell.badge.text = ext.uppercase()
        cell.badge.visibility = if (showBadge && ext.isNotEmpty()) View.VISIBLE else View.GONE
        if (e.kind == Kind.IMAGE || e.kind == Kind.VIDEO || e.kind == Kind.SPECIAL || e.kind == Kind.PDF) {
            loadThumb(cell, e.path, e.file, px, folder = false)
        }
        bindAi(cell, e)
    }

    /** Плашка «AI», если в файле есть промпт / параметры генерации. */
    private fun bindAi(cell: Cell, e: Entry) {
        if (!Prefs.showAiBadge || !Metadata.canHaveAi(e.file)) return
        val cached = Metadata.cachedAi(e.file)
        if (cached != null) {
            cell.ai.visibility = if (cached) View.VISIBLE else View.GONE
            return
        }
        Bg.io.execute {
            if (cell.boundPath != e.path) return@execute
            val ai = Metadata.hasAi(e.file)
            if (ai) Bg.ui { if (cell.boundPath == e.path) cell.ai.visibility = View.VISIBLE }
        }
    }

    private fun loadThumb(cell: Cell, bindPath: String, file: File, px: Int, folder: Boolean) {
        Thumbs.peek(file, px)?.let {
            cell.image.setImageBitmap(it)
            if (folder) cell.folderBadge.visibility = View.VISIBLE
            return
        }
        cell.request = Thumbs.load(act, file, px) { bmp ->
            if (cell.boundPath == bindPath && bmp != null) {
                cell.image.alpha = 0f
                cell.image.setImageBitmap(bmp)
                cell.image.animate().alpha(1f).setDuration(120).start()
                if (folder) cell.folderBadge.visibility = View.VISIBLE
            }
        }
    }

    // ---------- Поиск по имени ----------

    private fun buildSearchRow() {
        searchRow.visibility = View.GONE
        searchRow.setBackgroundColor(Palette.surface)
        searchRow.setPadding(act.dp(12), 0, act.dp(4), act.dp(8))
        searchEdit.hint = I18n.t("filterHint")
        searchEdit.setSingleLine()
        searchEdit.setTextColor(Palette.text)
        searchEdit.setHintTextColor(Palette.textDim)
        searchEdit.background = rounded(Palette.surface2, act.dpf(20f))
        searchEdit.setPadding(act.dp(14), act.dp(8), act.dp(14), act.dp(8))
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter = s?.toString() ?: ""
                applyFilter()
            }
        })
        searchRow.addView(searchEdit, lp(0, WRAP, 1f))
        searchRow.addView(act.iconButton("close", I18n.t("close")) { toggleSearch() })
    }

    private fun toggleSearch() {
        val imm = act.getSystemService(InputMethodManager::class.java)
        if (searchRow.visibility == View.VISIBLE) {
            searchRow.visibility = View.GONE
            filter = ""
            searchEdit.setText("")
            imm?.hideSoftInputFromWindow(searchEdit.windowToken, 0)
        } else {
            searchRow.visibility = View.VISIBLE
            searchEdit.requestFocus()
            imm?.showSoftInput(searchEdit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ---------- Меню ----------

    private fun sortDialog() {
        val keys = listOf("name", "date", "size", "type", "random")
        val labels = ArrayList<String>()
        for (k in keys) {
            if (k == "random") labels.add(I18n.t("sort_random"))
            else {
                labels.add("${I18n.t("sort_$k")} ↑")
                labels.add("${I18n.t("sort_$k")} ↓")
            }
        }
        val cur = if (Prefs.sortBy == "random") labels.size - 1 else keys.indexOf(Prefs.sortBy).coerceAtLeast(0) * 2 + if (Prefs.sortDesc) 1 else 0
        Dialogs.choice(act, I18n.t("sortFiles"), labels, cur) { which ->
            if (which == labels.size - 1) { Prefs.sortBy = "random"; Prefs.sortDesc = false }
            else { Prefs.sortBy = keys[which / 2]; Prefs.sortDesc = which % 2 == 1 }
            refresh()
        }
    }

    private fun popup(anchor: View): PopupMenu {
        val theme = if (Palette.dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light
        return PopupMenu(ContextThemeWrapper(act, theme), anchor, Gravity.END)
    }

    private fun showMore(anchor: View) {
        val pm = popup(anchor)
        val m = pm.menu
        val fav = Prefs.favorites.contains(currentDir.absolutePath)
        m.add(0, 1, 0, I18n.t("bulkView"))
        m.add(0, 2, 0, I18n.t("slideshow"))
        m.add(0, 3, 0, if (fav) I18n.t("removeFavorite") else I18n.t("addFavorite"))
        m.add(0, 4, 0, I18n.t("newFolder"))
        m.add(0, 5, 0, I18n.t("refresh"))
        m.add(0, 6, 0, I18n.t("showAllFiles")).apply { isCheckable = true; isChecked = Prefs.showAllFiles }
        m.add(0, 7, 0, I18n.t("showHidden")).apply { isCheckable = true; isChecked = Prefs.showHidden }
        m.add(0, 8, 0, I18n.t("settings"))
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1, 2 -> {
                    val media = items.filter { e -> e.kind.isMedia }.map { e -> e.file }
                    if (media.isEmpty()) act.toast(I18n.t("noImages"))
                    else if (it.itemId == 1) act.push(BulkScreen(act, media, 0, null))
                    else { val v = ViewerScreen(act, media, 0); act.push(v); v.root.post { v.startSlideshowPublic() } }
                }
                3 -> act.toast(if (Prefs.toggleFavorite(currentDir.absolutePath)) I18n.t("favoriteAdded") else I18n.t("favoriteRemoved"))
                4 -> Dialogs.input(act, I18n.t("newFolder"), "") { name ->
                    if (FileOps.mkdir(currentDir, name) == null) act.toast(I18n.t("error")) else refresh()
                }
                5 -> { folderPreview.clear(); refresh() }
                6 -> { Prefs.showAllFiles = !Prefs.showAllFiles; refresh() }
                7 -> { Prefs.showHidden = !Prefs.showHidden; refresh() }
                8 -> act.openSettings()
            }
            true
        }
        pm.show()
    }

    private fun itemMenu(e: Entry) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (e.kind == Kind.IMAGE || e.kind == Kind.SPECIAL) actions.add(I18n.t("quickLook") to { quickLook(e.file) })
        actions.add(I18n.t("open") to { open(e) })
        if (e.isDir) {
            actions.add(I18n.t("bulkView") to {
                Bg.run({ FileLister.media(e.file).map { it.file } }) { list ->
                    if (list.isEmpty()) act.toast(I18n.t("noImages")) else act.push(BulkScreen(act, list, 0, e.name))
                }
            })
            val fav = Prefs.favorites.contains(e.path)
            actions.add((if (fav) I18n.t("removeFavorite") else I18n.t("addFavorite")) to {
                act.toast(if (Prefs.toggleFavorite(e.path)) I18n.t("favoriteAdded") else I18n.t("favoriteRemoved"))
            })
        }
        if (e.kind == Kind.ARCHIVE) actions.add(I18n.t("bulkView") to { act.openArchive(e.file, bulk = true) })
        if (!e.isDir) {
            actions.add(I18n.t("share") to { FileOps.share(act, listOf(e.file)) })
            actions.add(I18n.t("openWith") to { FileOps.openWith(act, e.file) })
            actions.add(I18n.t("info") to { infoDialog(e.file) })
        }
        actions.add(I18n.t("rename") to {
            val dot = if (e.isDir) -1 else e.name.lastIndexOf('.')
            Dialogs.input(act, I18n.t("rename"), e.name, if (dot > 0) dot else -1) { n ->
                if (FileOps.rename(act, e.file, n) == null) act.toast(I18n.t("renameFailed")) else refresh()
            }
        })
        actions.add(I18n.t("copyPath") to { FileOps.copyText(act, e.path) })
        actions.add(I18n.t("delete") to {
            val msg = if (e.isDir) I18n.t("deleteFolderConfirm", e.name) else I18n.t("deleteConfirm", e.name)
            Dialogs.confirm(act, I18n.t("delete"), msg, I18n.t("delete")) {
                if (FileOps.delete(act, e.file)) { Thumbs.invalidate(e.file); refresh() } else act.toast(I18n.t("deleteFailed"))
            }
        })
        Dialogs.choice(act, e.name, actions.map { it.first }) { actions[it].second() }
    }

    /** QuickLook: быстрый предпросмотр без перехода в просмотрщик. */
    private fun quickLook(f: File) {
        val dlg = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val box = FrameLayout(act)
        box.setBackgroundColor(0xEE000000.toInt())
        val z = ZoomImageView(act)
        val pb = ProgressBar(act).apply { isIndeterminate = true }
        box.addView(z, flp())
        box.addView(pb, flp(WRAP, WRAP, Gravity.CENTER))
        val caption = act.label(f.name, 13f, Color.WHITE, lines = 1).apply {
            setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(24))
            gravity = Gravity.CENTER
        }
        box.addView(caption, flp(MATCH, WRAP, Gravity.BOTTOM))
        z.onTap = { dlg.dismiss() }
        dlg.setContentView(box)
        dlg.show()
        Bg.runCatching({ Imaging.load(act, f, 2048) }) { r ->
            pb.visibility = View.GONE
            r.onSuccess { z.setImage(it, f) }.onFailure { caption.text = I18n.t("cannotOpen") }
        }
        dlg.setOnDismissListener { z.clear() }
    }

    private fun infoDialog(f: File) {
        val panel = InfoPanel(act)
        val dlg = android.app.AlertDialog.Builder(act, com.tiefsee.android.util.dialogTheme())
            .setView(panel.scroll)
            .setPositiveButton(I18n.t("close"), null)
            .create()
        dlg.show()
        panel.show(f)
    }

    private fun jumpUpDialog() {
        val chain = ArrayList<File>()
        var d: File? = currentDir.parentFile
        while (d != null) { chain.add(d); d = d.parentFile }
        if (chain.isEmpty()) return
        Dialogs.choice(act, I18n.t("goUp"), chain.map { Storage.displayPath(act, it) }) { navigate(chain[it]) }
    }

    // ---------- Боковая панель ----------

    private fun buildDrawer() {
        drawerScrim.setBackgroundColor(0x88000000.toInt())
        drawerScrim.visibility = View.GONE
        drawerScrim.setOnClickListener { toggleDrawer() }
        root.addView(drawerScrim, flp())
        drawer.setBackgroundColor(Palette.surface)
        drawer.visibility = View.GONE
        drawer.elevation = act.dp(12).toFloat()
        drawer.addView(drawerContent)
        val w = minOf(act.dp(320), (act.resources.displayMetrics.widthPixels * 0.85f).toInt())
        root.addView(drawer, flp(w, MATCH, Gravity.START))
    }

    private fun fillDrawer() {
        drawerContent.removeAllViews()
        drawerContent.setPadding(insetsArr[2], insetsArr[0] + act.dp(8), 0, insetsArr[1] + act.dp(8))

        val head = act.hbox()
        head.setPadding(act.dp(20), act.dp(12), act.dp(16), act.dp(16))
        val logo = ImageView(act).apply { setImageResource(act.applicationInfo.icon) }
        head.addView(logo, lp(act.dp(40), act.dp(40)))
        head.addView(act.vbox().apply {
            setPadding(act.dp(12), 0, 0, 0)
            addView(act.label("Tiefsee", 20f, Palette.text, bold = true))
            addView(act.label("for Android", 12f, Palette.textDim))
        })
        drawerContent.addView(head)

        section(I18n.t("storage"))
        for (r in Storage.roots(act)) {
            val n = if (r.icon == "home") I18n.t("internal") else r.name
            drawerItem(r.icon, n) { goTo(r.file) }
        }
        section(I18n.t("places"))
        for ((n, f) in Storage.shortcuts()) drawerItem(placeIcon(n), n) { goTo(f) }

        val favs = Prefs.favorites
        if (favs.isNotEmpty()) {
            section(I18n.t("favorites"))
            for (p in favs) {
                val f = File(p)
                drawerItem("star", f.name.ifEmpty { p }, onLong = {
                    Prefs.toggleFavorite(p)
                    fillDrawer()
                }) { goTo(f) }
            }
        }
        val rec = Prefs.recent.filter { it != currentDir.absolutePath }.take(6)
        if (rec.isNotEmpty()) {
            section(I18n.t("recent"))
            for (p in rec) drawerItem("history", File(p).name.ifEmpty { p }) { goTo(File(p)) }
        }
        divider()
        drawerItem("settings", I18n.t("settings")) { toggleDrawer(); act.openSettings() }
        drawerItem("info", I18n.t("about")) { toggleDrawer(); about() }
    }

    private fun placeIcon(n: String) = when (n) {
        "DCIM" -> "camera"
        "Pictures" -> "pictures"
        "Download" -> "download"
        "Movies" -> "video"
        else -> "folder_open"
    }

    private fun section(t: String) {
        drawerContent.addView(act.label(t, 12f, Palette.textDim, bold = true).apply {
            setPadding(act.dp(20), act.dp(14), act.dp(16), act.dp(6))
            isAllCaps = true
        })
    }

    private fun divider() {
        drawerContent.addView(View(act).apply { setBackgroundColor(Palette.divider) }, lp(MATCH, 1).apply {
            topMargin = act.dp(10); bottomMargin = act.dp(6)
        })
    }

    private fun drawerItem(icon: String, text: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
        val row = act.hbox()
        row.setPadding(act.dp(20), act.dp(12), act.dp(16), act.dp(12))
        row.background = ripple(null)
        row.addView(ImageView(act).apply { setImageDrawable(act.icon(icon, Palette.textDim)) }, lp(act.dp(22), act.dp(22)))
        row.addView(act.label(text, 15f, Palette.text, lines = 1).apply { setPadding(act.dp(18), 0, 0, 0) }, lp(0, WRAP, 1f))
        row.setOnClickListener { onClick() }
        if (onLong != null) row.setOnLongClickListener { onLong(); true }
        drawerContent.addView(row, lp())
    }

    private fun goTo(f: File) {
        if (drawerOpen) toggleDrawer()
        if (!f.isDirectory) { act.toast(I18n.t("noAccess")); return }
        navigate(f)
    }

    private fun toggleDrawer() {
        drawerOpen = !drawerOpen
        val w = drawer.layoutParams.width.toFloat()
        if (drawerOpen) {
            fillDrawer()
            drawer.visibility = View.VISIBLE
            drawerScrim.visibility = View.VISIBLE
            drawerScrim.alpha = 0f
            drawerScrim.animate().alpha(1f).setDuration(180).start()
            drawer.translationX = -w
            drawer.animate().translationX(0f).setDuration(200).start()
        } else {
            drawerScrim.animate().alpha(0f).setDuration(160).withEndAction { drawerScrim.visibility = View.GONE }.start()
            drawer.animate().translationX(-w).setDuration(180).withEndAction { drawer.visibility = View.GONE }.start()
        }
    }

    private fun about() {
        val msg = "Tiefsee for Android ${BuildConfig.VERSION_NAME}\n\n" + I18n.t("aboutText")
        android.app.AlertDialog.Builder(act, com.tiefsee.android.util.dialogTheme())
            .setTitle(I18n.t("about"))
            .setMessage(msg)
            .setPositiveButton("GitHub") { _, _ -> FileOps.openUrl(act, "https://github.com/hbl917070/Tiefsee4") }
            .setNegativeButton(I18n.t("close"), null)
            .show()
    }

    // ---------- Жизненный цикл ----------

    override fun onInsets(top: Int, bottom: Int, left: Int, right: Int) {
        insetsArr = intArrayOf(top, bottom, left, right)
        bar.view.setPadding(left + act.dp(4), top, right + act.dp(4), 0)
        grid.setPadding(left + act.dp(4), act.dp(4), right + act.dp(4), bottom + act.dp(8))
        updateColumns()
    }

    override fun onBack(): Boolean {
        if (drawerOpen) { toggleDrawer(); return true }
        if (searchRow.visibility == View.VISIBLE) { toggleSearch(); return true }
        val h = history.removeLastOrNull()
        if (h != null) {
            load(h.dir, restorePos = h.pos)
            return true
        }
        // Вверх по дереву, если открыли сразу вложенную папку
        val parent = currentDir.parentFile
        val rootDirs = Storage.roots(act).map { it.file.absolutePath }
        if (parent != null && currentDir.absolutePath !in rootDirs && parent.canRead()) {
            load(parent, focus = currentDir)
            return true
        }
        return false
    }

    private var shownOnce = false

    override fun onShow() {
        Prefs.addRecent(currentDir.absolutePath)
        if (shownOnce) refresh()
        shownOnce = true
    }

    override fun onConfigChanged() {
        root.post { updateColumns() }
    }

    override fun onDestroy() {
        listGen++
    }
}
