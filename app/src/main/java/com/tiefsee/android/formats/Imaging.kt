package com.tiefsee.android.formats

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.Size
import com.tiefsee.android.data.Archives
import com.tiefsee.android.data.FileTypes
import com.tiefsee.android.data.Kind
import java.io.File
import java.security.MessageDigest

/** Загрузка изображений для просмотрщика. */
object Imaging {

    /** Ограничение стороны текстуры: больше — детализация через BitmapRegionDecoder. */
    const val MAX_SIDE = 4096

    class Loaded(
        val bitmap: Bitmap?,
        val animated: Drawable?,
        /** Размер оригинала (с учётом EXIF-поворота). */
        val width: Int,
        val height: Int,
        /** Можно ли дорисовывать фрагменты в полном разрешении. */
        val regionCapable: Boolean,
    )

    fun load(ctx: Context, file: File, maxSide: Int = MAX_SIDE): Loaded {
        Archives.ensure(file)
        val kind = FileTypes.kind(file.name)
        if (kind == Kind.SPECIAL) {
            val r = decodeSpecial(ctx, file, maxSide)
            return Loaded(r.first, null, r.second, r.third, false)
        }
        return if (Build.VERSION.SDK_INT >= 28) loadModern(file, maxSide) else loadLegacy(file, maxSide)
    }

    private fun loadModern(file: File, maxSide: Int): Loaded {
        val src = ImageDecoder.createSource(file)
        var w = 0
        var h = 0
        var animated = false
        val listener = ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
            w = info.size.width
            h = info.size.height
            animated = info.isAnimated
            val s = sampleFor(w, h, maxSide)
            if (s > 1) decoder.setTargetSampleSize(s)
            if (!animated) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener { true } // показать частично битые файлы
        }
        val ext = FileTypes.ext(file.name)
        if (ext == "gif" || ext == "webp" || ext == "png" || ext == "apng" || ext == "avif" || ext == "heic" || ext == "heif") {
            val d = ImageDecoder.decodeDrawable(src, listener)
            if (d is AnimatedImageDrawable) {
                return Loaded(null, d, w, h, false)
            }
            val bmp = (d as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bmp != null) return Loaded(bmp, null, w, h, regionOk(file))
        }
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), listener)
        return Loaded(bmp, null, w, h, regionOk(file))
    }

    private fun loadLegacy(file: File, maxSide: Int): Loaded {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        val rot = exifRotation(file)
        val s = sampleFor(o.outWidth, o.outHeight, maxSide)
        val opts = BitmapFactory.Options().apply { inSampleSize = s }
        var bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: throw IllegalStateException("decode")
        var w = o.outWidth
        var h = o.outHeight
        if (rot != 0) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            if (rot % 180 != 0) { val t = w; w = h; h = t }
        }
        return Loaded(bmp, null, w, h, rot == 0 && regionOk(file))
    }

    private fun regionOk(file: File): Boolean {
        val ext = FileTypes.ext(file.name)
        if (ext !in setOf("jpg", "jpeg", "jfif", "png", "webp", "heic", "heif")) return false
        return exifRotation(file) == 0
    }

    fun exifRotation(file: File): Int {
        val ext = FileTypes.ext(file.name)
        if (ext !in setOf("jpg", "jpeg", "jfif", "heic", "heif", "webp", "dng")) return 0
        return try {
            when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    @Suppress("DEPRECATION")
    fun regionDecoder(file: File): BitmapRegionDecoder? = try {
        if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(file.absolutePath)
        else BitmapRegionDecoder.newInstance(file.absolutePath, false)
    } catch (_: Exception) {
        null
    }

    fun sampleFor(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (maxOf(w, h) / s > maxSide) s *= 2
        return s
    }

    /** Размеры изображения без полного декодирования. */
    fun dimensions(file: File): Pair<Int, Int>? {
        val kind = FileTypes.kind(file.name)
        if (kind == Kind.VIDEO) {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(file.absolutePath)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot % 180 != 0) h to w else w to h
            } catch (_: Exception) {
                null
            } finally {
                r.release()
            }
        }
        if (kind == Kind.SPECIAL) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        if (o.outWidth <= 0) return null
        return if (exifRotation(file) % 180 != 0) o.outHeight to o.outWidth else o.outWidth to o.outHeight
    }

    // ---------- Особые форматы ----------

    /** (bitmap, originalW, originalH) */
    fun decodeSpecial(ctx: Context, file: File, maxSide: Int): Triple<Bitmap, Int, Int> {
        return when (FileTypes.ext(file.name)) {
            "qoi" -> file.inputStream().use { toBitmap(QoiDecoder.decode(it, maxSide)) }
            "psd", "psb" -> file.inputStream().use { toBitmap(PsdDecoder.decode(it, maxSide)) }
            "clip" -> decodeClip(ctx, file, maxSide)
            else -> throw IllegalArgumentException("format")
        }
    }

    private fun toBitmap(r: Rgba): Triple<Bitmap, Int, Int> {
        val bmp = Bitmap.createBitmap(r.pixels, r.width, r.height, Bitmap.Config.ARGB_8888)
        return Triple(bmp, r.originalW, r.originalH)
    }

    private fun decodeClip(ctx: Context, file: File, maxSide: Int): Triple<Bitmap, Int, Int> {
        val db = File(ctx.cacheDir, "clip_${System.nanoTime()}.sqlite")
        try {
            require(ClipExtractor.extractSqlite(file, db)) { "clip" }
            val png = SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { sq ->
                var total = 0
                sq.rawQuery("SELECT length(ImageData) FROM CanvasPreview LIMIT 1", null).use { c ->
                    if (c.moveToFirst()) total = c.getInt(0)
                }
                require(total > 0) { "no preview" }
                val out = java.io.ByteArrayOutputStream(total)
                val chunk = 512 * 1024
                var off = 0
                while (off < total) {
                    // substr для BLOB работает побайтно; так обходим лимит CursorWindow (2 МБ).
                    sq.rawQuery("SELECT substr(ImageData, ${off + 1}, $chunk) FROM CanvasPreview LIMIT 1", null).use { c ->
                        if (c.moveToFirst()) out.write(c.getBlob(0))
                    }
                    off += chunk
                }
                out.toByteArray()
            }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, o)
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(o.outWidth, o.outHeight, maxSide) }
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size, opts) ?: throw IllegalStateException("png")
            return Triple(bmp, o.outWidth, o.outHeight)
        } finally {
            db.delete()
            File(db.absolutePath + "-journal").delete()
        }
    }

    // ---------- Превью PDF ----------

    fun pdfFirstPage(file: File, px: Int): Bitmap? {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    if (r.pageCount == 0) return null
                    r.openPage(0).use { p ->
                        val scale = px.toFloat() / maxOf(p.width, p.height)
                        val bmp = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- Миниатюры ----------

    fun makeThumbnail(ctx: Context, file: File, px: Int): Bitmap? {
        Archives.ensure(file)
        val kind = FileTypes.kind(file.name)
        return try {
            when (kind) {
                Kind.IMAGE -> imageThumb(file, px)
                Kind.VIDEO -> videoThumb(file, px)
                Kind.SPECIAL -> decodeSpecial(ctx, file, px * 2).first.let { scaleDown(it, px) }
                Kind.PDF -> pdfFirstPage(file, px)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun imageThumb(file: File, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return ThumbnailUtils.createImageThumbnail(file, Size(px, px), null)
            } catch (_: Exception) {
            }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        var s = 1
        while (minOf(o.outWidth, o.outHeight) / (s * 2) >= px) s *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val rot = exifRotation(file)
        return if (rot != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else bmp
    }

    @Suppress("DEPRECATION")
    private fun videoThumb(file: File, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return ThumbnailUtils.createVideoThumbnail(file, Size(px, px), null)
            } catch (_: Exception) {
            }
        }
        return ThumbnailUtils.createVideoThumbnail(file.absolutePath, android.provider.MediaStore.Images.Thumbnails.MINI_KIND)
    }

    fun scaleDown(bmp: Bitmap, px: Int): Bitmap {
        val m = maxOf(bmp.width, bmp.height)
        if (m <= px) return bmp
        val s = px.toFloat() / m
        return Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
    }

    fun hash(s: String): String {
        val md = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }
}

/** Кэш миниатюр: память + диск, очередь LIFO (сначала то, что видно сейчас). */
object Thumbs {
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private val executor = java.util.concurrent.ThreadPoolExecutor(
        3, 3, 30, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingDeque<Runnable>().let { dq ->
            object : java.util.concurrent.BlockingQueue<Runnable> by dq {
                override fun offer(e: Runnable): Boolean = dq.offerFirst(e)
                override fun add(element: Runnable): Boolean = dq.offerFirst(element)
            }
        }
    )

    private lateinit var diskDir: File

    fun init(ctx: Context) {
        diskDir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
    }

    fun key(file: File, px: Int) = "${file.absolutePath}|${file.lastModified()}|$px"

    fun peek(file: File, px: Int): Bitmap? = mem.get(key(file, px))

    fun hasFailed(file: File, px: Int) = failed.contains(key(file, px))

    class Request(val key: String) {
        @Volatile var cancelled = false
        fun cancel() { cancelled = true }
    }

    fun load(ctx: Context, file: File, px: Int, done: (Bitmap?) -> Unit): Request? {
        val k = key(file, px)
        mem.get(k)?.let { done(it); return null }
        if (failed.contains(k)) { done(null); return null }
        if (!::diskDir.isInitialized) init(ctx)
        val req = Request(k)
        executor.execute {
            if (req.cancelled) return@execute
            var bmp: Bitmap? = null
            val disk = File(diskDir, Imaging.hash(k) + ".webp")
            if (disk.exists()) bmp = BitmapFactory.decodeFile(disk.absolutePath)
            if (bmp == null && !req.cancelled) {
                bmp = Imaging.makeThumbnail(ctx, file, px)
                if (bmp != null) {
                    try {
                        @Suppress("DEPRECATION")
                        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                        disk.outputStream().use { bmp.compress(fmt, 82, it) }
                    } catch (_: Exception) {
                    }
                }
            }
            if (bmp != null) mem.put(k, bmp) else failed.add(k)
            com.tiefsee.android.util.Bg.ui { if (!req.cancelled) done(bmp) }
        }
        return req
    }

    fun invalidate(file: File) {
        val prefix = file.absolutePath + "|"
        mem.snapshot().keys.filter { it.startsWith(prefix) }.forEach { mem.remove(it) }
        failed.removeAll { it.startsWith(prefix) }
    }

    fun clearDisk() {
        mem.evictAll()
        failed.clear()
        if (::diskDir.isInitialized) diskDir.listFiles()?.forEach { it.delete() }
    }
}
