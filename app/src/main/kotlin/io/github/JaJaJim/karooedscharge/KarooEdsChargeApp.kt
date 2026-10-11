package io.github.JaJaJim.karooedscharge

import android.app.Application
import timber.log.Timber

class KarooEdsChargeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
    }
}
