package com.tiefsee.android.meta

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.tiefsee.android.formats.Imaging
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Поиск по картинке — как в Tiefsee: картинка сжимается (≤ 1000×1000 пикселей по площади),
 * загружается на временный сервер автора Tiefsee, затем открывается страница поисковика с её URL.
 */
object ImageSearch {

    class Engine(val id: String, val name: String, val urlTemplate: String)

    val engines = listOf(
        Engine("saucenao", "SauceNAO", "https://saucenao.com/search.php?db=999&url={url}"),
        Engine("yandex", "Yandex", "https://yandex.com/images/search?rpt=imageview&url={url}"),
        Engine("ascii2d", "Ascii2d", "https://ascii2d.net/search/url/{url}"),
        Engine("google", "Google Lens", "https://lens.google.com/uploadbyurl?url={url}"),
        Engine("bing", "Bing", "https://www.bing.com/images/search?view=detailv2&iss=sbi&FORM=SBIIDP&sbisrc=UrlPaste&idpbck=1&q=imgurl:{url}"),
    )

    private val servers = listOf(
        "https://hbl917070.com/imgSearch/upload" to 8_000,
        "https://tiefseesearchimageserver.onrender.com/imgSearch/upload" to 15_000,
    )

    /** Выполняется в фоне. Возвращает готовый URL поиска или бросает исключение. */
    fun search(ctx: android.content.Context, file: File, engine: Engine): String {
        val jpeg = compress(ctx, file)
        var lastErr: Exception? = null
        for ((server, timeout) in servers) {
            try {
                val url = upload(server, jpeg, timeout)
                if (url.isNotEmpty()) return engine.urlTemplate.replace("{url}", url)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: IllegalStateException("upload failed")
    }

    private fun compress(ctx: android.content.Context, file: File): ByteArray {
        val loaded = Imaging.load(ctx, file, 2048)
        val src: Bitmap = loaded.bitmap ?: run {
            val d = loaded.animated ?: throw IllegalStateException("decode")
            val b = Bitmap.createBitmap(d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, b.width, b.height)
            d.draw(Canvas(b))
            b
        }
        val max = 1000.0
        val area = src.width.toDouble() * src.height
        val zoom = if (area > max * max) Math.sqrt(max * max / area) else 1.0
        val w = (src.width * zoom).toInt().coerceAtLeast(1)
        val h = (src.height * zoom).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        c.drawBitmap(Bitmap.createScaledBitmap(soft, w, h, true), 0f, 0f, null)
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        return bos.toByteArray()
    }

    private fun upload(server: String, jpeg: ByteArray, timeout: Int): String {
        val boundary = "----Tiefsee" + System.currentTimeMillis()
        val conn = URL(server).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { os ->
                os.write("--$boundary\r\nContent-Disposition: form-data; name=\"media\"; filename=\"image.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                os.write(jpeg)
                os.write("\r\n--$boundary--\r\n".toByteArray())
            }
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            var url = json.optJSONObject("data")?.optString("media") ?: json.optString("media")
            if (url.startsWith("http://")) url = "https://" + url.removePrefix("http://")
            return url
        } finally {
            conn.disconnect()
        }
    }
}
