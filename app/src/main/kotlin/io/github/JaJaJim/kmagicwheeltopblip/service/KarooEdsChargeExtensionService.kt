package io.github.JaJaJim.kmagicwheeltopblip.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import io.github.JaJaJim.kmagicwheeltopblip.BuildConfig
import io.github.JaJaJim.kmagicwheeltopblip.R
import io.github.JaJaJim.kmagicwheeltopblip.ble.WheeltopManager
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class KarooEdsChargeExtensionService : KarooExtension("kmagic-wheeltop-blip", BuildConfig.VERSION_NAME) {

    companion object {
        const val EXTENSION_ID = "kmagic-wheeltop-blip"
        const val BATTERY_WIDGET_TYPE_ID = "kmagic-wheeltop-blip"
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

        override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
            emitter.onNext(UpdateGraphicConfig(showHeader = true))

            val job = serviceScope.launch(Dispatchers.Main) {
                combine(
                    wheeltopManager.lastGattAsciiText,
                    wheeltopManager.blockStatusText,
                    wheeltopManager.isPullingGattBlocks,
                    wheeltopManager.boundMacAddress
                ) { asciiText, blockStatus, isPulling, boundMac ->
                    Tuple4(asciiText, blockStatus, isPulling, boundMac)
                }.collectLatest { (asciiText, blockStatus, isPulling, boundMac) ->
                    val remoteViews = RemoteViews(context.packageName, R.layout.wheeltop_widget_view)

                    // Touch Click-Sensitivity (Tap Widget to Refresh)
                    val clickIntent = Intent("io.github.JaJaJim.kmagicwheeltopblip.WIDGET_CLICK").apply {
                        setPackage(context.packageName)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        0,
                        clickIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    remoteViews.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

                    if (!asciiText.isNullOrBlank()) {
                        val parsedMap = parseAsciiMap(asciiText)
                        val boundName = wheeltopManager.boundDeviceName.value ?: "EDS TX"
                        val isOx = boundName.contains("OX", ignoreCase = true) || parsedMap.containsKey("POWER_1")
                        val isGx = boundName.contains("GX", ignoreCase = true) || boundName.contains("GEX", ignoreCase = true) || (parsedMap.containsKey("L_POWER") && (parsedMap["Q_POWER"] ?: 0f) <= 1.0f)

                        if (isOx) {
                            val rm = parsedMap["POWER_1"] ?: 0f
                            val rd = parsedMap["POWER_2"] ?: 0f

                            remoteViews.setViewVisibility(R.id.txt_top_left, View.GONE)
                            remoteViews.setViewVisibility(R.id.txt_top_right, View.GONE)
                            remoteViews.setViewVisibility(R.id.txt_top_center, View.VISIBLE)

                            remoteViews.setViewVisibility(R.id.txt_bottom_left, View.GONE)
                            remoteViews.setViewVisibility(R.id.txt_bottom_right, View.GONE)
                            remoteViews.setViewVisibility(R.id.txt_bottom_center, View.VISIBLE)

                            remoteViews.setTextViewText(R.id.txt_top_center, "RM: %.2fV".format(rm))
                            remoteViews.setTextViewText(R.id.txt_bottom_center, "RD: %.2fV".format(rd))
                        } else if (isGx) {
                            val sl = parsedMap["L_POWER"] ?: 0f
                            val sr = parsedMap["R_POWER"] ?: 0f
                            val rd = parsedMap["H_POWER"] ?: 0f

                            remoteViews.setViewVisibility(R.id.txt_top_left, View.VISIBLE)
                            remoteViews.setViewVisibility(R.id.txt_top_right, View.VISIBLE)
                            remoteViews.setViewVisibility(R.id.txt_top_center, View.GONE)

                            remoteViews.setViewVisibility(R.id.txt_bottom_left, View.GONE)
                            remoteViews.setViewVisibility(R.id.txt_bottom_right, View.GONE)
                            remoteViews.setViewVisibility(R.id.txt_bottom_center, View.VISIBLE)

                            remoteViews.setTextViewText(R.id.txt_top_left, "SL: %.2fV".format(sl))
                            remoteViews.setTextViewText(R.id.txt_top_right, "SR: %.2fV".format(sr))
                            remoteViews.setTextViewText(R.id.txt_bottom_center, "RD: %.2fV".format(rd))
                        } else {
                            val sl = parsedMap["L_POWER"] ?: 0f
                            val sr = parsedMap["R_POWER"] ?: 0f
                            val fd = parsedMap["Q_POWER"] ?: 0f
                            val rd = parsedMap["H_POWER"] ?: 0f

                            remoteViews.setViewVisibility(R.id.txt_top_left, View.VISIBLE)
                            remoteViews.setViewVisibility(R.id.txt_top_right, View.VISIBLE)
                            remoteViews.setViewVisibility(R.id.txt_top_center, View.GONE)

                            remoteViews.setViewVisibility(R.id.txt_bottom_left, View.VISIBLE)
                            remoteViews.setViewVisibility(R.id.txt_bottom_right, View.VISIBLE)
                            remoteViews.setViewVisibility(R.id.txt_bottom_center, View.GONE)

                            remoteViews.setTextViewText(R.id.txt_top_left, "SL: %.2fV".format(sl))
                            remoteViews.setTextViewText(R.id.txt_top_right, "SR: %.2fV".format(sr))
                            remoteViews.setTextViewText(R.id.txt_bottom_left, "FD: %.2fV".format(fd))
                            remoteViews.setTextViewText(R.id.txt_bottom_right, "RD: %.2fV".format(rd))
                        }
                    } else {
                        // Reason Status Messages
                        val reasonText = when {
                            boundMac.isNullOrBlank() -> "No Derailleur Bound"
                            isPulling -> "Syncing..."
                            blockStatus == "Pull Timed Out / Try Again" -> "Connection Failed"
                            blockStatus != null && blockStatus.contains("Failed", ignoreCase = true) -> "Block Read Failed"
                            else -> "Tap to Sync"
                        }

                        remoteViews.setViewVisibility(R.id.txt_top_left, View.GONE)
                        remoteViews.setViewVisibility(R.id.txt_top_right, View.GONE)
                        remoteViews.setViewVisibility(R.id.txt_top_center, View.VISIBLE)

                        remoteViews.setViewVisibility(R.id.txt_bottom_left, View.GONE)
                        remoteViews.setViewVisibility(R.id.txt_bottom_right, View.GONE)
                        remoteViews.setViewVisibility(R.id.txt_bottom_center, View.VISIBLE)

                        remoteViews.setTextViewText(R.id.txt_top_center, "WheelTop")
                        remoteViews.setTextViewText(R.id.txt_bottom_center, reasonText)
                    }

                    emitter.updateView(remoteViews)
                }
            }

            emitter.setCancellable {
                job.cancel()
            }
        }

        private fun parseAsciiMap(text: String): Map<String, Float> {
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
            return kvMap
        }

        private fun parseAsciiVoltages(text: String): Map<String, Double> {
            val resultMap = mutableMapOf<String, Double>()
            val kvMap = parseAsciiMap(text)

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

private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
