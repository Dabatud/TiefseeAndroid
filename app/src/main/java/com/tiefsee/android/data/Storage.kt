package com.tiefsee.android.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

object Storage {

    class Root(val name: String, val file: File, val icon: String)

    fun internal(): File = Environment.getExternalStorageDirectory()

    /** Внутренняя память + SD-карты/USB. */
    fun roots(ctx: Context): List<Root> {
        val out = ArrayList<Root>()
        val primary = internal()
        out.add(Root("internal", primary, "home"))
        for (d in ctx.getExternalFilesDirs(null)) {
            if (d == null) continue
            val p = d.absolutePath
            val idx = p.indexOf("/Android/data/")
            if (idx <= 0) continue
            val root = File(p.substring(0, idx))
            if (root.absolutePath != primary.absolutePath && root.exists()) out.add(Root(root.name, root, "sd"))
        }
        return out
    }

    fun shortcuts(): List<Pair<String, File>> {
        val base = internal()
        return listOf(
            "DCIM" to File(base, Environment.DIRECTORY_DCIM),
            "Pictures" to File(base, Environment.DIRECTORY_PICTURES),
            "Download" to File(base, Environment.DIRECTORY_DOWNLOADS),
            "Movies" to File(base, Environment.DIRECTORY_MOVIES),
            "Documents" to File(base, Environment.DIRECTORY_DOCUMENTS),
        ).filter { it.second.exists() }
    }

    /** Красивое отображение пути: /storage/emulated/0/DCIM → Internal/DCIM. */
    fun displayPath(ctx: Context, f: File): String {
        val p = f.absolutePath
        for (r in roots(ctx)) {
            val rp = r.file.absolutePath
            if (p == rp || p.startsWith("$rp/")) {
                val label = if (r.icon == "home") com.tiefsee.android.util.I18n.t("internal") else r.name
                return label + p.removePrefix(rp)
            }
        }
        return p
    }

    fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else internal().canRead() && (internal().list() != null)

    /**
     * Пытаемся превратить content:// URI из «Открыть с помощью» в реальный путь,
     * чтобы можно было листать соседние файлы папки (как в Tiefsee).
     */
    fun resolvePath(ctx: Context, uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }?.takeIf { it.exists() }
        if (uri.scheme != "content") return null

        try {
            if (DocumentsContract.isDocumentUri(ctx, uri)) {
                val id = DocumentsContract.getDocumentId(uri)
                when (uri.authority) {
                    "com.android.externalstorage.documents" -> {
                        val parts = id.split(":", limit = 2)
                        val rel = parts.getOrElse(1) { "" }
                        val base = if (parts[0].equals("primary", true)) internal() else File("/storage/${parts[0]}")
                        File(base, rel).takeIf { it.exists() }?.let { return it }
                    }
                    "com.android.providers.downloads.documents" -> {
                        if (id.startsWith("raw:")) File(id.removePrefix("raw:")).takeIf { it.exists() }?.let { return it }
                    }
                    "com.android.providers.media.documents" -> {
                        val parts = id.split(":")
                        if (parts.size == 2) {
                            val base = when (parts[0]) {
                                "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                else -> MediaStore.Files.getContentUri("external")
                            }
                            queryData(ctx, base, "_id=?", arrayOf(parts[1]))?.let { return it }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // MediaStore и многие файловые менеджеры отдают колонку _data.
        queryData(ctx, uri, null, null)?.let { return it }

        // Эвристика: путь зашит в URI (FileProvider многих приложений).
        val path = Uri.decode(uri.path ?: "")
        for (marker in listOf("/storage/", "/sdcard/", "/mnt/")) {
            val i = path.indexOf(marker)
            if (i >= 0) {
                val f = File(path.substring(i))
                if (f.exists()) return f
            }
        }
        val i = path.indexOf("/root/")
        if (i >= 0) {
            val f = File(path.substring(i + 5))
            if (f.exists()) return f
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun queryData(ctx: Context, uri: Uri, sel: String?, args: Array<String>?): File? {
        return try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), sel, args, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx >= 0) c.getString(idx)?.let { File(it) }?.takeIf { it.exists() && it.canRead() } else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** Копия содержимого URI во временную папку (если доступа к оригиналу нет). */
    fun copyToCache(ctx: Context, uri: Uri): File? {
        var name = displayName(ctx, uri).replace('/', '_')
        if (!name.contains('.')) {
            val mime = ctx.contentResolver.getType(uri) ?: ""
            val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            if (ext != null) name = "$name.$ext"
        }
        val dir = File(ctx.cacheDir, "shared/${System.currentTimeMillis()}").apply { mkdirs() }
        val out = File(dir, name)
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { inp -> out.outputStream().use { inp.copyTo(it) } } ?: return null
            out
        } catch (_: Exception) {
            null
        }
    }
}
