package io.github.JaJaJim.karooedscharge.service

import io.github.JaJaJim.karooedscharge.ble.WheeltopManager
import io.hammerhead.karooext.KarooExtension
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

class KarooEdsChargeExtensionService : KarooExtension("karoo-eds-charge", "1.0.0") {

    companion object {
        private const val REAR_BATTERY_TYPE_ID = "wheeltop-rear-battery"
        private const val FRONT_BATTERY_TYPE_ID = "wheeltop-front-battery"
        private const val COMBINED_BATTERY_TYPE_ID = "wheeltop-combined-battery"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var wheeltopManager: WheeltopManager

    override fun onCreate() {
        super.onCreate()
        wheeltopManager = WheeltopManager.getInstance(applicationContext)

        serviceScope.launch {
            wheeltopManager.batteryRearVolts.collectLatest { volts ->
                val displayStr = if (volts != null) "%.2f V".format(volts) else "-- V"
                val value = volts?.toDouble() ?: 0.0
                streamDataFlow(
                    DataType(extensionId, REAR_BATTERY_TYPE_ID),
                    DataPoint(
                        dataTypeId = REAR_BATTERY_TYPE_ID,
                        values = mapOf(
                            DataPoint.Value.DOUBLE to value,
                            DataPoint.Value.STRING to displayStr
                        )
                    )
                )
            }
        }

        serviceScope.launch {
            wheeltopManager.batteryFrontVolts.collectLatest { volts ->
                val displayStr = if (volts != null) "%.2f V".format(volts) else "-- V"
                val value = volts?.toDouble() ?: 0.0
                streamDataFlow(
                    DataType(extensionId, FRONT_BATTERY_TYPE_ID),
                    DataPoint(
                        dataTypeId = FRONT_BATTERY_TYPE_ID,
                        values = mapOf(
                            DataPoint.Value.DOUBLE to value,
                            DataPoint.Value.STRING to displayStr
                        )
                    )
                )
            }
        }

        serviceScope.launch {
            wheeltopManager.batteryRearVolts.collectLatest { rear ->
                val front = wheeltopManager.batteryFrontVolts.value
                val displayStr = when {
                    rear != null && front != null -> "H:%.2fV V:%.2fV".format(rear, front)
                    rear != null -> "H:%.2fV".format(rear)
                    front != null -> "V:%.2fV".format(front)
                    else -> "Wheeltop -- V"
                }
                streamDataFlow(
                    DataType(extensionId, COMBINED_BATTERY_TYPE_ID),
                    DataPoint(
                        dataTypeId = COMBINED_BATTERY_TYPE_ID,
                        values = mapOf(
                            DataPoint.Value.STRING to displayStr
                        )
                    )
                )
            }
        }
    }

    override val types: List<DataType> = listOf(
        DataType(extensionId, REAR_BATTERY_TYPE_ID),
        DataType(extensionId, FRONT_BATTERY_TYPE_ID),
        DataType(extensionId, COMBINED_BATTERY_TYPE_ID)
    )

    private fun streamDataFlow(dataType: DataType, dataPoint: DataPoint) {
        try {
            // Update Karoo DataField stream
            dispatch(StreamState.Streaming(dataPoint))
        } catch (e: Exception) {
            Timber.e(e, "Error streaming data to Karoo")
        }
    }
}
