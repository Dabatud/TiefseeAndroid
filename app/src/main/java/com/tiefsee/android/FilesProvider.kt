package com.tiefsee.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.tiefsee.android.data.FileTypes
import java.io.File
import java.io.FileNotFoundException

/**
 * Простой аналог FileProvider (без androidx): отдаёт файлы другим приложениям
 * для «Поделиться», «Открыть с помощью» и «Установить как обои».
 * Провайдер не экспортирован — доступ только по выданному разрешению на URI.
 */
class FilesProvider : ContentProvider() {

    override fun onCreate() = true

    private fun fileOf(uri: Uri): File {
        val path = uri.path ?: throw FileNotFoundException()
        val f = File(path).canonicalFile
        if (!f.isFile) throw FileNotFoundException(path)
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode.contains('w')) throw SecurityException("read only")
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileOf(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                "_data" -> f.absolutePath
                else -> null
            }
        })
        return c
    }

    override fun getType(uri: Uri): String = FileTypes.mime(uri.lastPathSegment ?: "")

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.tiefsee.android.files"

        fun uriFor(@Suppress("UNUSED_PARAMETER") ctx: Context, f: File): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).path(f.absolutePath).build()
    }
}
