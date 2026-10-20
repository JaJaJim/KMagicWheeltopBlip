package io.github.JaJaJim.kmagicwheeltopblip.service

import io.github.JaJaJim.kmagicwheeltopblip.BuildConfig
import io.github.JaJaJim.kmagicwheeltopblip.ble.WheeltopManager
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class KarooEdsChargeExtensionService : KarooExtension("kmagic-wheeltop-blip", BuildConfig.VERSION_NAME) {

    companion object {
        const val EXTENSION_ID = "kmagic-wheeltop-blip"
        const val BATTERY_WIDGET_TYPE_ID = "wheeltop-battery-widget"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var wheeltopManager: WheeltopManager

    override fun onCreate() {
        super.onCreate()
        wheeltopManager = WheeltopManager.getInstance(applicationContext)
    }

    override val types: List<DataTypeImpl> = listOf(
        WheelTopBatteryWidget(EXTENSION_ID, BATTERY_WIDGET_TYPE_ID)
    )

    private inner class WheelTopBatteryWidget(
        extension: String,
        typeId: String
    ) : DataTypeImpl(extension, typeId) {

        override fun startStream(emitter: Emitter<StreamState>) {
            val job = serviceScope.launch {
                // Ticker job for 5-minute auto-refresh
                launch {
                    while (true) {
                        val lastTimestamp = wheeltopManager.lastGattTimestamp.value
                        val ageMs = System.currentTimeMillis() - lastTimestamp
                        if (ageMs >= 300_000) { // >= 5 minutes old
                            wheeltopManager.pullGattBlocks()
                        }
                        delay(60_000) // Re-check age every minute
                    }
                }

                wheeltopManager.lastGattAsciiText.collectLatest { asciiText ->
                    if (!asciiText.isNullOrBlank()) {
                        val parsedValues = parseAsciiVoltages(asciiText)
                        if (parsedValues.isNotEmpty()) {
                            emitter.onNext(
                                StreamState.Streaming(
                                    DataPoint(
                                        dataTypeId = dataTypeId,
                                        values = parsedValues
                                    )
                                )
                            )
                        } else {
                            emitter.onNext(StreamState.Searching)
                        }
                    } else {
                        emitter.onNext(StreamState.Searching)
                    }
                }
            }

            emitter.setCancellable {
                job.cancel()
            }
        }

        private fun parseAsciiVoltages(text: String): Map<String, Double> {
            val resultMap = mutableMapOf<String, Double>()
            val kvMap = mutableMapOf<String, Float>()

            val parts = text.split(",", ";", "\n", "\r")
            for (part in parts) {
                val kv = part.split(":", "=")
                if (kv.size == 2) {
                    val key = kv[0].trim().uppercase()
                    val cleanValStr = kv[1].trim().replace("V", "")
                    cleanValStr.toFloatOrNull()?.let { raw ->
                        val volts = if (raw > 20f) raw / 100.0f else raw
                        kvMap[key] = volts
                    }
                }
            }

            // 1. EDS OX Model (contains POWER_1)
            if (kvMap.containsKey("POWER_1")) {
                val rm = kvMap["POWER_1"] ?: 0.0f
                val rd = kvMap["POWER_2"] ?: 0.0f
                resultMap[DataType.Field.SINGLE] = rm.toDouble() // Remote RM
                resultMap["field_2"] = rd.toDouble() // Derailleur RD
                return resultMap
            }

            // 2. EDS TX or GeX/GX Model (contains L_POWER)
            if (kvMap.containsKey("L_POWER")) {
                val sl = kvMap["L_POWER"] ?: 0.0f
                val sr = kvMap["R_POWER"] ?: 0.0f
                val fd = kvMap["Q_POWER"] ?: 0.0f
                val rd = kvMap["H_POWER"] ?: 0.0f

                resultMap[DataType.Field.SINGLE] = sl.toDouble() // Shifter Left
                resultMap["field_2"] = sr.toDouble() // Shifter Right
                resultMap["field_3"] = rd.toDouble() // Rear Derailleur

                // Add Front Derailleur for TX model
                if (fd > 1.0f) {
                    resultMap["field_4"] = fd.toDouble() // Front Derailleur
                }
            }

            return resultMap
        }
    }
}
