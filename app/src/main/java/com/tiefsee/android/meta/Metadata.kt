package com.tiefsee.android.meta

import android.media.ExifInterface
import android.os.Build
import com.tiefsee.android.data.FileTypes
import com.tiefsee.android.formats.Imaging
import com.tiefsee.android.formats.skipFully
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.zip.Inflater

class FileMeta(
    val basic: List<Pair<String, String>>,
    val exif: List<Pair<String, String>>,
    val gps: DoubleArray?,
    val ai: AiInfo?,
    /** Прочие текстовые блоки (PNG tEXt, комментарии), не распознанные как AI. */
    val texts: List<Pair<String, String>>,
)

/** Информационная панель: базовые сведения, EXIF, GPS, AI-промпты. */
object Metadata {

    fun read(file: File, t: (String) -> String): FileMeta {
        val basic = ArrayList<Pair<String, String>>()
        basic.add(t("name") to file.name)
        basic.add(t("path") to (file.parent ?: ""))
        basic.add(t("size") to "${FileTypes.humanSize(file.length())} (${"%,d".format(file.length())} B)")
        Imaging.dimensions(file)?.let { (w, h) -> basic.add(t("resolution") to "$w × $h  (${"%.1f".format(w * h / 1_000_000.0)} MP)") }
        basic.add(t("modified") to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(file.lastModified())))
        basic.add(t("type") to FileTypes.mime(file.name))

        val texts = ArrayList<Pair<String, String>>()
        val ext = FileTypes.ext(file.name)
        if (ext == "png" || ext == "apng") {
            try { texts.addAll(pngText(file)) } catch (_: Exception) { }
        }

        val exifList = ArrayList<Pair<String, String>>()
        var gps: DoubleArray? = null
        if (ext in setOf("jpg", "jpeg", "jfif", "png", "webp", "heic", "heif", "dng", "avif")) {
            try {
                val ex = ExifInterface(file.absolutePath)
                for ((tag, label) in exifTags) {
                    val v = ex.getAttribute(tag) ?: continue
                    if (v.isBlank()) continue
                    exifList.add(label to formatExif(tag, v))
                }
                val ll = FloatArray(2)
                if (ex.getLatLong(ll)) gps = doubleArrayOf(ll[0].toDouble(), ll[1].toDouble())

                exifTexts(ex, texts)
            } catch (_: Exception) {
            }
        }
        if (ext == "jpg" || ext == "jpeg" || ext == "jfif") {
            try { jpegComment(file)?.let { texts.add("Comment" to it) } } catch (_: Exception) { }
        }

        val ai = try { AiParser.parse(texts) } catch (_: Exception) { null }
        val aiKeys = setOf("parameters", "prompt", "workflow", "Comment", "Description", "invokeai_metadata", "invokeai_graph", "sd-metadata", "Dream", "UserComment")
        val other = if (ai != null) texts.filter { it.first !in aiKeys } else texts
        return FileMeta(basic, exifList, gps, ai, other)
    }

    /** Текстовые поля EXIF, где генераторы хранят промпты. */
    private fun exifTexts(ex: ExifInterface, texts: MutableList<Pair<String, String>>) {
        userComment(ex)?.let { texts.add("UserComment" to it) }
        for (tag in listOf(ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL)) {
            val v = ex.getAttribute(tag) ?: continue
            // ComfyUI сохраняет в WebP: Make = "workflow:{...}", Model = "prompt:{...}"
            when {
                v.startsWith("workflow:") -> texts.add("workflow" to v.removePrefix("workflow:"))
                v.startsWith("prompt:") -> texts.add("prompt" to v.removePrefix("prompt:"))
                tag == ExifInterface.TAG_IMAGE_DESCRIPTION && v.contains("Steps: ") -> texts.add("parameters" to v)
            }
        }
    }

    private val aiCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val aiExts = setOf("png", "apng", "jpg", "jpeg", "jfif", "webp", "heic", "heif", "avif")

    /** Есть ли у файла данные генерации — для плашки «AI». Результат из кэша, если уже проверяли. */
    fun cachedAi(file: File): Boolean? = aiCache[aiKey(file)]

    fun canHaveAi(file: File) = FileTypes.ext(file.name) in aiExts

    /** Вызывать в фоне. */
    fun hasAi(file: File): Boolean {
        val key = aiKey(file)
        aiCache[key]?.let { return it }
        val result = try {
            val ext = FileTypes.ext(file.name)
            val texts = ArrayList<Pair<String, String>>()
            if (ext == "png" || ext == "apng") texts.addAll(pngText(file))
            if (ext in aiExts) {
                try { exifTexts(ExifInterface(file.absolutePath), texts) } catch (_: Exception) { }
            }
            if (ext == "jpg" || ext == "jpeg" || ext == "jfif") {
                try { jpegComment(file)?.let { texts.add("Comment" to it) } } catch (_: Exception) { }
            }
            AiParser.detect(texts)
        } catch (_: Exception) {
            false
        }
        aiCache[key] = result
        return result
    }

    private fun aiKey(f: File) = "${f.absolutePath}|${f.lastModified()}|${f.length()}"

    private val exifTags = listOf(
        ExifInterface.TAG_MAKE to "Make",
        ExifInterface.TAG_MODEL to "Model",
        "LensModel" to "Lens",
        ExifInterface.TAG_DATETIME_ORIGINAL to "Date taken",
        ExifInterface.TAG_EXPOSURE_TIME to "Exposure",
        ExifInterface.TAG_F_NUMBER to "F-number",
        ExifInterface.TAG_ISO_SPEED_RATINGS to "ISO",
        ExifInterface.TAG_FOCAL_LENGTH to "Focal length",
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM to "Focal length (35mm)",
        ExifInterface.TAG_FLASH to "Flash",
        ExifInterface.TAG_WHITE_BALANCE to "White balance",
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE to "Exposure bias",
        ExifInterface.TAG_ORIENTATION to "Orientation",
        ExifInterface.TAG_SOFTWARE to "Software",
        ExifInterface.TAG_ARTIST to "Artist",
        ExifInterface.TAG_COPYRIGHT to "Copyright",
        ExifInterface.TAG_GPS_ALTITUDE to "Altitude",
    )

    private fun formatExif(tag: String, v: String): String = when (tag) {
        ExifInterface.TAG_EXPOSURE_TIME -> {
            val d = v.toDoubleOrNull()
            if (d != null && d > 0 && d < 1) "1/${Math.round(1 / d)} s" else "$v s"
        }
        ExifInterface.TAG_F_NUMBER -> "f/$v"
        ExifInterface.TAG_FOCAL_LENGTH -> rational(v)?.let { "%.1f mm".format(it) } ?: v
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM -> "$v mm"
        ExifInterface.TAG_GPS_ALTITUDE -> rational(v)?.let { "%.1f m".format(it) } ?: v
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE -> rational(v)?.let { "%+.1f EV".format(it) } ?: v
        ExifInterface.TAG_FLASH -> if ((v.toIntOrNull() ?: 0) and 1 == 1) "On" else "Off"
        ExifInterface.TAG_WHITE_BALANCE -> if (v == "0") "Auto" else "Manual"
        else -> v
    }

    private fun rational(v: String): Double? {
        val p = v.split('/')
        return if (p.size == 2) {
            val a = p[0].toDoubleOrNull(); val b = p[1].toDoubleOrNull()
            if (a != null && b != null && b != 0.0) a / b else null
        } else v.toDoubleOrNull()
    }

    /** EXIF UserComment: 8 байт кодировки + данные (A1111 пишет "UNICODE\0" + UTF-16). */
    private fun userComment(ex: ExifInterface): String? {
        val bytes: ByteArray? = if (Build.VERSION.SDK_INT >= 28) ex.getAttributeBytes(ExifInterface.TAG_USER_COMMENT) else null
        if (bytes == null || bytes.size <= 8) {
            return ex.getAttribute(ExifInterface.TAG_USER_COMMENT)?.trim('\u0000', ' ')?.takeIf { it.isNotEmpty() }
        }
        val head = String(bytes, 0, 8, Charsets.US_ASCII)
        val data = bytes.copyOfRange(8, bytes.size)
        val s = when {
            head.startsWith("UNICODE") -> {
                // Порядок байт: A1111/piexif — big-endian; некоторые программы — little-endian.
                val be = data.size >= 2 && data[0].toInt() == 0 && data[1].toInt() != 0
                String(data, if (be) Charsets.UTF_16BE else Charsets.UTF_16LE)
            }
            head.startsWith("ASCII") -> String(data, Charsets.UTF_8)
            else -> String(bytes, Charsets.UTF_8)
        }
        return s.trim('\u0000', ' ').takeIf { it.isNotEmpty() }
    }

    /** Текстовые чанки PNG: tEXt, zTXt, iTXt. */
    fun pngText(file: File): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        DataInputStream(file.inputStream().buffered(1 shl 16)).use { inp ->
            val sig = ByteArray(8)
            inp.readFully(sig)
            if (sig[1] != 'P'.code.toByte() || sig[2] != 'N'.code.toByte()) return out
            while (true) {
                val len: Int
                try { len = inp.readInt() } catch (_: EOFException) { break }
                val typeB = ByteArray(4)
                inp.readFully(typeB)
                val type = String(typeB, Charsets.US_ASCII)
                if (type == "IEND") break
                if (len < 0) break
                if (type == "tEXt" || type == "zTXt" || type == "iTXt") {
                    if (len > 64 * 1024 * 1024) break
                    val data = ByteArray(len)
                    inp.readFully(data)
                    parseTextChunk(type, data)?.let { out.add(it) }
                } else {
                    skipFully(inp, len.toLong())
                }
                inp.readInt() // CRC
            }
        }
        return out
    }

    fun parseTextChunk(type: String, data: ByteArray): Pair<String, String>? {
        val z = data.indexOf(0)
        if (z <= 0) return null
        val key = String(data, 0, z, Charsets.ISO_8859_1)
        return when (type) {
            "tEXt" -> {
                // По стандарту Latin-1, но многие генераторы пишут UTF-8.
                val raw = data.copyOfRange(z + 1, data.size)
                key to decodeLenient(raw)
            }
            "zTXt" -> key to decodeLenient(inflate(data.copyOfRange(z + 2, data.size)))
            "iTXt" -> {
                val compressed = data.getOrNull(z + 1)?.toInt() == 1
                var p = z + 3
                val langEnd = indexOfZero(data, p); if (langEnd < 0) return null
                p = langEnd + 1
                val transEnd = indexOfZero(data, p); if (transEnd < 0) return null
                p = transEnd + 1
                val body = data.copyOfRange(p, data.size)
                key to String(if (compressed) inflate(body) else body, Charsets.UTF_8)
            }
            else -> null
        }
    }

    private fun indexOfZero(b: ByteArray, from: Int): Int {
        for (i in from until b.size) if (b[i].toInt() == 0) return i
        return -1
    }

    private fun decodeLenient(raw: ByteArray): String {
        val utf = String(raw, Charsets.UTF_8)
        return if (utf.contains('�')) String(raw, Charsets.ISO_8859_1) else utf
    }

    private fun inflate(b: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(b)
        val out = java.io.ByteArrayOutputStream(b.size * 4)
        val buf = ByteArray(1 shl 16)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    /** Сегмент COM в JPEG. */
    private fun jpegComment(file: File): String? {
        DataInputStream(file.inputStream().buffered()).use { inp ->
            if (inp.readUnsignedShort() != 0xFFD8) return null
            while (true) {
                var marker = inp.readUnsignedByte()
                if (marker != 0xFF) return null
                marker = inp.readUnsignedByte()
                while (marker == 0xFF) marker = inp.readUnsignedByte()
                if (marker == 0xDA || marker == 0xD9) return null
                val len = inp.readUnsignedShort() - 2
                if (len < 0) return null
                if (marker == 0xFE) {
                    val b = ByteArray(len)
                    inp.readFully(b)
                    return decodeLenient(b).trim('\u0000').takeIf { it.isNotBlank() }
                }
                skipFully(inp, len.toLong())
            }
        }
    }
}
