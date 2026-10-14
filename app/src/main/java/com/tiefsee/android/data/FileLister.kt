package com.tiefsee.android.data

import java.io.File

/** Элемент списка: файл/папка с закэшированными атрибутами (чтобы сортировка не дёргала диск). */
class Entry(val file: File) {
    val name: String = file.name
    val isDir: Boolean = file.isDirectory
    val kind: Kind = if (isDir) Kind.DIR else FileTypes.kind(name)
    val size: Long = if (isDir) 0 else file.length()
    val modified: Long = file.lastModified()
    val path: String get() = file.absolutePath

    override fun equals(other: Any?) = other is Entry && other.file == file
    override fun hashCode() = file.hashCode()
}

object FileLister {

    class Listing(val dirs: List<Entry>, val files: List<Entry>)

    fun list(dir: File, includeAll: Boolean = Prefs.showAllFiles): Listing {
        val children = dir.listFiles() ?: emptyArray()
        val dirs = ArrayList<Entry>()
        val files = ArrayList<Entry>()
        val hidden = Prefs.showHidden
        for (f in children) {
            if (!hidden && f.name.startsWith(".")) continue
            val e = Entry(f)
            if (e.isDir) dirs.add(e)
            else if (includeAll || e.kind != Kind.OTHER) files.add(e)
        }
        return Listing(sort(dirs, Prefs.dirSortBy, Prefs.dirSortDesc), sort(files, Prefs.sortBy, Prefs.sortDesc))
    }

    /** Только файлы, которые листаются в просмотрщике. */
    fun media(dir: File): List<Entry> = list(dir, false).files.filter { it.kind.isMedia }

    fun sort(list: List<Entry>, by: String, desc: Boolean): List<Entry> {
        val cmp: Comparator<Entry> = when (by) {
            "date" -> compareBy<Entry> { it.modified }.thenComparing { a, b -> NaturalOrder.compare(a.name, b.name) }
            "size" -> compareBy<Entry> { it.size }.thenComparing { a, b -> NaturalOrder.compare(a.name, b.name) }
            "type" -> compareBy<Entry> { FileTypes.ext(it.name) }.thenComparing { a, b -> NaturalOrder.compare(a.name, b.name) }
            "random" -> return list.shuffled()
            else -> Comparator { a, b -> NaturalOrder.compare(a.name, b.name) }
        }
        val sorted = list.sortedWith(cmp)
        return if (desc) sorted.reversed() else sorted
    }

    /** Первая картинка папки — для превью в панели папок. */
    fun firstImage(dir: File): File? {
        val children = dir.listFiles() ?: return null
        var best: File? = null
        for (f in children) {
            if (f.name.startsWith(".")) continue
            val k = FileTypes.kind(f.name)
            if (k == Kind.IMAGE || k == Kind.VIDEO || k == Kind.SPECIAL) {
                if (f.isFile && (best == null || NaturalOrder.compare(f.name, best.name) < 0)) best = f
            }
        }
        return best
    }

    fun countChildren(dir: File): Int = dir.list()?.count { !it.startsWith(".") } ?: 0
}

/** «Естественная» сортировка: img2 < img10, без учёта регистра. Как в проводнике Windows. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var si = i
                var sj = j
                while (si < a.length && a[si] == '0') si++
                while (sj < b.length && b[sj] == '0') sj++
                var ei = si
                var ej = sj
                while (ei < a.length && a[ei].isDigit()) ei++
                while (ej < b.length && b[ej].isDigit()) ej++
                val la = ei - si
                val lb = ej - sj
                if (la != lb) return la - lb
                for (k in 0 until la) {
                    val d = a[si + k] - b[sj + k]
                    if (d != 0) return d
                }
                i = ei
                j = ej
            } else {
                val d = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (d != 0) return d
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
