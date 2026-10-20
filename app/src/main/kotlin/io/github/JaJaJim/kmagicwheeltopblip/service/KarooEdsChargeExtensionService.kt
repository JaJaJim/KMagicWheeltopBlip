package io.github.JaJaJim.kmagicwheeltopblip.service

import io.github.JaJaJim.kmagicwheeltopblip.BuildConfig
import io.github.JaJaJim.kmagicwheeltopblip.ble.WheeltopManager
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension

class KarooEdsChargeExtensionService : KarooExtension("kmagic-wheeltop-blip", BuildConfig.VERSION_NAME) {

    companion object {
        const val EXTENSION_ID = "kmagic-wheeltop-blip"
    }

    private lateinit var wheeltopManager: WheeltopManager

    override fun onCreate() {
        super.onCreate()
        wheeltopManager = WheeltopManager.getInstance(applicationContext)
    }

    override val types: List<DataTypeImpl> = emptyList()
}
