package com.tiefsee.android.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.tiefsee.android.data.Prefs
import com.tiefsee.android.formats.Imaging
import com.tiefsee.android.util.Bg
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Просмотр изображения: масштаб щипком, двойной тап, инерция, поворот/отражение,
 * свайп к соседним файлам и подгрузка участка в полном разрешении при сильном увеличении
 * (аналог Tiefseeview).
 */
class ZoomImageView(ctx: Context) : View(ctx) {

    interface SwipeListener {
        fun onSwipeDrag(dx: Float)
        fun onSwipeRelease(dx: Float, velocityX: Float)
    }

    var onTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var swipeListener: SwipeListener? = null
    var onTransform: (() -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var anim: Drawable? = null
    private var srcW = 0
    private var srcH = 0
    private var fullW = 0
    private var fullH = 0

    var rotationDeg = 0; private set
    var flipped = false; private set
    var scale = 1f; private set
    private var panX = 0f
    private var panY = 0f
    private var fitScale = 1f
    private var baseScale = 1f
    private var pendingReset = true

    private val matrix = Matrix()
    private val inverse = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // Детализация участка
    private var regionFile: File? = null
    private var regionDecoder: BitmapRegionDecoder? = null
    private var regionBitmap: Bitmap? = null
    private val regionSrc = RectF()
    private var regionGen = 0
    private var regionCapable = false

    private val scroller = OverScroller(ctx)
    private var animator: ValueAnimator? = null
    private var velocity: VelocityTracker? = null

    private var scaling = false
    private var swiping = false
    private var decided = false
    private var swipeDx = 0f
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop

    val hasImage get() = bitmap != null || anim != null
    val isZoomedIn get() = scale > baseScale * 1.02f
    val imageWidth get() = fullW
    val imageHeight get() = fullH

    fun setImage(loaded: Imaging.Loaded, file: File?) {
        clear()
        bitmap = loaded.bitmap
        anim = loaded.animated
        anim?.let {
            srcW = it.intrinsicWidth.coerceAtLeast(1)
            srcH = it.intrinsicHeight.coerceAtLeast(1)
            it.setBounds(0, 0, srcW, srcH)
            it.callback = this
            (it as? Animatable)?.start()
        }
        bitmap?.let { srcW = it.width; srcH = it.height }
        fullW = if (loaded.width > 0) loaded.width else srcW
        fullH = if (loaded.height > 0) loaded.height else srcH
        regionCapable = loaded.regionCapable && file != null && fullW > srcW
        regionFile = if (regionCapable) file else null
        pendingReset = true
        resetView()
        invalidate()
    }

    fun clear() {
        animator?.cancel()
        scroller.forceFinished(true)
        (anim as? Animatable)?.stop()
        anim?.callback = null
        anim = null
        bitmap = null
        regionBitmap = null
        regionGen++
        val dec = regionDecoder
        regionDecoder = null
        if (dec != null) Bg.single.execute { dec.recycle() }
        regionFile = null
        rotationDeg = 0
        flipped = false
        srcW = 0; srcH = 0
        invalidate()
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === anim || super.verifyDrawable(who)

    // ---------- Геометрия ----------

    private val rotW get() = (if (rotationDeg % 180 == 0) srcW else srcH).toFloat()
    private val rotH get() = (if (rotationDeg % 180 == 0) srcH else srcW).toFloat()
    private val originalScale get() = if (srcW > 0) fullW.toFloat() / srcW else 1f
    private val minScale get() = min(fitScale, baseScale) * 0.5f
    private val maxScale get() = max(max(fitScale * 12f, originalScale * 10f), 1f)

    private fun computeFit() {
        if (width == 0 || height == 0 || srcW == 0) return
        fitScale = min(width / rotW, height / rotH)
        baseScale = when (Prefs.zoomMode) {
            "width" -> width / rotW
            "height" -> height / rotH
            "original" -> originalScale
            "fitShrink" -> min(fitScale, originalScale)
            else -> fitScale
        }
    }

    fun resetView() {
        if (width == 0 || srcW == 0) return
        computeFit()
        scale = baseScale
        panX = 0f
        panY = 0f
        // Режим «по ширине» — начинаем сверху (удобно для манги/длинных картинок).
        if (Prefs.zoomMode == "width") panY = (rotH * scale - height) / 2f
        clampPan()
        pendingReset = false
        updateMatrix()
    }

    private fun clampPan() {
        val cw = rotW * scale
        val ch = rotH * scale
        panX = if (cw <= width) 0f else panX.coerceIn(-(cw - width) / 2f, (cw - width) / 2f)
        panY = if (ch <= height) 0f else panY.coerceIn(-(ch - height) / 2f, (ch - height) / 2f)
    }

    private fun updateMatrix() {
        matrix.reset()
        matrix.postTranslate(-srcW / 2f, -srcH / 2f)
        if (flipped) matrix.postScale(-1f, 1f)
        matrix.postRotate(rotationDeg.toFloat())
        matrix.postScale(scale, scale)
        matrix.postTranslate(width / 2f + panX, height / 2f + panY)
        matrix.invert(inverse)
        paint.isFilterBitmap = Prefs.smoothing || scale < 1f
        invalidate()
        onTransform?.invoke()
    }

    private fun zoomAt(newScale: Float, fx: Float, fy: Float) {
        val s = newScale.coerceIn(minScale, maxScale)
        val cx = width / 2f
        val cy = height / 2f
        val k = s / scale
        panX = fx - cx - k * (fx - cx - panX)
        panY = fy - cy - k * (fy - cy - panY)
        scale = s
        clampPan()
        updateMatrix()
    }

    private fun animateTo(targetScale: Float, fx: Float, fy: Float, resetPan: Boolean = false) {
        animator?.cancel()
        val startScale = scale
        val startX = panX
        val startY = panY
        // Конечное положение
        val k = targetScale / scale
        var endX = if (resetPan) 0f else fx - width / 2f - k * (fx - width / 2f - panX)
        var endY = if (resetPan) 0f else fy - height / 2f - k * (fy - height / 2f - panY)
        val cw = rotW * targetScale
        val ch = rotH * targetScale
        endX = if (cw <= width) 0f else endX.coerceIn(-(cw - width) / 2f, (cw - width) / 2f)
        endY = if (ch <= height) 0f else endY.coerceIn(-(ch - height) / 2f, (ch - height) / 2f)
        if (resetPan && Prefs.zoomMode == "width" && ch > height) endY = (ch - height) / 2f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                scale = startScale + (targetScale - startScale) * t
                panX = startX + (endX - startX) * t
                panY = startY + (endY - startY) * t
                updateMatrix()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = scheduleRegion()
            })
            start()
        }
    }

    // ---------- Команды ----------

    fun rotateBy(deg: Int) {
        rotationDeg = ((rotationDeg + deg) % 360 + 360) % 360
        regionBitmap = null
        resetView()
    }

    fun flip() {
        flipped = !flipped
        regionBitmap = null
        updateMatrix()
    }

    fun zoomToFit() = animateTo(baseScale, width / 2f, height / 2f, resetPan = true)

    fun zoomToOriginal() = animateTo(originalScale.coerceIn(minScale, maxScale), width / 2f, height / 2f)

    fun zoomBy(factor: Float) {
        zoomAt(scale * factor, width / 2f, height / 2f)
        scheduleRegion()
    }

    fun toggleZoom(fx: Float = width / 2f, fy: Float = height / 2f) {
        if (scale > baseScale * 1.05f) animateTo(baseScale, fx, fy, resetPan = true)
        else animateTo(max(baseScale * 2.5f, min(originalScale, baseScale * 6f)).coerceAtMost(maxScale), fx, fy)
    }

    /** Масштаб в % от оригинала. */
    fun percent(): Int = if (fullW > 0) Math.round(scale * srcW / fullW * 100) else 100

    // ---------- Отрисовка ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (srcW > 0) resetView()
    }

    override fun onDraw(canvas: Canvas) {
        if (srcW == 0) return
        canvas.save()
        canvas.concat(matrix)
        bitmap?.let { if (!it.isRecycled) canvas.drawBitmap(it, 0f, 0f, paint) }
        anim?.draw(canvas)
        regionBitmap?.let { canvas.drawBitmap(it, null, regionSrc, paint) }
        canvas.restore()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            panX = scroller.currX.toFloat()
            panY = scroller.currY.toFloat()
            clampPan()
            updateMatrix()
            postInvalidateOnAnimation()
            if (scroller.isFinished) scheduleRegion()
        }
    }

    // ---------- Подгрузка участка в высоком разрешении ----------

    private val regionTask = Runnable { decodeRegion() }

    private fun scheduleRegion() {
        removeCallbacks(regionTask)
        if (regionCapable) postDelayed(regionTask, 160)
    }

    private fun decodeRegion() {
        val file = regionFile ?: return
        if (srcW == 0 || width == 0) return
        val sampleNow = fullW.toFloat() / srcW
        // экранных пикселей на пиксель оригинала
        val so = scale / sampleNow
        if (scale <= 1.05f) {
            if (regionBitmap != null) { regionBitmap = null; invalidate() }
            return
        }
        var k = 1
        while (k * 2 <= 1f / so) k *= 2
        if (k >= sampleNow) return

        val vis = RectF(0f, 0f, width.toFloat(), height.toFloat())
        inverse.mapRect(vis)
        if (!vis.intersect(0f, 0f, srcW.toFloat(), srcH.toFloat())) return
        val sx = fullW.toFloat() / srcW
        val sy = fullH.toFloat() / srcH
        val rect = Rect(
            (vis.left * sx).toInt().coerceAtLeast(0),
            (vis.top * sy).toInt().coerceAtLeast(0),
            Math.ceil((vis.right * sx).toDouble()).toInt().coerceAtMost(fullW),
            Math.ceil((vis.bottom * sy).toDouble()).toInt().coerceAtMost(fullH),
        )
        if (rect.width() <= 0 || rect.height() <= 0) return
        val gen = ++regionGen
        val srcRect = RectF(rect.left / sx, rect.top / sy, rect.right / sx, rect.bottom / sy)
        Bg.single.execute {
            if (gen != regionGen) return@execute
            try {
                var dec = regionDecoder
                if (dec == null) {
                    dec = Imaging.regionDecoder(file) ?: return@execute
                    regionDecoder = dec
                }
                val bmp = dec.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = k })
                post {
                    if (gen == regionGen && bmp != null) {
                        regionBitmap = bmp
                        regionSrc.set(srcRect)
                        invalidate()
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    // ---------- Жесты ----------

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            animator?.cancel()
            scroller.forceFinished(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAt(scale * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    }).apply { isQuickScaleEnabled = true }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            toggleZoom(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!scaling && !swiping) onLongPress?.invoke()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling || e2.pointerCount > 1 || swiping) return true
            panX -= dx
            panY -= dy
            clampPan()
            updateMatrix()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (swiping || scaling) return true
            val cw = rotW * scale
            val ch = rotH * scale
            val mx = max(0f, (cw - width) / 2f).toInt()
            val my = max(0f, (ch - height) / 2f).toInt()
            scroller.fling(panX.toInt(), panY.toInt(), vx.toInt(), vy.toInt(), -mx, mx, -my, my)
            postInvalidateOnAnimation()
            return true
        }
    })

    /** Можно ли сдвинуть картинку в направлении жеста (иначе — листаем файлы). */
    private fun canPan(dirX: Float): Boolean {
        val cw = rotW * scale
        if (cw <= width + 1) return false
        val maxPan = (cw - width) / 2f
        return if (dirX > 0) panX < maxPan - 1 else panX > -maxPan + 1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                swiping = false
                decided = false
                swipeDx = 0f
                animator?.cancel()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (swiping) {
                    swiping = false
                    swipeListener?.onSwipeRelease(0f, 0f)
                }
                decided = true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!decided && !scaling && event.pointerCount == 1 && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    decided = true
                    if (swipeListener != null && abs(dx) > abs(dy) * 1.2f && !canPan(dx)) {
                        swiping = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (swiping) {
                    swipeDx = dx
                    swipeListener?.onSwipeDrag(dx)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                velocity?.recycle()
                velocity = null
                if (swiping) {
                    swiping = false
                    swipeListener?.onSwipeRelease(swipeDx, vx)
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    gestures.onTouchEvent(cancel)
                    cancel.recycle()
                    scaling = false
                    return true
                }
                if (scaling && scale < baseScale) animateTo(baseScale, width / 2f, height / 2f, resetPan = true)
                scaling = false
                scheduleRegion()
            }
        }
        if (!swiping) gestures.onTouchEvent(event)
        return true
    }

    /** Колёсико мыши / тачпад на планшетах и Chromebook. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                zoomAt(scale * if (v > 0) 1.15f else 1 / 1.15f, event.x, event.y)
                scheduleRegion()
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        (anim as? Animatable)?.stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (anim as? Animatable)?.start()
    }
}
