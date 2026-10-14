package com.tiefsee.android.data

import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Просмотр архивов без распаковки всего содержимого (ZIP/CBZ — по требованию, TAR — потоково).
 * Файлы попадают во временную папку; просмотрщик работает с ними как с обычной папкой.
 */
object Archives {

    private class Pending(val archive: File, val entryName: String, val charset: Charset)

    private val pending = ConcurrentHashMap<String, Pending>()

    class Opened(val dir: File, val entries: List<File>, val title: String)

    fun cacheDirFor(ctx: Context, archive: File): File {
        val key = archive.absolutePath + "|" + archive.length() + "|" + archive.lastModified()
        val md = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        val hex = md.joinToString("") { "%02x".format(it) }.substring(0, 16)
        return File(ctx.cacheDir, "archive/$hex")
    }

    fun open(ctx: Context, archive: File): Opened {
        val dir = cacheDirFor(ctx, archive)
        dir.mkdirs()
        val ext = FileTypes.ext(archive.name)
        val files = if (ext == "tar" || ext == "cbt") openTar(archive, dir) else openZip(archive, dir)
        val sorted = files.sortedWith { a, b -> NaturalOrder.compare(a.absolutePath, b.absolutePath) }
        return Opened(dir, sorted, archive.name)
    }

    private fun safeTarget(dir: File, entryName: String): File? {
        val clean = entryName.replace('\\', '/').trimStart('/')
        if (clean.isEmpty() || clean.endsWith("/")) return null
        val target = File(dir, clean)
        // Защита от "zip slip".
        if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) return null
        return target
    }

    private fun openZip(archive: File, dir: File): List<File> {
        val charsets = listOf("UTF-8", "GBK", "Shift_JIS", "IBM866", "IBM437")
            .mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }
        var lastError: Exception? = null
        for (cs in charsets) {
            try {
                ZipFile(archive, cs).use { zip ->
                    val out = ArrayList<File>()
                    val en = zip.entries()
                    while (en.hasMoreElements()) {
                        val e = en.nextElement()
                        if (e.isDirectory) continue
                        if (!FileTypes.kind(e.name).isMedia) continue
                        val target = safeTarget(dir, e.name) ?: continue
                        if (!target.exists() || target.length() != e.size) {
                            pending[target.absolutePath] = Pending(archive, e.name, cs)
                        }
                        out.add(target)
                    }
                    return out
                }
            } catch (ex: Exception) {
                lastError = ex
            }
        }
        throw lastError ?: IllegalStateException("zip")
    }

    private fun openTar(archive: File, dir: File): List<File> {
        val out = ArrayList<File>()
        archive.inputStream().buffered().use { inp ->
            TarReader(inp).forEach { name, size, data ->
                if (FileTypes.kind(name).isMedia) {
                    val target = safeTarget(dir, name)
                    if (target != null) {
                        if (!target.exists() || target.length() != size) {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { data.copyTo(it) }
                        }
                        out.add(target)
                    }
                }
            }
        }
        return out
    }

    /** Вызывается перед декодированием: если файл ещё не извлечён из архива — извлечь. */
    fun ensure(file: File): Boolean {
        val p = pending[file.absolutePath] ?: return file.exists()
        synchronized(p) {
            if (file.exists() && pending[file.absolutePath] == null) return true
            try {
                ZipFile(p.archive, p.charset).use { zip ->
                    val e = zip.getEntry(p.entryName) ?: return false
                    file.parentFile?.mkdirs()
                    val tmp = File(file.absolutePath + ".part")
                    zip.getInputStream(e).use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                    tmp.renameTo(file)
                }
                pending.remove(file.absolutePath)
                return true
            } catch (_: Exception) {
                return false
            }
        }
    }

    fun isPending(file: File) = pending.containsKey(file.absolutePath)

    fun clearCache(ctx: Context) {
        pending.clear()
        File(ctx.cacheDir, "archive").deleteRecursively()
    }
}

/** Минимальный читатель TAR (ustar / GNU long names). */
class TarReader(private val inp: InputStream) {

    fun forEach(block: (name: String, size: Long, data: InputStream) -> Unit) {
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (!readFully(header)) return
            if (header.all { it.toInt() == 0 }) return
            var name = cstr(header, 0, 100)
            val prefix = cstr(header, 345, 155)
            if (prefix.isNotEmpty() && header[257] == 'u'.code.toByte()) name = "$prefix/$name"
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val padded = (size + 511) / 512 * 512
            when (type) {
                'L' -> {
                    val buf = ByteArray(size.toInt())
                    readFully(buf)
                    skip(padded - size)
                    longName = String(buf).trimEnd('\u0000')
                    continue
                }
                '0', '\u0000', '7' -> {
                    val realName = longName ?: name
                    longName = null
                    val limited = LimitedStream(inp, size)
                    block(realName, size, limited)
                    limited.drain()
                    skip(padded - size)
                }
                else -> {
                    longName = null
                    skip(padded)
                }
            }
        }
    }

    private fun readFully(b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) {
            val n = inp.read(b, off, b.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skip(n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) return
            left -= r
        }
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        // GNU base-256 для больших файлов
        if (b[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }
        var v = 0L
        for (i in off until off + len) {
            val c = b[i].toInt()
            if (c in '0'.code..'7'.code) v = v * 8 + (c - '0'.code)
        }
        return v
    }

    private class LimitedStream(val src: InputStream, var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val r = src.read()
            if (r >= 0) left--
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }

        fun drain() {
            val buf = ByteArray(8192)
            while (left > 0 && read(buf, 0, buf.size) > 0) { /* skip */ }
        }
    }
}
