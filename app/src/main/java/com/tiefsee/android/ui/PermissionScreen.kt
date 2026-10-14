package com.tiefsee.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageView
import com.tiefsee.android.MainActivity
import com.tiefsee.android.data.Storage
import com.tiefsee.android.util.I18n
import com.tiefsee.android.util.MATCH
import com.tiefsee.android.util.Palette
import com.tiefsee.android.util.WRAP
import com.tiefsee.android.util.dp
import com.tiefsee.android.util.label
import com.tiefsee.android.util.lp
import com.tiefsee.android.util.pillButton
import com.tiefsee.android.util.vbox

/** Запрос доступа к файлам: без него нельзя листать соседние файлы папки. */
class PermissionScreen(act: MainActivity) : Screen(act) {

    override val root = act.vbox()

    init {
        root.setBackgroundColor(Palette.bg)
        root.gravity = Gravity.CENTER
        root.setPadding(act.dp(32), act.dp(32), act.dp(32), act.dp(32))
        root.addView(ImageView(act).apply { setImageResource(act.applicationInfo.icon) }, lp(act.dp(96), act.dp(96)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(act.label("Tiefsee", 26f, Palette.text, bold = true).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP).apply {
            topMargin = act.dp(16)
        })
        root.addView(act.label(I18n.t("permTitle"), 17f, Palette.text, bold = true).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP).apply {
            topMargin = act.dp(24)
        })
        root.addView(act.label(I18n.t("permText"), 14f, Palette.textDim).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }, lp(MATCH, WRAP).apply { topMargin = act.dp(10) })
        root.addView(act.pillButton(I18n.t("permButton"), primary = true) { request() }, lp(WRAP, WRAP).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = act.dp(28)
        })
        root.addView(act.pillButton(I18n.t("settings")) { act.openSettings() }, lp(WRAP, WRAP).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = act.dp(12)
        })
    }

    private fun request() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                act.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + act.packageName)))
            } catch (_: Exception) {
                act.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else act.requestLegacyPermission()
    }

    override fun onResume() {
        if (Storage.hasAccess()) act.onAccessGranted()
    }

    override fun onInsets(top: Int, bottom: Int, left: Int, right: Int) {
        root.setPadding(left + act.dp(32), top + act.dp(32), right + act.dp(32), bottom + act.dp(32))
    }
}
