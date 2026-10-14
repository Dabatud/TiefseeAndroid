package com.tiefsee.android.formats

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/** Результат декодирования: ARGB_8888 пиксели. originalW/H — размер исходника до уменьшения. */
class Rgba(val width: Int, val height: Int, val pixels: IntArray, val originalW: Int = width, val originalH: Int = height)

/**
 * QOI — "Quite OK Image Format" (https://qoiformat.org).
 */
object QoiDecoder {
    fun decode(input: InputStream, maxSide: Int = Int.MAX_VALUE): Rgba {
        val inp = DataInputStream(input.buffered(1 shl 16))
        val magic = ByteArray(4)
        inp.readFully(magic)
        require(String(magic, Charsets.US_ASCII) == "qoif") { "not qoi" }
        val w = inp.readInt()
        val h = inp.readInt()
        inp.readUnsignedByte() // channels
        inp.readUnsignedByte() // colorspace
        require(w > 0 && h > 0 && w.toLong() * h < 400_000_000L) { "bad size" }

        val step = sampleStep(w, h, maxSide)
        val ow = (w + step - 1) / step
        val oh = (h + step - 1) / step
        val out = IntArray(ow * oh)

        val index = IntArray(64)
        var r = 0; var g = 0; var b = 0; var a = 255
        var run = 0
        val total = w.toLong() * h
        var p = 0L
        while (p < total) {
            if (run > 0) {
                run--
            } else {
                val b1 = inp.readUnsignedByte()
                when {
                    b1 == 0xFE -> { r = inp.readUnsignedByte(); g = inp.readUnsignedByte(); b = inp.readUnsignedByte() }
                    b1 == 0xFF -> { r = inp.readUnsignedByte(); g = inp.readUnsignedByte(); b = inp.readUnsignedByte(); a = inp.readUnsignedByte() }
                    (b1 and 0xC0) == 0x00 -> {
                        val c = index[b1]
                        a = c ushr 24; r = (c shr 16) and 0xFF; g = (c shr 8) and 0xFF; b = c and 0xFF
                    }
                    (b1 and 0xC0) == 0x40 -> {
                        r = (r + ((b1 shr 4) and 3) - 2) and 0xFF
                        g = (g + ((b1 shr 2) and 3) - 2) and 0xFF
                        b = (b + (b1 and 3) - 2) and 0xFF
                    }
                    (b1 and 0xC0) == 0x80 -> {
                        val b2 = inp.readUnsignedByte()
                        val vg = (b1 and 0x3F) - 32
                        r = (r + vg - 8 + ((b2 shr 4) and 0x0F)) and 0xFF
                        g = (g + vg) and 0xFF
                        b = (b + vg - 8 + (b2 and 0x0F)) and 0xFF
                    }
                    else -> run = b1 and 0x3F
                }
                index[(r * 3 + g * 5 + b * 7 + a * 11) % 64] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            val x = (p % w).toInt()
            val y = (p / w).toInt()
            if (x % step == 0 && y % step == 0) {
                out[(y / step) * ow + x / step] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            p++
        }
        return Rgba(ow, oh, out, w, h)
    }
}

/**
 * PSD / PSB — читает «сведённое» изображение (Image Data section), как это делают просмотрщики.
 * Поддерживается 8/16 бит, RGB / Grayscale / CMYK, без сжатия и RLE.
 */
object PsdDecoder {

    fun decode(input: InputStream, maxSide: Int = Int.MAX_VALUE): Rgba {
        val inp = DataInputStream(input.buffered(1 shl 16))
        val sig = ByteArray(4)
        inp.readFully(sig)
        require(String(sig, Charsets.US_ASCII) == "8BPS") { "not psd" }
        val version = inp.readUnsignedShort()
        val psb = version == 2
        skipFully(inp, 6)
        val channels = inp.readUnsignedShort()
        val h = inp.readInt()
        val w = inp.readInt()
        val depth = inp.readUnsignedShort()
        val mode = inp.readUnsignedShort()
        require(depth == 8 || depth == 16) { "depth $depth" }
        require(w > 0 && h > 0) { "size" }

        skipFully(inp, inp.readInt().toLong() and 0xFFFFFFFFL)      // color mode data
        skipFully(inp, inp.readInt().toLong() and 0xFFFFFFFFL)      // image resources
        val layerLen = if (psb) inp.readLong() else inp.readInt().toLong() and 0xFFFFFFFFL
        skipFully(inp, layerLen)                                     // layer & mask info

        val compression = inp.readUnsignedShort()
        require(compression == 0 || compression == 1) { "compression $compression" }

        val step = sampleStep(w, h, maxSide)
        val ow = (w + step - 1) / step
        val oh = (h + step - 1) / step
        val bpc = depth / 8
        val rowBytes = w * bpc

        // Какие каналы читаем
        val need = when (mode) {
            1, 8 -> minOf(channels, 2)          // grayscale (+alpha)
            4 -> minOf(channels, 5)             // CMYK (+alpha)
            else -> minOf(channels, 4)          // RGB (+alpha)
        }
        val planes = Array(need) { ByteArray(ow * oh) }

        val rowLens: IntArray? = if (compression == 1) {
            IntArray(channels * h) { if (psb) inp.readInt() else inp.readUnsignedShort() }
        } else null

        val row = ByteArray(rowBytes)
        val packed = ByteArray(rowBytes * 2 + 16)
        for (c in 0 until channels) {
            for (y in 0 until h) {
                if (rowLens != null) {
                    val len = rowLens[c * h + y]
                    if (c >= need) { skipFully(inp, len.toLong()); continue }
                    val buf = if (len <= packed.size) packed else ByteArray(len)
                    inp.readFully(buf, 0, len)
                    unpackBits(buf, len, row)
                } else {
                    if (c >= need) { skipFully(inp, rowBytes.toLong()); continue }
                    inp.readFully(row)
                }
                if (y % step != 0) continue
                val plane = planes[c]
                val base = (y / step) * ow
                var x = 0
                var ox = 0
                while (x < w) {
                    plane[base + ox] = row[x * bpc]   // для 16 бит — старший байт (big-endian)
                    x += step
                    ox++
                }
            }
        }

        val px = IntArray(ow * oh)
        for (i in px.indices) {
            fun ch(n: Int) = planes[n][i].toInt() and 0xFF
            px[i] = when (mode) {
                1, 8 -> {
                    val v = ch(0)
                    val a = if (need > 1) ch(1) else 255
                    (a shl 24) or (v shl 16) or (v shl 8) or v
                }
                4 -> {
                    // В PSD значения CMYK хранятся инвертированными (0 = 100% краски)
                    val cc = ch(0); val m = ch(1); val yy = ch(2)
                    val k = if (need > 3) ch(3) else 255
                    val r = cc * k / 255; val g = m * k / 255; val b = yy * k / 255
                    val a = if (need > 4) ch(4) else 255
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                else -> {
                    val r = ch(0)
                    val g = if (need > 1) ch(1) else r
                    val b = if (need > 2) ch(2) else r
                    val a = if (need > 3) ch(3) else 255
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return Rgba(ow, oh, px, w, h)
    }

    /** PackBits (RLE) */
    private fun unpackBits(src: ByteArray, len: Int, dst: ByteArray) {
        var i = 0
        var o = 0
        while (i < len && o < dst.size) {
            val n = src[i++].toInt()
            if (n >= 0) {
                val cnt = minOf(n + 1, dst.size - o, len - i)
                System.arraycopy(src, i, dst, o, cnt)
                i += n + 1
                o += cnt
            } else if (n != -128) {
                val cnt = minOf(1 - n, dst.size - o)
                if (i >= len) break
                val v = src[i++]
                for (k in 0 until cnt) dst[o + k] = v
                o += cnt
            }
        }
    }
}

/**
 * CLIP (Clip Studio Paint): внутри файла лежит база SQLite, в таблице CanvasPreview — PNG превью.
 * Здесь вырезаем SQLite-базу в отдельный файл; чтение — через android.database.sqlite (см. SpecialFormats).
 */
object ClipExtractor {
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun extractSqlite(clip: File, out: File): Boolean {
        RandomAccessFile(clip, "r").use { raf ->
            val len = raf.length()
            val buf = ByteArray(1 shl 16)
            var pos = 0L
            var found = -1L
            // Ищем сигнатуру SQLite (обычно ближе к концу, но сканируем с начала).
            while (pos < len && found < 0) {
                raf.seek(pos)
                val n = raf.read(buf)
                if (n <= 0) break
                val idx = indexOf(buf, n, SQLITE_MAGIC)
                if (idx >= 0) found = pos + idx
                else pos += (n - SQLITE_MAGIC.size).coerceAtLeast(1)
            }
            if (found < 0) return false
            raf.seek(found + 16)
            var pageSize = raf.readUnsignedShort()
            if (pageSize == 1) pageSize = 65536
            raf.seek(found + 28)
            val pageCount = raf.readInt().toLong() and 0xFFFFFFFFL
            var dbLen = pageSize * pageCount
            if (dbLen <= 0 || found + dbLen > len) dbLen = len - found
            raf.seek(found)
            out.outputStream().buffered().use { os ->
                var left = dbLen
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    os.write(buf, 0, n)
                    left -= n
                }
            }
            return true
        }
    }

    private fun indexOf(hay: ByteArray, n: Int, needle: ByteArray): Int {
        outer@ for (i in 0..n - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}

internal fun sampleStep(w: Int, h: Int, maxSide: Int): Int {
    var s = 1
    while (maxOf(w, h) / s > maxSide) s++
    return s
}

internal fun skipFully(inp: InputStream, n: Long) {
    var left = n
    while (left > 0) {
        val s = inp.skip(left)
        if (s <= 0) {
            if (inp.read() < 0) throw EOFException()
            left--
        } else left -= s
    }
}
