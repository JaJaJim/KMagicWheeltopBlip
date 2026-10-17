package io.github.JaJaJim.kmagicwheeltopblip.ble

data class BleDeviceItem(
    val name: String,
    val address: String,
    val rssi: Int
)

data class DiagnosticLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val isError: Boolean = false,
    val isSuccess: Boolean = false
)
