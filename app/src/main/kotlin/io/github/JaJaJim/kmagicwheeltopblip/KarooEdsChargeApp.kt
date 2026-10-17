package io.github.JaJaJim.kmagicwheeltopblip

import android.app.Application
import timber.log.Timber

class KarooEdsChargeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
    }
}
