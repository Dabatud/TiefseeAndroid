package com.tiefsee.android.data

import java.io.File
import java.util.Locale

enum class Kind {
    DIR,
    IMAGE,      // декодируется системой: jpg png gif webp bmp heic avif ico
    SPECIAL,    // собственные декодеры: psd psb qoi clip
    SVG,
    VIDEO,
    PDF,
    TEXT,
    MARKDOWN,
    DOCX,
    PPTX,
    ARCHIVE,
    OTHER;

    /** Можно показать в просмотрщике (листается вместе с картинками). */
    val isMedia get() = this == IMAGE || this == SPECIAL || this == SVG || this == VIDEO
}

object FileTypes {
    val image = setOf("jpg", "jpeg", "jfif", "png", "apng", "gif", "webp", "bmp", "heic", "heif", "avif", "ico", "wbmp", "dng")
    val special = setOf("psd", "psb", "qoi", "clip")
    val video = setOf("mp4", "webm", "mkv", "3gp", "mov", "m4v", "ts")
    val text = setOf(
        "txt", "log", "json", "xml", "ini", "cfg", "conf", "csv", "tsv", "yaml", "yml", "toml",
        "js", "ts", "css", "html", "htm", "py", "kt", "java", "c", "cpp", "h", "cs", "go", "rs",
        "sh", "bat", "ps1", "sql", "srt", "ass", "lua", "php", "rb", "swift", "gradle", "properties", "nfo"
    )
    val markdown = setOf("md", "markdown")
    val archive = setOf("zip", "cbz", "tar", "cbt")

    fun ext(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i < 0) "" else name.substring(i + 1).lowercase(Locale.ROOT)
    }

    fun kind(name: String): Kind {
        val e = ext(name)
        return when {
            e in image -> Kind.IMAGE
            e in special -> Kind.SPECIAL
            e == "svg" -> Kind.SVG
            e in video -> Kind.VIDEO
            e == "pdf" -> Kind.PDF
            e in markdown -> Kind.MARKDOWN
            e in text -> Kind.TEXT
            e == "docx" -> Kind.DOCX
            e == "pptx" -> Kind.PPTX
            e in archive -> Kind.ARCHIVE
            else -> Kind.OTHER
        }
    }

    fun kind(f: File): Kind = if (f.isDirectory) Kind.DIR else kind(f.name)

    fun mime(name: String): String = when (ext(name)) {
        "jpg", "jpeg", "jfif" -> "image/jpeg"
        "png", "apng" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heif"
        "avif" -> "image/avif"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "psd", "psb" -> "image/vnd.adobe.photoshop"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        "mov" -> "video/quicktime"
        "pdf" -> "application/pdf"
        "zip", "cbz" -> "application/zip"
        "tar", "cbt" -> "application/x-tar"
        "md", "markdown" -> "text/markdown"
        "html", "htm" -> "text/html"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        in text -> "text/plain"
        else -> "application/octet-stream"
    }

    fun iconName(kind: Kind, name: String = ""): String = when (kind) {
        Kind.DIR -> "folder"
        Kind.IMAGE -> "image"
        Kind.SPECIAL -> "psd"
        Kind.SVG -> "svg"
        Kind.VIDEO -> "video"
        Kind.PDF -> "pdf"
        Kind.TEXT -> "text"
        Kind.MARKDOWN -> "markdown"
        Kind.DOCX -> "word"
        Kind.PPTX -> "ppt"
        Kind.ARCHIVE -> "archive"
        Kind.OTHER -> "file"
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, if (v < 10) "%.2f %s" else "%.1f %s", v, units[i])
    }
}
