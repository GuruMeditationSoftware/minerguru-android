package app.minerguru

import android.app.Application
import app.minerguru.model.Lang

/** Connects the string resources before any activity, service or widget shows text. */
class MinerGuruApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Lang.init(this)
    }
}
