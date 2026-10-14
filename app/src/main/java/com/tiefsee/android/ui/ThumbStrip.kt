package com.tiefsee.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import com.tiefsee.android.data.FileTypes
import com.tiefsee.android.data.Prefs
import com.tiefsee.android.formats.Thumbs
import com.tiefsee.android.meta.Metadata
import com.tiefsee.android.util.Bg
import com.tiefsee.android.util.Palette
import com.tiefsee.android.util.dp
import com.tiefsee.android.util.icon
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Лента миниатюр соседних файлов (аналог File Panel из Tiefsee) с виртуализацией.
 */
class ThumbStrip(ctx: Context) : View(ctx) {

    var files: List<File> = emptyList()
        set(v) { field = v; requests.values.forEach { it?.cancel() }; requests.clear(); invalidate() }
    var selected = 0
        private set
    var onPick: ((Int) -> Unit)? = null

    private val cell = dp(56)
    private val gap = dp(4)
    private val pad = dp(8)
    private var scrollPos = 0f
    private val scroller = OverScroller(ctx)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3).toFloat()
    }
    private val bg = Paint().apply { color = 0x33FFFFFF }
    private val requests = HashMap<Int, Thumbs.Request?>()
    private val thumbPx = dp(96)

    private val stride get() = cell + gap
    private val contentWidth get() = files.size * stride - gap + pad * 2
    private val maxScroll get() = max(0, contentWidth - width).toFloat()

    fun select(index: Int, animate: Boolean = true) {
        selected = index
        val target = (pad + index * stride + cell / 2f - width / 2f).coerceIn(0f, maxScroll)
        if (animate && width > 0) {
            scroller.forceFinished(true)
            scroller.startScroll(scrollPos.toInt(), 0, (target - scrollPos).toInt(), 0, 250)
            postInvalidateOnAnimation()
        } else scrollPos = target
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), cell + pad * 2)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        select(selected, false)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPos = scroller.currX.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
    }

    private val dst = RectF()
    private val srcRect = Rect()

    override fun onDraw(canvas: Canvas) {
        if (files.isEmpty()) return
        border.color = Palette.accent
        val first = max(0, ((scrollPos - pad) / stride).toInt())
        val last = min(files.size - 1, ((scrollPos + width - pad) / stride).toInt() + 1)
        val top = pad.toFloat()
        val r = dp(6).toFloat()
        for (i in first..last) {
            val left = pad + i * stride - scrollPos
            dst.set(left, top, left + cell, top + cell)
            canvas.drawRoundRect(dst, r, r, bg)
            val f = files[i]
            val bmp = Thumbs.peek(f, thumbPx)
            if (bmp != null) {
                drawCrop(canvas, bmp)
            } else {
                if (!requests.containsKey(i) && !Thumbs.hasFailed(f, thumbPx)) {
                    requests[i] = Thumbs.load(context, f, thumbPx) { invalidate() }
                }
                val ic = context.icon(FileTypes.iconName(FileTypes.kind(f.name)), 0xAAFFFFFF.toInt())
                ic?.let {
                    val s = dp(24)
                    val cx = (left + cell / 2f).toInt()
                    val cy = (top + cell / 2f).toInt()
                    it.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
                    it.draw(canvas)
                }
            }
            drawAi(canvas, f, left, top)
            if (i == selected) canvas.drawRoundRect(dst, r, r, border)
        }
        // Отменяем запросы для ушедших за экран
        val it = requests.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key < first - 10 || e.key > last + 10) { e.value?.cancel(); it.remove() }
        }
    }

    private val aiChecks = HashSet<File>()
    private val aiBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() }
    private val aiText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = dp(9).toFloat()
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val aiRect = RectF()

    /** Маленькая плашка «AI» в углу миниатюры. */
    private fun drawAi(canvas: Canvas, f: File, left: Float, top: Float) {
        if (!Prefs.showAiBadge || !Metadata.canHaveAi(f)) return
        val ai = Metadata.cachedAi(f)
        if (ai == null) {
            if (aiChecks.add(f)) Bg.io.execute { if (Metadata.hasAi(f)) post { invalidate() } }
            return
        }
        if (!ai) return
        val w = dp(18).toFloat()
        val h = dp(12).toFloat()
        aiRect.set(left + dp(3), top + dp(3), left + dp(3) + w, top + dp(3) + h)
        canvas.drawRoundRect(aiRect, dp(4).toFloat(), dp(4).toFloat(), aiBg)
        canvas.drawText("AI", aiRect.centerX(), aiRect.centerY() - (aiText.descent() + aiText.ascent()) / 2, aiText)
    }

    private fun drawCrop(canvas: Canvas, bmp: Bitmap) {
        val s = min(bmp.width, bmp.height)
        srcRect.set((bmp.width - s) / 2, (bmp.height - s) / 2, (bmp.width + s) / 2, (bmp.height + s) / 2)
        canvas.save()
        val path = android.graphics.Path().apply { addRoundRect(dst, dp(6).toFloat(), dp(6).toFloat(), android.graphics.Path.Direction.CW) }
        canvas.clipPath(path)
        canvas.drawBitmap(bmp, srcRect, dst, paint)
        canvas.restore()
    }

    private val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollPos = (scrollPos + dx).coerceIn(0f, maxScroll)
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(scrollPos.toInt(), 0, -vx.toInt(), 0, 0, maxScroll.toInt(), 0, 0)
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val i = ((e.x + scrollPos - pad) / stride).toInt()
            if (i in files.indices) {
                selected = i
                invalidate()
                onPick?.invoke(i)
            }
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return detector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        requests.values.forEach { it?.cancel() }
        requests.clear()
    }
}
