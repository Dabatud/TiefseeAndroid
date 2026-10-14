package com.tiefsee.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.tiefsee.android.data.Archives
import com.tiefsee.android.data.FileLister
import com.tiefsee.android.data.FileTypes
import com.tiefsee.android.data.Kind
import com.tiefsee.android.data.Prefs
import com.tiefsee.android.data.Storage
import com.tiefsee.android.formats.Thumbs
import com.tiefsee.android.ui.BrowserScreen
import com.tiefsee.android.ui.BulkScreen
import com.tiefsee.android.ui.OfficeScreen
import com.tiefsee.android.ui.PdfScreen
import com.tiefsee.android.ui.PermissionScreen
import com.tiefsee.android.ui.Screen
import com.tiefsee.android.ui.SettingsScreen
import com.tiefsee.android.ui.TextScreen
import com.tiefsee.android.ui.ViewerScreen
import com.tiefsee.android.util.Bg
import com.tiefsee.android.util.I18n
import com.tiefsee.android.util.Palette
import com.tiefsee.android.util.toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var host: FrameLayout
    private val stack = ArrayList<Screen>()
    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var immersive = false

    val top: Screen? get() = stack.lastOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thumbs.init(this)
        host = FrameLayout(this)
        host.setBackgroundColor(Palette.bg)
        setContentView(host)
        setupWindow()

        host.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                // В режиме погружения панели скрыты, но отступы оставляем стабильными.
                val s = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
                insetTop = maxOf(i.top, s.top); insetBottom = maxOf(i.bottom, s.bottom)
                insetLeft = maxOf(i.left, s.left); insetRight = maxOf(i.right, s.right)
            } else {
                @Suppress("DEPRECATION")
                run {
                    insetTop = insets.stableInsetTop; insetBottom = insets.stableInsetBottom
                    insetLeft = insets.stableInsetLeft; insetRight = insets.stableInsetRight
                }
            }
            stack.forEach { it.onInsets(insetTop, insetBottom, insetLeft, insetRight) }
            insets
        }

        if (!handleIntent(intent)) startDefault()
    }

    private fun startDefault() {
        if (!Storage.hasAccess()) {
            push(PermissionScreen(this))
            return
        }
        val last = File(Prefs.lastFolder)
        val start = if (Prefs.openLastFolder && Prefs.lastFolder.isNotEmpty() && last.isDirectory) last else Storage.internal()
        push(BrowserScreen(this, start))
    }

    /** Вызывается из PermissionScreen после выдачи доступа. */
    fun onAccessGranted() {
        clearStack()
        startDefault()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ---------- Открытие файлов из других приложений ----------

    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uris.add(it) }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val u = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (u != null) uris.add(u)
                else {
                    // «Открыть веб-изображение» (как в Tiefsee): поделились ссылкой на картинку из браузера.
                    val url = Regex("https?://\\S+").find(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "")?.value
                    if (url != null) {
                        intent.action = null
                        toast(I18n.t("downloading"))
                        Bg.runCatching({ downloadToCache(url) }) { r ->
                            r.onSuccess { openFile(it) }.onFailure { toast(I18n.t("cannotOpen")) }
                            if (stack.isEmpty()) startDefault()
                        }
                        return true
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
            else -> return false
        }
        if (uris.isEmpty()) return false
        intent.action = null // не открывать повторно при пересоздании

        Bg.run({
            uris.mapNotNull { u ->
                Storage.resolvePath(this, u)?.takeIf { it.canRead() } ?: Storage.copyToCache(this, u)
            }
        }) { files ->
            if (files.isEmpty()) {
                toast(I18n.t("cannotOpen"))
                if (stack.isEmpty()) startDefault()
                return@run
            }
            if (stack.isEmpty()) {
                // Под просмотрщиком — браузер папки файла (если есть доступ).
                val parent = files[0].parentFile
                if (Storage.hasAccess() && parent != null && !files[0].absolutePath.startsWith(cacheDir.absolutePath)) {
                    push(BrowserScreen(this, parent))
                }
            }
            if (files.size == 1) openFile(files[0], fromOutside = true)
            else push(ViewerScreen(this, files.filter { FileTypes.kind(it.name).isMedia }, 0))
            if (stack.isEmpty()) startDefault()
        }
        return true
    }

    /** Открыть файл в подходящем экране. Для медиа — листать всю папку, как в Tiefsee. */
    fun openFile(f: File, siblings: List<File>? = null, fromOutside: Boolean = false) {
        when (FileTypes.kind(f.name)) {
            Kind.IMAGE, Kind.SPECIAL, Kind.SVG, Kind.VIDEO -> {
                if (siblings != null) {
                    push(ViewerScreen(this, siblings, siblings.indexOf(f).coerceAtLeast(0)))
                } else {
                    val parent = f.parentFile
                    val canList = fromOutside && parent != null && parent.canRead() && !f.absolutePath.startsWith(cacheDir.absolutePath)
                    if (canList) {
                        Bg.run({ FileLister.media(parent!!).map { it.file } }) { list ->
                            val l = if (list.contains(f)) list else listOf(f)
                            push(ViewerScreen(this, l, l.indexOf(f).coerceAtLeast(0)))
                        }
                    } else push(ViewerScreen(this, listOf(f), 0))
                }
            }
            Kind.PDF -> push(PdfScreen(this, f))
            Kind.TEXT -> push(TextScreen(this, f, false))
            Kind.MARKDOWN -> push(TextScreen(this, f, true))
            Kind.DOCX, Kind.PPTX -> push(OfficeScreen(this, f))
            Kind.ARCHIVE -> openArchive(f)
            Kind.DIR -> push(BrowserScreen(this, f))
            Kind.OTHER -> com.tiefsee.android.data.FileOps.openWith(this, f)
        }
    }

    fun openArchive(f: File, bulk: Boolean = false) {
        val dlg = android.app.ProgressDialog(this, com.tiefsee.android.util.dialogTheme())
        @Suppress("DEPRECATION")
        run {
            dlg.setMessage(I18n.t("opening"))
            dlg.setCancelable(false)
            dlg.show()
        }
        Bg.runCatching({ Archives.open(this, f) }) { r ->
            dlg.dismiss()
            r.onSuccess { opened ->
                if (opened.entries.isEmpty()) toast(I18n.t("archiveEmpty"))
                else if (bulk) push(BulkScreen(this, opened.entries, 0, opened.title))
                else push(ViewerScreen(this, opened.entries, 0, opened.title))
            }.onFailure { toast(I18n.t("cannotOpen") + ": " + (it.message ?: "")) }
        }
    }

    private fun downloadToCache(url: String): File {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Tiefsee")
        try {
            val type = conn.contentType ?: ""
            var name = Uri.parse(url).lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "image"
            if (FileTypes.kind(name) == Kind.OTHER) {
                val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type.substringBefore(';').trim()) ?: "jpg"
                name = "$name.$ext"
            }
            val dir = File(cacheDir, "shared/${System.currentTimeMillis()}").apply { mkdirs() }
            val out = File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
            conn.inputStream.use { inp -> out.outputStream().use { inp.copyTo(it) } }
            return out
        } finally {
            conn.disconnect()
        }
    }

    fun openSettings() = push(SettingsScreen(this))

    // ---------- Стек экранов ----------

    fun push(s: Screen) {
        top?.let { it.onHide(); it.root.visibility = View.GONE }
        stack.add(s)
        host.addView(s.root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        s.onInsets(insetTop, insetBottom, insetLeft, insetRight)
        applyBarsAppearance(s)
        s.onShow()
        host.requestApplyInsets()
    }

    fun pop() {
        val s = stack.removeLastOrNull() ?: return
        s.onHide()
        s.onDestroy()
        host.removeView(s.root)
        val t = top
        if (t == null) {
            finish()
            return
        }
        t.root.visibility = View.VISIBLE
        applyBarsAppearance(t)
        t.onShow()
    }

    /** Заменить верхний экран (без мигания нижнего). */
    fun replaceTop(s: Screen) {
        val old = stack.removeLastOrNull()
        old?.onHide(); old?.onDestroy()
        old?.let { host.removeView(it.root) }
        push(s)
    }

    private fun clearStack() {
        while (stack.isNotEmpty()) {
            val s = stack.removeAt(stack.size - 1)
            s.onDestroy()
            host.removeView(s.root)
        }
    }

    fun goBack() {
        val t = top ?: return finish()
        if (!t.onBack()) pop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goBack()

    /** Пересоздать интерфейс после смены темы/языка. */
    fun rebuildUi() {
        Palette.apply(this)
        I18n.apply(this, Prefs.language)
        host.setBackgroundColor(Palette.bg)
        val browserDir = stack.filterIsInstance<BrowserScreen>().lastOrNull()?.currentDir
        clearStack()
        if (!Storage.hasAccess()) push(PermissionScreen(this))
        else push(BrowserScreen(this, browserDir ?: Storage.internal()))
        push(SettingsScreen(this))
    }

    // ---------- Окно / системные панели ----------

    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun applyBarsAppearance(s: Screen) {
        val light = s.lightSystemBars
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
        } else {
            @Suppress("DEPRECATION")
            run {
                var f = window.decorView.systemUiVisibility
                f = if (light) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                else f and (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
                window.decorView.systemUiVisibility = f
            }
        }
        if (s !is ViewerScreen) setImmersiveMode(false)
    }

    /** Полноэкранный режим просмотрщика. */
    fun setImmersiveMode(on: Boolean) {
        if (immersive == on) return
        immersive = on
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (on) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else c.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            run {
                val base = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                val keepLight = window.decorView.systemUiVisibility and
                    (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
                window.decorView.systemUiVisibility = if (on) base or keepLight or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else base or keepLight
            }
        }
    }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------- Разрешения (Android 8–10) ----------

    fun requestLegacyPermission() {
        requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED && top is PermissionScreen) onAccessGranted()
    }

    override fun onResume() {
        super.onResume()
        top?.onResume()
    }

    override fun onPause() {
        super.onPause()
        top?.onPause()
        (stack.lastOrNull { it is BrowserScreen } as? BrowserScreen)?.let { Prefs.lastFolder = it.currentDir.absolutePath }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Prefs.theme == "system") {
            val wasDark = Palette.dark
            Palette.apply(this)
            if (wasDark != Palette.dark) { rebuildUi(); pop(); return }
        }
        stack.forEach { it.onConfigChanged() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (top?.onKey(keyCode, event) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        clearStack()
        super.onDestroy()
    }
}
