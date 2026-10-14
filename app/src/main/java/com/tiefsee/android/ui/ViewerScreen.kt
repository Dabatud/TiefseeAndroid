package com.tiefsee.android.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.media.MediaPlayer
import android.util.Base64
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import com.tiefsee.android.MainActivity
import com.tiefsee.android.data.Archives
import com.tiefsee.android.data.FileOps
import com.tiefsee.android.data.FileTypes
import com.tiefsee.android.data.Kind
import com.tiefsee.android.data.Prefs
import com.tiefsee.android.formats.Imaging
import com.tiefsee.android.formats.Thumbs
import com.tiefsee.android.meta.ImageSearch
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
import com.tiefsee.android.util.iconButton
import com.tiefsee.android.util.label
import com.tiefsee.android.util.lp
import com.tiefsee.android.util.rounded
import com.tiefsee.android.util.toast
import com.tiefsee.android.util.vbox
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** Просмотрщик: листание файлов папки/архива, как главное окно Tiefsee. */
class ViewerScreen(
    act: MainActivity,
    files: List<File>,
    startIndex: Int,
    private val titleOverride: String? = null,
) : Screen(act) {

    private val files = files.toMutableList()
    private var index = startIndex.coerceIn(0, (files.size - 1).coerceAtLeast(0))
    private val current: File? get() = files.getOrNull(index)

    override val root = FrameLayout(act)
    private val stage = FrameLayout(act)
    private val peek = ImageView(act)
    private val zoom = ZoomImageView(act)
    private val spinner = ProgressBar(act).apply { isIndeterminate = true; visibility = View.GONE }
    private val errorText: TextView = act.label("", 14f, 0xCCFFFFFF.toInt()).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    private var videoView: VideoView? = null
    private var mediaController: MediaController? = null
    private var webView: WebView? = null
    private var currentKind: Kind? = null

    private val topBar = act.hbox()
    private val titleView: TextView = act.label("", 16f, Palette.text, bold = true, lines = 1)
    private val subView: TextView = act.label("", 12f, Palette.textDim, lines = 1)
    private val bottomBar = act.vbox()
    private val strip = ThumbStrip(act)
    private val zoomLabel: TextView = act.label("", 12f, Color.WHITE).apply {
        background = rounded(0x99000000.toInt(), act.dpf(12f))
        setPadding(act.dp(10), act.dp(4), act.dp(10), act.dp(4))
        visibility = View.GONE
    }

    private val infoWrap = FrameLayout(act)
    private val info = InfoPanel(act)
    private var infoOpen = false

    private var uiVisible = true
    private var moreButton: View? = null
    private var loadGen = 0
    private val cache = LinkedHashMap<File, Imaging.Loaded>()
    private val preloading = HashSet<File>()
    private var slideshow = false
    private val maxSide: Int = run {
        val budget = Runtime.getRuntime().maxMemory() / 7 / 4
        sqrt(budget.toDouble()).toInt().coerceIn(2048, Imaging.MAX_SIDE)
    }
    private var insets = IntArray(4)

    init {
        applyBackground()
        root.addView(stage, flp())
        stage.addView(zoom, flp())
        stage.addView(spinner, flp(WRAP, WRAP, Gravity.CENTER))
        stage.addView(errorText, flp(MATCH, WRAP, Gravity.CENTER))
        peek.scaleType = ImageView.ScaleType.FIT_CENTER
        peek.visibility = View.GONE
        root.addView(peek, flp())
        root.addView(zoomLabel, flp(WRAP, WRAP, Gravity.CENTER))

        zoom.onTap = { toggleUi() }
        zoom.onLongPress = {
            if (!uiVisible) setUi(true)
            root.post { moreButton?.let { showMenu(it) } }
        }
        zoom.onTransform = { showZoomLabel() }

        buildTopBar()
        buildBottomBar()
        root.addView(topBar, flp(MATCH, WRAP, Gravity.TOP))
        root.addView(bottomBar, flp(MATCH, WRAP, Gravity.BOTTOM))
        buildInfoPanel()

        strip.files = this.files
        strip.onPick = { i -> stopSlideshow(); show(i) }
    }

    override val lightSystemBars: Boolean get() = !Palette.dark

    private fun applyBackground() {
        when (Prefs.viewerBackground) {
            "theme" -> root.setBackgroundColor(Palette.bg)
            "white" -> root.setBackgroundColor(Color.WHITE)
            "checker" -> root.background = if (Palette.dark) CheckerDrawable(act.dp(10), 0xFF262626.toInt(), 0xFF333333.toInt())
            else CheckerDrawable(act.dp(10), 0xFFFFFFFF.toInt(), 0xFFE6E6E6.toInt())
            else -> root.setBackgroundColor(Color.BLACK)
        }
    }

    // ---------- Панели ----------

    private fun buildTopBar() {
        topBar.setBackgroundColor(Palette.barBg)
        topBar.addView(act.iconButton("back", I18n.t("back")) { act.goBack() })
        val titles = act.vbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(4), 0, act.dp(4), 0)
            addView(titleView)
            addView(subView)
        }
        topBar.addView(titles, lp(0, act.dp(56), 1f))
        topBar.addView(act.iconButton("info", I18n.t("info")) { toggleInfo() })
        val more = act.iconButton("more", I18n.t("more")) { showMenu(it) }
        moreButton = more
        topBar.addView(more)
    }

    private fun buildBottomBar() {
        bottomBar.setBackgroundColor(Palette.barBg)
        if (Prefs.showFileStrip && files.size > 1) bottomBar.addView(strip, lp())
        val row = act.hbox()
        fun btn(icon: String, key: String, action: () -> Unit) {
            val b = act.iconButton(icon, I18n.t(key)) { stopSlideshow(); action() }
            row.addView(b, LinearLayout.LayoutParams(0, act.dp(52), 1f))
        }
        btn("rotate_left", "rotateLeft") { zoom.rotateBy(-90) }
        btn("rotate_right", "rotateRight") { zoom.rotateBy(90) }
        btn("flip", "flip") { zoom.flip() }
        btn("zoom_fit", "zoomToggle") { if (zoom.isZoomedIn) zoom.zoomToFit() else zoom.zoomToOriginal() }
        btn("bulk", "bulkView") { current?.let { act.push(BulkScreen(act, files, index, titleOverride)) } }
        btn("share", "share") { current?.let { FileOps.share(act, listOf(it)) } }
        btn("delete", "delete") { deleteCurrent() }
        bottomBar.addView(row, lp())
    }

    private fun buildInfoPanel() {
        infoWrap.visibility = View.GONE
        infoWrap.elevation = act.dp(8).toFloat()
        val box = act.vbox()
        val head = act.hbox()
        head.setPadding(act.dp(16), act.dp(6), act.dp(4), 0)
        head.addView(act.label(I18n.t("info"), 16f, Palette.text, bold = true), lp(0, WRAP, 1f))
        head.addView(act.iconButton("close", I18n.t("close")) { toggleInfo() })
        box.addView(head)
        box.addView(info.scroll, lp(MATCH, 0, 1f))
        infoWrap.addView(box, flp())
        root.addView(infoWrap)
        layoutInfo()
    }

    private fun layoutInfo() {
        val landscape = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val dm = act.resources.displayMetrics
        val r = act.dpf(18f)
        if (landscape) {
            infoWrap.layoutParams = flp((dm.widthPixels * 0.42f).toInt().coerceAtMost(act.dp(460)), MATCH, Gravity.END)
            infoWrap.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Palette.surface)
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
            infoWrap.setPadding(0, insets[0], insets[3], 0)
            info.setBottomPadding(insets[1])
        } else {
            infoWrap.layoutParams = flp(MATCH, (dm.heightPixels * 0.58f).toInt(), Gravity.BOTTOM)
            infoWrap.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Palette.surface)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            infoWrap.setPadding(insets[2], 0, insets[3], 0)
            info.setBottomPadding(insets[1])
        }
    }

    private fun toggleInfo() {
        infoOpen = !infoOpen
        if (infoOpen) {
            current?.let { info.show(it) }
            infoWrap.visibility = View.VISIBLE
            val landscape = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (landscape) { infoWrap.translationX = act.dp(300).toFloat(); infoWrap.animate().translationX(0f).setDuration(200).start() }
            else { infoWrap.translationY = act.dp(300).toFloat(); infoWrap.animate().translationY(0f).setDuration(200).start() }
        } else {
            infoWrap.animate().cancel()
            infoWrap.visibility = View.GONE
        }
    }

    override fun onInsets(top: Int, bottom: Int, left: Int, right: Int) {
        insets = intArrayOf(top, bottom, left, right)
        topBar.setPadding(left + act.dp(4), top, right + act.dp(4), 0)
        bottomBar.setPadding(left, 0, right, bottom)
        layoutInfo()
    }

    override fun onConfigChanged() = layoutInfo()

    private fun toggleUi() {
        if (slideshow) { stopSlideshow(); return }
        setUi(!uiVisible)
    }

    private fun setUi(visible: Boolean) {
        uiVisible = visible
        for (v in listOf(topBar, bottomBar)) {
            v.animate().cancel()
            if (visible) {
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(150).setListener(null).start()
            } else {
                v.animate().alpha(0f).setDuration(150).setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { v.visibility = View.GONE }
                }).start()
            }
        }
        act.setImmersiveMode(!visible)
        if (visible) mediaController?.show() else mediaController?.hide()
    }

    private fun showZoomLabel() {
        if (!zoom.hasImage) return
        zoomLabel.text = "${zoom.percent()}%"
        if (zoom.isZoomedIn || zoomLabel.visibility == View.VISIBLE) {
            zoomLabel.visibility = View.VISIBLE
            zoomLabel.alpha = 1f
            root.removeCallbacks(hideZoomLabel)
            root.postDelayed(hideZoomLabel, 900)
        }
    }

    private val hideZoomLabel = Runnable { zoomLabel.animate().alpha(0f).setDuration(200).withEndAction { zoomLabel.visibility = View.GONE }.start() }

    // ---------- Показ файла ----------

    fun show(i: Int, fromSwipe: Boolean = false) {
        if (files.isEmpty()) { act.pop(); return }
        index = i.coerceIn(0, files.size - 1)
        val f = files[index]
        val kind = FileTypes.kind(f.name)
        val gen = ++loadGen
        teardownMedia()
        errorText.visibility = View.GONE
        currentKind = kind

        titleView.text = titleOverride?.let { "$it · ${f.name}" } ?: f.name
        updateSubtitle(null)
        strip.select(index, !fromSwipe)
        if (infoOpen) info.show(f)

        when (kind) {
            Kind.VIDEO -> { zoom.clear(); zoom.visibility = View.GONE; showVideo(f) }
            Kind.SVG -> { zoom.clear(); zoom.visibility = View.GONE; showSvg(f) }
            else -> {
                zoom.visibility = View.VISIBLE
                val cached = cache[f]
                if (cached != null) {
                    zoom.setImage(cached, f)
                    spinner.visibility = View.GONE
                    updateSubtitle(cached)
                    preloadNeighbors()
                } else {
                    zoom.clear()
                    // Пока грузится — миниатюра, чтобы не было пустого экрана.
                    Thumbs.peek(f, act.dp(96))?.let { zoom.setImage(Imaging.Loaded(it, null, 0, 0, false), null) }
                    spinner.visibility = View.VISIBLE
                    Bg.runCatching({ Imaging.load(act, f, maxSide) }) { r ->
                        if (gen != loadGen) return@runCatching
                        spinner.visibility = View.GONE
                        r.onSuccess {
                            putCache(f, it)
                            zoom.setImage(it, f)
                            updateSubtitle(it)
                            preloadNeighbors()
                        }.onFailure {
                            zoom.clear()
                            errorText.text = I18n.t("cannotOpen") + "\n" + f.name + "\n" + (it.message ?: it.javaClass.simpleName)
                            errorText.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
        if (slideshow) scheduleSlideshow()
    }

    private fun updateSubtitle(l: Imaging.Loaded?) {
        val f = current ?: return
        val parts = ArrayList<String>()
        parts.add("${index + 1} / ${files.size}")
        if (l != null && l.width > 0) parts.add("${l.width}×${l.height}")
        if (!Archives.isPending(f)) parts.add(FileTypes.humanSize(f.length()))
        subView.text = parts.joinToString("  ·  ")
    }

    private fun putCache(f: File, l: Imaging.Loaded) {
        cache.remove(f)
        cache[f] = l
        while (cache.size > 3) {
            val victim = cache.keys.firstOrNull { it != current } ?: break
            cache.remove(victim)
        }
    }

    private fun preloadNeighbors() {
        for (d in intArrayOf(1, -1)) {
            val f = files.getOrNull(index + d) ?: continue
            val k = FileTypes.kind(f.name)
            if (k != Kind.IMAGE && k != Kind.SPECIAL) continue
            if (cache.containsKey(f) || !preloading.add(f)) continue
            Bg.runCatching({ Imaging.load(act, f, maxSide) }) { r ->
                preloading.remove(f)
                r.onSuccess { if (files.contains(f)) putCache(f, it) }
            }
        }
    }

    // ---------- Видео / SVG ----------

    private fun swipeFrame(): FrameLayout = object : FrameLayout(act) {
        private var sx = 0f
        private var sy = 0f
        private var dragging = false
        private var vt: android.view.VelocityTracker? = null

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - sx
                    val dy = e.y - sy
                    val wv = webView
                    val canScroll = wv != null && wv.canScrollHorizontally(if (dx > 0) -1 else 1)
                    if (abs(dx) > act.dp(16) && abs(dx) > abs(dy) * 1.5f && !canScroll && e.pointerCount == 1) {
                        dragging = true
                        return true
                    }
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (vt == null) vt = android.view.VelocityTracker.obtain()
            vt?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (dragging) swipe.onSwipeDrag(e.x - sx)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    vt?.recycle(); vt = null
                    if (dragging) swipe.onSwipeRelease(e.x - sx, vx)
                    else if (e.actionMasked == MotionEvent.ACTION_UP && abs(e.x - sx) < act.dp(10) && abs(e.y - sy) < act.dp(10)) toggleUi()
                    dragging = false
                }
            }
            return true
        }
    }

    private var mediaHolder: FrameLayout? = null

    private fun showVideo(f: File) {
        spinner.visibility = View.VISIBLE
        val holder = swipeFrame()
        val vv = VideoView(act)
        holder.addView(vv, flp(MATCH, MATCH, Gravity.CENTER))
        stage.addView(holder, 0, flp())
        val mc = MediaController(act)
        mc.setAnchorView(holder)
        vv.setMediaController(mc)
        vv.setVideoPath(f.absolutePath)
        vv.setOnPreparedListener { mp: MediaPlayer ->
            spinner.visibility = View.GONE
            mp.isLooping = Prefs.videoLoop
            if (Prefs.videoAutoplay) vv.start()
            if (uiVisible) mc.show(2500)
            mp.setOnVideoSizeChangedListener { _, w, h -> updateSubtitle(Imaging.Loaded(null, null, w, h, false)) }
            updateSubtitle(Imaging.Loaded(null, null, mp.videoWidth, mp.videoHeight, false))
        }
        vv.setOnErrorListener { _, _, _ ->
            spinner.visibility = View.GONE
            errorText.text = I18n.t("cannotPlay") + "\n" + f.name
            errorText.visibility = View.VISIBLE
            true
        }
        vv.setOnCompletionListener { if (slideshow) next() }
        videoView = vv
        mediaController = mc
        mediaHolder = holder
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showSvg(f: File) {
        val holder = swipeFrame()
        val wv = WebView(act)
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.settings.apply {
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptEnabled = false
            allowFileAccess = false
        }
        holder.addView(wv, flp())
        stage.addView(holder, 0, flp())
        Bg.runCatching({ f.readBytes() }) { r ->
            r.onSuccess { bytes ->
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=20">
                    <style>html,body{margin:0;height:100%;background:transparent}body{display:flex;align-items:center;justify-content:center}
                    img{max-width:100vw;max-height:100vh}</style></head>
                    <body><img src="data:image/svg+xml;base64,$b64"></body></html>"""
                wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        }
        webView = wv
        mediaHolder = holder
    }

    private fun teardownMedia() {
        mediaController?.hide()
        videoView?.stopPlayback()
        webView?.destroy()
        mediaHolder?.let { stage.removeView(it) }
        videoView = null
        webView = null
        mediaController = null
        mediaHolder = null
    }

    // ---------- Перелистывание ----------

    private var peekIndex = -1

    private val swipe: ZoomImageView.SwipeListener by lazy { object : ZoomImageView.SwipeListener {
        override fun onSwipeDrag(dx: Float) {
            stopSlideshow()
            val target = if (dx < 0) index + 1 else index - 1
            val w = root.width.toFloat()
            if (target !in files.indices) {
                stage.translationX = dx * 0.25f
                peek.visibility = View.GONE
                return
            }
            if (peekIndex != target) {
                peekIndex = target
                val f = files[target]
                val bmp = cache[f]?.bitmap ?: Thumbs.peek(f, act.dp(96))
                peek.setImageBitmap(bmp)
                peek.rotation = 0f
            }
            peek.visibility = View.VISIBLE
            stage.translationX = dx
            peek.translationX = if (dx < 0) dx + w else dx - w
        }

        override fun onSwipeRelease(dx: Float, velocityX: Float) {
            val w = root.width.toFloat()
            val target = if (dx < 0) index + 1 else index - 1
            val fling = abs(velocityX) > act.dp(900) && (velocityX < 0) == (dx < 0) && abs(dx) > act.dp(24)
            if (target in files.indices && (abs(dx) > w * 0.2f || fling)) {
                val dir = if (dx < 0) -1f else 1f
                stage.animate().translationX(dir * w).setDuration(170).start()
                peek.animate().translationX(0f).setDuration(170).withEndAction {
                    show(target, fromSwipe = true)
                    stage.translationX = 0f
                    peek.visibility = View.GONE
                    peek.setImageDrawable(null)
                    peekIndex = -1
                }.start()
            } else {
                stage.animate().translationX(0f).setDuration(160).start()
                val back = if (dx < 0) w else -w
                peek.animate().translationX(back).setDuration(160).withEndAction {
                    peek.visibility = View.GONE
                    peekIndex = -1
                }.start()
            }
        }
    } }

    fun next() { if (index < files.size - 1) show(index + 1) else if (slideshow) show(0) }
    fun prev() { if (index > 0) show(index - 1) }

    // ---------- Слайд-шоу ----------

    private val slideTask = Runnable { if (slideshow) next() }

    private fun scheduleSlideshow() {
        root.removeCallbacks(slideTask)
        if (currentKind == Kind.VIDEO) return // переключится по окончании видео
        root.postDelayed(slideTask, Prefs.slideshowSeconds * 1000L)
    }

    private fun startSlideshow() {
        slideshow = true
        setUi(false)
        act.toast(I18n.t("slideshowStarted"))
        scheduleSlideshow()
    }

    fun startSlideshowPublic() = startSlideshow()

    private fun stopSlideshow() {
        if (!slideshow) return
        slideshow = false
        root.removeCallbacks(slideTask)
    }

    // ---------- Действия ----------

    private fun deleteCurrent() {
        val f = current ?: return
        val doDelete = {
            if (FileOps.delete(act, f)) {
                Thumbs.invalidate(f)
                cache.remove(f)
                files.removeAt(index)
                strip.files = files
                act.toast(I18n.t("deleted"))
                if (files.isEmpty()) act.pop() else show(index.coerceAtMost(files.size - 1))
            } else act.toast(I18n.t("deleteFailed"))
        }
        if (Prefs.confirmDelete) Dialogs.confirm(act, I18n.t("delete"), I18n.t("deleteConfirm", f.name), I18n.t("delete")) { doDelete() }
        else doDelete()
    }

    private fun renameCurrent() {
        val f = current ?: return
        val dot = f.name.lastIndexOf('.')
        Dialogs.input(act, I18n.t("rename"), f.name, if (dot > 0) dot else -1) { name ->
            val nf = FileOps.rename(act, f, name)
            if (nf == null) act.toast(I18n.t("renameFailed"))
            else {
                cache.remove(f)?.let { cache[nf] = it }
                files[index] = nf
                strip.files = files
                show(index)
            }
        }
    }

    private fun imageSearch() {
        val f = current ?: return
        val enabled = ImageSearch.engines.filter { it.id in Prefs.searchEngines }
        if (enabled.isEmpty()) { act.toast(I18n.t("noEngines")); return }
        Dialogs.choice(act, I18n.t("imageSearch"), enabled.map { it.name }) { which ->
            val engine = enabled[which]
            act.toast(I18n.t("uploading"))
            Bg.runCatching({ ImageSearch.search(act, f, engine) }) { r ->
                r.onSuccess { FileOps.openUrl(act, it) }
                    .onFailure { act.toast(I18n.t("imageSearchFailed")) }
            }
        }
    }

    private fun showMenu(anchor: View) {
        val f = current ?: return
        val theme = if (Palette.dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light
        val pm = PopupMenu(ContextThemeWrapper(act, theme), anchor, Gravity.END)
        val m = pm.menu
        val isImage = currentKind == Kind.IMAGE || currentKind == Kind.SPECIAL
        m.add(0, 1, 0, I18n.t("rename"))
        m.add(0, 2, 0, I18n.t("openWith"))
        if (isImage) m.add(0, 3, 0, I18n.t("setAs"))
        if (isImage) m.add(0, 4, 0, I18n.t("imageSearch"))
        m.add(0, 5, 0, I18n.t("slideshow"))
        m.add(0, 6, 0, I18n.t("bulkView"))
        if (isImage) m.add(0, 7, 0, I18n.t("zoom100"))
        m.add(0, 8, 0, if (Prefs.showFileStrip) I18n.t("hideStrip") else I18n.t("showStrip"))
        m.add(0, 9, 0, I18n.t("copyPath"))
        if (titleOverride == null && f.parentFile != null) m.add(0, 10, 0, I18n.t("showInFolder"))
        m.add(0, 11, 0, I18n.t("settings"))
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> renameCurrent()
                2 -> FileOps.openWith(act, f)
                3 -> FileOps.setAs(act, f)
                4 -> imageSearch()
                5 -> startSlideshow()
                6 -> act.push(BulkScreen(act, files, index, titleOverride))
                7 -> zoom.zoomToOriginal()
                8 -> {
                    Prefs.showFileStrip = !Prefs.showFileStrip
                    bottomBar.removeView(strip)
                    if (Prefs.showFileStrip && files.size > 1) bottomBar.addView(strip, 0, lp())
                }
                9 -> FileOps.copyText(act, f.absolutePath)
                10 -> f.parentFile?.let { dir -> act.push(BrowserScreen(act, dir, highlight = f)) }
                11 -> act.openSettings()
            }
            true
        }
        pm.show()
    }

    // ---------- Жизненный цикл ----------

    override fun onBack(): Boolean {
        if (infoOpen) { toggleInfo(); return true }
        if (slideshow) { stopSlideshow(); setUi(true); return true }
        return false
    }

    override fun onShow() {
        act.keepScreenOn(Prefs.keepScreenOn)
        if (!uiVisible) act.setImmersiveMode(true)
        // Файлы могли удалить/переименовать в другом экране.
        if (files.any { !it.exists() && !Archives.isPending(it) }) {
            val cur = current
            files.retainAll { it.exists() || Archives.isPending(it) }
            strip.files = files
            if (files.isEmpty()) { Bg.ui { act.pop() }; return }
            show(cur?.let { files.indexOf(it) }?.takeIf { it >= 0 } ?: index.coerceAtMost(files.size - 1))
        }
    }

    override fun onHide() {
        act.keepScreenOn(false)
        act.setImmersiveMode(false)
        stopSlideshow()
        videoView?.pause()
    }

    override fun onPause() {
        videoView?.pause()
    }

    override fun onDestroy() {
        loadGen++
        teardownMedia()
        zoom.clear()
        cache.clear()
        root.removeCallbacks(slideTask)
    }

    override fun onKey(code: Int, event: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE -> next()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> prev()
            KeyEvent.KEYCODE_MOVE_HOME -> show(0)
            KeyEvent.KEYCODE_MOVE_END -> show(files.size - 1)
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> zoom.zoomBy(1.25f)
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> zoom.zoomBy(0.8f)
            KeyEvent.KEYCODE_0 -> zoom.zoomToFit()
            KeyEvent.KEYCODE_R -> zoom.rotateBy(if (event.isShiftPressed) -90 else 90)
            KeyEvent.KEYCODE_I -> toggleInfo()
            KeyEvent.KEYCODE_FORWARD_DEL -> deleteCurrent()
            else -> return false
        }
        return true
    }

    // Последний init: к этому моменту все свойства класса уже инициализированы.
    init {
        zoom.swipeListener = swipe
        show(index)
    }
}
