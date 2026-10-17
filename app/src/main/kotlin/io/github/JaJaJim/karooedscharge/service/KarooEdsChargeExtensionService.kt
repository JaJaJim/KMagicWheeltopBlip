package io.github.JaJaJim.karooedscharge.service

import io.github.JaJaJim.karooedscharge.ble.WheeltopManager
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class KarooEdsChargeExtensionService : KarooExtension("kmagic-wheeltop-blip", "0.0.1") {

    companion object {
        const val EXTENSION_ID = "kmagic-wheeltop-blip"
        const val REAR_BATTERY_TYPE_ID = "wheeltop-rear-battery"
        const val FRONT_BATTERY_TYPE_ID = "wheeltop-front-battery"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var wheeltopManager: WheeltopManager

    override fun onCreate() {
        super.onCreate()
        wheeltopManager = WheeltopManager.getInstance(applicationContext)
    }

    override val types: List<DataTypeImpl> = listOf(
        BatteryDataType(EXTENSION_ID, REAR_BATTERY_TYPE_ID, isRear = true),
        BatteryDataType(EXTENSION_ID, FRONT_BATTERY_TYPE_ID, isRear = false)
    )

    private inner class BatteryDataType(
        extension: String,
        typeId: String,
        private val isRear: Boolean
    ) : DataTypeImpl(extension, typeId) {

        override fun startStream(emitter: Emitter<StreamState>) {
            val job = serviceScope.launch {
                val flow = if (isRear) wheeltopManager.gattRearVolts else wheeltopManager.gattFrontVolts

                flow.collectLatest { volts ->
                    if (volts != null) {
                        emitter.onNext(
                            StreamState.Streaming(
                                DataPoint(
                                    dataTypeId = dataTypeId,
                                    values = mapOf(DataType.Field.SINGLE to volts.toDouble())
                                )
                            )
                        )
                    } else {
                        emitter.onNext(StreamState.Searching)
                    }
                }
            }
            emitter.setCancellable {
                job.cancel()
            }
        }
    }
}
