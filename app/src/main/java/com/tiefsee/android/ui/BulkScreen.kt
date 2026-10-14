package com.tiefsee.android.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import com.tiefsee.android.MainActivity
import com.tiefsee.android.data.FileTypes
import com.tiefsee.android.data.Kind
import com.tiefsee.android.data.Prefs
import com.tiefsee.android.formats.Imaging
import com.tiefsee.android.formats.Thumbs
import com.tiefsee.android.util.Bg
import com.tiefsee.android.util.I18n
import com.tiefsee.android.util.MATCH
import com.tiefsee.android.util.Palette
import com.tiefsee.android.util.WRAP
import com.tiefsee.android.util.dp
import com.tiefsee.android.util.flp
import com.tiefsee.android.util.icon
import com.tiefsee.android.util.iconButton
import com.tiefsee.android.util.lp
import com.tiefsee.android.util.vbox
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Bulk View: все изображения папки одной вертикальной лентой (удобно для манги/комиксов). */
class BulkScreen(act: MainActivity, private val files: List<File>, startIndex: Int, title: String?) : Screen(act) {

    override val root = act.vbox()
    private val bar = TopBar(act)
    private val list = ListView(act)
    private val adapter = RowAdapter()
    private var columns = Prefs.bulkColumns.coerceIn(1, 4)
    private val aspect = ConcurrentHashMap<File, Float>() // h / w
    private val bitmaps = object : LruCache<String, Any>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: String, value: Any): Int = when (value) {
            is Bitmap -> value.byteCount
            is Drawable -> value.intrinsicWidth * value.intrinsicHeight * 4
            else -> 1
        }
    }
    private var gen = 0
    private var insets = IntArray(4)

    init {
        root.setBackgroundColor(if (Prefs.viewerBackground == "theme") Palette.bg else Color.BLACK)
        bar.setTitle(title ?: (files.firstOrNull()?.parentFile?.name ?: ""), I18n.t("filesCount", files.size))
        bar.add(act.iconButton("grid", I18n.t("columns")) { setColumns(if (columns >= 4) 1 else columns + 1) })
        root.addView(bar.view, lp())
        list.adapter = adapter
        list.divider = null
        list.isFastScrollEnabled = true
        list.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        list.clipToPadding = false
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                val fileIdx = (first * columns + 1).coerceAtMost(files.size)
                bar.subtitle.text = "$fileIdx / ${files.size}"
                bar.subtitle.visibility = View.VISIBLE
            }
        })
        setupPinch()
        root.addView(list, lp(MATCH, 0, 1f))
        list.setSelection(startIndex / columns)
        measureAll()
    }

    private fun setColumns(c: Int) {
        val first = list.firstVisiblePosition * columns
        columns = c
        Prefs.bulkColumns = c
        adapter.notifyDataSetChanged()
        list.setSelection(first / columns)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPinch() {
        val det = ScaleGestureDetector(act, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            var acc = 1f
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { acc = 1f; return true }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                acc *= detector.scaleFactor
                if (acc > 1.4f && columns > 1) { setColumns(columns - 1); acc = 1f }
                if (acc < 0.7f && columns < 4) { setColumns(columns + 1); acc = 1f }
                return true
            }
        })
        list.setOnTouchListener { _, ev -> det.onTouchEvent(ev); det.isInProgress }
    }

    /** Размеры заранее, чтобы лента не «прыгала» при загрузке. */
    private fun measureAll() {
        val g = gen
        Bg.io.execute {
            var changed = 0
            for (f in files) {
                if (g != gen) return@execute
                if (aspect.containsKey(f)) continue
                val d = try { Imaging.dimensions(f) } catch (_: Exception) { null }
                aspect[f] = if (d != null && d.first > 0) d.second.toFloat() / d.first else 1.4f
                if (++changed % 30 == 0) Bg.ui { adapter.notifyDataSetChanged() }
            }
            Bg.ui { adapter.notifyDataSetChanged() }
        }
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = (files.size + columns - 1) / columns
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout)?.takeIf { it.childCount == columns } ?: LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                for (i in 0 until columns) {
                    val cell = FrameLayout(act)
                    val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = false }
                    cell.addView(iv, flp())
                    val play = ImageView(act).apply { setImageDrawable(act.icon("play", Color.WHITE)); visibility = View.GONE }
                    cell.addView(play, flp(act.dp(48), act.dp(48), Gravity.CENTER))
                    addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
                }
            }
            val totalW = (list.width - list.paddingLeft - list.paddingRight).coerceAtLeast(1)
            val gap = if (Prefs.bulkGap) act.dp(if (columns == 1) 4 else 3) else 0
            val cellW = totalW / columns
            var rowH = 0
            for (c in 0 until columns) {
                val idx = position * columns + c
                val f = files.getOrNull(idx)
                val a = f?.let { aspect[it] } ?: 1.4f
                rowH = maxOf(rowH, (cellW * a).toInt())
            }
            rowH = rowH.coerceIn(act.dp(40), act.dp(4000) * columns)
            row.setPadding(0, 0, 0, gap)
            for (c in 0 until columns) {
                val idx = position * columns + c
                val cell = row.getChildAt(c) as FrameLayout
                val iv = cell.getChildAt(0) as ImageView
                val play = cell.getChildAt(1) as ImageView
                (cell.layoutParams as LinearLayout.LayoutParams).apply {
                    height = rowH
                    setMargins(if (c > 0) gap / 2 else 0, 0, if (c < columns - 1) gap / 2 else 0, 0)
                }
                val f = files.getOrNull(idx)
                if (f == null) {
                    cell.visibility = View.INVISIBLE
                    iv.tag = null
                    iv.setImageDrawable(null)
                    continue
                }
                cell.visibility = View.VISIBLE
                cell.setOnClickListener { act.push(ViewerScreen(act, files, idx)) }
                play.visibility = if (FileTypes.kind(f.name) == Kind.VIDEO) View.VISIBLE else View.GONE
                bindImage(iv, f, cellW)
            }
            return row
        }
    }

    private fun bindImage(iv: ImageView, f: File, cellW: Int) {
        val kind = FileTypes.kind(f.name)
        val a = aspect[f] ?: 1.4f
        val maxSide = (maxOf(cellW, (cellW * a).toInt()) * 1.2f).toInt().coerceIn(256, Imaging.MAX_SIDE)
        val key = "${f.absolutePath}|$maxSide"
        if (iv.tag == key) return
        (iv.drawable as? Animatable)?.stop()
        iv.tag = key
        iv.setImageDrawable(null)
        val cached = bitmaps.get(key)
        if (cached != null) { setContent(iv, cached); return }

        if (kind == Kind.VIDEO || kind == Kind.SVG || kind == Kind.PDF) {
            Thumbs.load(act, f, act.dp(320)) { if (iv.tag == key) iv.setImageBitmap(it) }
            if (kind == Kind.SVG) iv.setImageDrawable(act.icon("svg", Palette.textDim))
            return
        }
        Thumbs.peek(f, act.dp(96))?.let { iv.setImageBitmap(it) }
        val g = gen
        Bg.runCatching({ Imaging.load(act, f, maxSide) }) { r ->
            if (g != gen) return@runCatching
            r.onSuccess { l ->
                val content: Any? = l.animated ?: l.bitmap
                if (content != null) {
                    bitmaps.put(key, content)
                    if (iv.tag == key) setContent(iv, content)
                }
            }
        }
    }

    private fun setContent(iv: ImageView, c: Any) {
        when (c) {
            is Bitmap -> iv.setImageBitmap(c)
            is Drawable -> { iv.setImageDrawable(c); (c as? Animatable)?.start() }
        }
    }

    override fun onInsets(top: Int, bottom: Int, left: Int, right: Int) {
        insets = intArrayOf(top, bottom, left, right)
        bar.view.setPadding(left + act.dp(4), top, right + act.dp(4), 0)
        list.setPadding(left, 0, right, bottom)
    }

    override fun onConfigChanged() {
        list.post { adapter.notifyDataSetChanged() }
    }

    override fun onDestroy() {
        gen++
        bitmaps.evictAll()
    }
}
