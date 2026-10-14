package com.tiefsee.android

import android.app.Application
import com.tiefsee.android.data.Prefs
import com.tiefsee.android.util.I18n
import com.tiefsee.android.util.Palette

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        I18n.apply(this, Prefs.language)
        Palette.apply(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
