package com.tiefsee.android.data

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import com.tiefsee.android.FilesProvider
import com.tiefsee.android.util.I18n
import com.tiefsee.android.util.toast
import java.io.File

object FileOps {

    fun share(act: Activity, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { FilesProvider.uriFor(act, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = FileTypes.mime(files[0].name)
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.clipData = ClipData.newUri(act.contentResolver, files[0].name, uris[0]).apply {
            for (i in 1 until uris.size) addItem(ClipData.Item(uris[i]))
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        act.startActivity(Intent.createChooser(intent, I18n.t("share")))
    }

    fun openWith(act: Activity, f: File) {
        val uri = FilesProvider.uriFor(act, f)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, FileTypes.mime(f.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(act.contentResolver, f.name, uri)
        }
        try {
            act.startActivity(Intent.createChooser(intent, I18n.t("openWith")))
        } catch (_: Exception) {
            act.toast(I18n.t("noApp"))
        }
    }

    fun setAs(act: Activity, f: File) {
        val uri = FilesProvider.uriFor(act, f)
        val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
            setDataAndType(uri, FileTypes.mime(f.name))
            putExtra("mimeType", FileTypes.mime(f.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(act.contentResolver, f.name, uri)
        }
        try {
            act.startActivity(Intent.createChooser(intent, I18n.t("setAs")))
        } catch (_: Exception) {
            act.toast(I18n.t("noApp"))
        }
    }

    fun copyText(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Tiefsee", text))
        ctx.toast(I18n.t("copied"))
    }

    fun delete(ctx: Context, f: File): Boolean {
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (ok) scan(ctx, f)
        return ok
    }

    /** Возвращает новый файл или null при ошибке. */
    fun rename(ctx: Context, f: File, newName: String): File? {
        val clean = newName.trim()
        if (clean.isEmpty() || clean.contains('/') || clean == f.name) return null
        val target = File(f.parentFile, clean)
        if (target.exists()) return null
        if (!f.renameTo(target)) return null
        scan(ctx, f)
        scan(ctx, target)
        return target
    }

    fun mkdir(parent: File, name: String): File? {
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/')) return null
        val d = File(parent, clean)
        return if (d.mkdirs()) d else null
    }

    fun scan(ctx: Context, f: File) {
        try {
            MediaScannerConnection.scanFile(ctx.applicationContext, arrayOf(f.absolutePath), null, null)
        } catch (_: Exception) {
        }
    }

    fun openUrl(act: Activity, url: String) {
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            act.toast(I18n.t("noApp"))
        }
    }
}
