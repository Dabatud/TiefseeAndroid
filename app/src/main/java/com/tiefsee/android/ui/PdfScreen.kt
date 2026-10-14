package com.tiefsee.android.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.tiefsee.android.MainActivity
import com.tiefsee.android.data.FileOps
import com.tiefsee.android.formats.Imaging
import com.tiefsee.android.util.Bg
import com.tiefsee.android.util.Dialogs
import com.tiefsee.android.util.I18n
import com.tiefsee.android.util.MATCH
import com.tiefsee.android.util.Palette
import com.tiefsee.android.util.WRAP
import com.tiefsee.android.util.dp
import com.tiefsee.android.util.flp
import com.tiefsee.android.util.iconButton
import com.tiefsee.android.util.label
import com.tiefsee.android.util.lp
import com.tiefsee.android.util.vbox
import java.io.File
import java.util.concurrent.Executors

/** PDF-читалка на системном PdfRenderer. Нажатие на страницу — открыть её с зумом. */
class PdfScreen(act: MainActivity, private val file: File) : Screen(act) {

    override val root = act.vbox()
    private val bar = TopBar(act)
    private val list = ListView(act)
    private val error: TextView = act.label("", 15f, Palette.textDim).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    private val exec = Executors.newSingleThreadExecutor()
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageSizes: List<Pair<Int, Int>> = emptyList()
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    @Volatile private var closed = false

    init {
        root.setBackgroundColor(if (Palette.dark) 0xFF0F1315.toInt() else 0xFFDADDE0.toInt())
        bar.setTitle(file.name)
        bar.add(act.iconButton("search", I18n.t("goToPage")) { goToPage() })
        bar.add(act.iconButton("share", I18n.t("share")) { FileOps.share(act, listOf(file)) })
        bar.add(act.iconButton("open_in", I18n.t("openWith")) { FileOps.openWith(act, file) })
        root.addView(bar.view, lp())
        val body = FrameLayout(act)
        list.divider = null
        list.isFastScrollEnabled = true
        list.clipToPadding = false
        list.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                if (total > 0) bar.setTitle(file.name, "${first + 1} / $total")
            }
        })
        body.addView(list, flp())
        body.addView(error, flp(MATCH, WRAP, Gravity.CENTER))
        root.addView(body, lp(MATCH, 0, 1f))

        exec.execute {
            try {
                val p = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(p)
                val sizes = (0 until r.pageCount).map { i -> r.openPage(i).use { it.width to it.height } }
                pfd = p
                renderer = r
                Bg.ui {
                    pageSizes = sizes
                    list.adapter = PageAdapter()
                }
            } catch (e: Exception) {
                Bg.ui {
                    error.text = I18n.t("cannotOpen") + "\n" + (e.message ?: "")
                    error.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun render(index: Int, widthPx: Int): Bitmap? {
        val r = renderer ?: return null
        if (closed) return null
        return r.openPage(index).use { page ->
            val m = Imaging.MAX_SIDE.toFloat()
            val scale = minOf(widthPx.toFloat() / page.width, m / page.width, m / page.height)
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    private inner class PageAdapter : BaseAdapter() {
        override fun getCount() = pageSizes.size
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val iv = (convertView as? ImageView) ?: ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_XY }
            val w = (list.width - list.paddingLeft - list.paddingRight - act.dp(16)).coerceAtLeast(100)
            val (pw, ph) = pageSizes[position]
            val h = (w.toFloat() * ph / pw).toInt()
            iv.layoutParams = AbsListView.LayoutParams(MATCH, h + act.dp(8))
            iv.setPadding(act.dp(8), act.dp(4), act.dp(8), act.dp(4))
            iv.setBackgroundColor(Color.TRANSPARENT)
            val renderW = (w * 1.5f).toInt()
            val key = "$position|$renderW"
            iv.tag = key
            iv.setOnClickListener { openPage(position) }
            val cached = cache.get(key)
            if (cached != null) iv.setImageBitmap(cached)
            else {
                iv.setImageDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
                exec.execute {
                    if (iv.tag != key || closed) return@execute
                    val bmp = try { render(position, renderW) } catch (_: Exception) { null } ?: return@execute
                    cache.put(key, bmp)
                    Bg.ui { if (iv.tag == key) iv.setImageBitmap(bmp) }
                }
            }
            return iv
        }
    }

    private fun openPage(index: Int) {
        val screen = PdfPageScreen(act, this, index, pageSizes.size, file.name)
        act.push(screen)
    }

    /** Рендер страницы в высоком разрешении для экрана с зумом. */
    fun renderLarge(index: Int, done: (Bitmap?) -> Unit) {
        exec.execute {
            val dm = act.resources.displayMetrics
            val bmp = try { render(index, (maxOf(dm.widthPixels, dm.heightPixels) * 2).coerceAtMost(Imaging.MAX_SIDE)) } catch (_: Exception) { null }
            Bg.ui { done(bmp) }
        }
    }

    private fun goToPage() {
        if (pageSizes.isEmpty()) return
        Dialogs.input(act, I18n.t("goToPage") + " (1–${pageSizes.size})", "") { s ->
            s.trim().toIntOrNull()?.let { list.setSelection((it - 1).coerceIn(0, pageSizes.size - 1)) }
        }
    }

    override fun onInsets(top: Int, bottom: Int, left: Int, right: Int) {
        bar.view.setPadding(left + act.dp(4), top, right + act.dp(4), 0)
        list.setPadding(left, act.dp(4), right, bottom + act.dp(8))
    }

    override fun onConfigChanged() {
        list.post { (list.adapter as? BaseAdapter)?.notifyDataSetChanged() }
    }

    override fun onDestroy() {
        closed = true
        exec.execute {
            try { renderer?.close() } catch (_: Exception) { }
            try { pfd?.close() } catch (_: Exception) { }
        }
        exec.shutdown()
        cache.evictAll()
    }
}

/** Одна страница PDF с зумом и перелистыванием. */
class PdfPageScreen(act: MainActivity, private val pdf: PdfScreen, private var page: Int, private val count: Int, private val name: String) : Screen(act) {
    override val root = FrameLayout(act)
    private val zoom = ZoomImageView(act)
    private val bar = TopBar(act)

    init {
        root.setBackgroundColor(Color.BLACK)
        root.addView(zoom, flp())
        bar.view.setBackgroundColor(Palette.barBg)
        root.addView(bar.view, flp(MATCH, WRAP, Gravity.TOP))
        zoom.onTap = { bar.view.visibility = if (bar.view.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        zoom.swipeListener = object : ZoomImageView.SwipeListener {
            override fun onSwipeDrag(dx: Float) { zoom.translationX = dx * 0.5f }
            override fun onSwipeRelease(dx: Float, velocityX: Float) {
                zoom.animate().translationX(0f).setDuration(150).start()
                if (dx < -act.dp(60) && page < count - 1) load(page + 1)
                else if (dx > act.dp(60) && page > 0) load(page - 1)
            }
        }
        load(page)
    }

    private fun load(p: Int) {
        page = p
        bar.setTitle(name, "${p + 1} / $count")
        pdf.renderLarge(p) { bmp -> if (bmp != null && page == p) zoom.setImage(Imaging.Loaded(bmp, null, bmp.width, bmp.height, false), null) }
    }

    override fun onInsets(top: Int, bottom: Int, left: Int, right: Int) {
        bar.view.setPadding(left + act.dp(4), top, right + act.dp(4), 0)
    }

    override fun onDestroy() = zoom.clear()
}
