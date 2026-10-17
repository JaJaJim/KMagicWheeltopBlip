package io.github.JaJaJim.karooedscharge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.JaJaJim.karooedscharge.ble.WheeltopManager

class AdbTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val wheeltopManager = WheeltopManager.getInstance(context)
        val mac = intent.getStringExtra("mac") ?: "D4:85:A1:BE:24:3B"

        Log.i("AdbTestReceiver", "Received ADB Broadcast Action: $action (MAC: $mac)")

        when (action) {
            "io.github.JaJaJim.karooedscharge.CONNECT" -> {
                wheeltopManager.bindDevice(mac, "Wheeltop EDS TX")
                wheeltopManager.toggleMonitoring(true)
            }
            "io.github.JaJaJim.karooedscharge.DISCONNECT" -> {
                wheeltopManager.toggleMonitoring(false)
            }
            "io.github.JaJaJim.karooedscharge.RD_SHIFT_UP" -> {
                wheeltopManager.shiftRear(2)
            }
            "io.github.JaJaJim.karooedscharge.RD_SHIFT_DOWN" -> {
                wheeltopManager.shiftRear(1)
            }
            "io.github.JaJaJim.karooedscharge.FD_TOGGLE" -> {
                wheeltopManager.toggleFrontDerailleur()
            }
        }
    }
}
