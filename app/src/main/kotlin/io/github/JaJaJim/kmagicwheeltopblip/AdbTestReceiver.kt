package io.github.JaJaJim.kmagicwheeltopblip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.JaJaJim.kmagicwheeltopblip.ble.MagicshineRemoteManager
import io.github.JaJaJim.kmagicwheeltopblip.ble.WheeltopManager

class AdbTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val wheeltopManager = WheeltopManager.getInstance(context)
        val remoteManager = MagicshineRemoteManager.getInstance(context)
        val mac = intent.getStringExtra("mac") ?: "D4:85:A1:BE:24:3B"

        Log.i("AdbTestReceiver", "Received ADB Broadcast Action: $action (MAC: $mac)")

        when (action) {
            "io.github.JaJaJim.kmagicwheeltopblip.CONNECT" -> {
                wheeltopManager.bindDevice(mac, "EDS TX")
                wheeltopManager.toggleMonitoring(true)
            }
            "io.github.JaJaJim.kmagicwheeltopblip.DISCONNECT" -> {
                wheeltopManager.toggleMonitoring(false)
            }
            "io.github.JaJaJim.kmagicwheeltopblip.BIND_REMOTE" -> {
                val remoteMac = intent.getStringExtra("mac")
                val remoteName = intent.getStringExtra("name") ?: "M0-C0 MJ_6558"
                if (!remoteMac.isNullOrBlank()) {
                    Log.i("AdbTestReceiver", "===> [ADB BIND REMOTE] Binding Magicshine Remote MAC: $remoteMac Name: $remoteName")
                    remoteManager.bindRemote(remoteMac, remoteName)
                } else {
                    Log.w("AdbTestReceiver", "[ADB BIND REMOTE] Missing required 'mac' extra parameter.")
                }
            }
            "io.github.JaJaJim.kmagicwheeltopblip.UNBIND_REMOTE" -> {
                Log.i("AdbTestReceiver", "===> [ADB UNBIND REMOTE] Resetting Magicshine Remote binding configuration...")
                remoteManager.unbindRemote()
            }
            "io.github.JaJaJim.kmagicwheeltopblip.RD_SHIFT_UP" -> {
                wheeltopManager.shiftRear(2)
            }
            "io.github.JaJaJim.kmagicwheeltopblip.RD_SHIFT_DOWN" -> {
                wheeltopManager.shiftRear(1)
            }
            "io.github.JaJaJim.kmagicwheeltopblip.FD_TOGGLE" -> {
                wheeltopManager.toggleFrontDerailleur()
            }
            "io.github.JaJaJim.kmagicwheeltopblip.REMOTE_SQUARE" -> {
                Log.i("AdbTestReceiver", "===> [ADB BLIP TEST] Single Click Square [□] -> RD_SHIFT_UP")
                wheeltopManager.shiftRear(2)
            }
            "io.github.JaJaJim.kmagicwheeltopblip.REMOTE_CIRCLE" -> {
                Log.i("AdbTestReceiver", "===> [ADB BLIP TEST] Single Click Circle [○] -> RD_SHIFT_DOWN")
                wheeltopManager.shiftRear(1)
            }
            "io.github.JaJaJim.kmagicwheeltopblip.REMOTE_DOUBLE_CLICK" -> {
                Log.i("AdbTestReceiver", "===> [ADB BLIP TEST] Double Click [□/○] -> FD_TOGGLE")
                wheeltopManager.toggleFrontDerailleur()
            }
        }
    }
}
