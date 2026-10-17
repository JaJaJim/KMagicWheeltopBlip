package io.github.JaJaJim.kmagicwheeltopblip.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import io.github.JaJaJim.kmagicwheeltopblip.data.RemotePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MagicshineRemoteManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "MagicshineRemoteManager"

        @Volatile
        private var instance: MagicshineRemoteManager? = null

        fun getInstance(context: Context): MagicshineRemoteManager {
            return instance ?: synchronized(this) {
                instance ?: MagicshineRemoteManager(context).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val remotePreferences = RemotePreferences(appContext)
    private val wheeltopManager = WheeltopManager.getInstance(appContext)

    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var isScanningInternal = false

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _boundRemoteMac = MutableStateFlow<String?>(null)
    val boundRemoteMac: StateFlow<String?> = _boundRemoteMac.asStateFlow()

    private val _boundRemoteName = MutableStateFlow<String?>(null)
    val boundRemoteName: StateFlow<String?> = _boundRemoteName.asStateFlow()

    private val _scannedRemotes = MutableStateFlow<List<BleDeviceItem>>(emptyList())
    val scannedRemotes: StateFlow<List<BleDeviceItem>> = _scannedRemotes.asStateFlow()

    private val _lastClickText = MutableStateFlow<String?>("No clicks registered yet")
    val lastClickText: StateFlow<String?> = _lastClickText.asStateFlow()

    private val _doubleClickMode = MutableStateFlow("FD_TOGGLE")
    val doubleClickMode: StateFlow<String> = _doubleClickMode.asStateFlow()

    private val foundRemotesMap = mutableMapOf<String, BleDeviceItem>()
    private var lastSequenceToken = -1
    private var lastButtonCode = -1

    init {
        Log.i(TAG, "MagicshineRemoteManager initialized with Token-Based KMagicTopBlips engine.")

        scope.launch {
            remotePreferences.boundRemoteMac.collect { mac ->
                _boundRemoteMac.value = mac
                if (!mac.isNullOrBlank()) {
                    startLowLatencyScan()
                } else {
                    stopScan()
                }
            }
        }

        scope.launch {
            remotePreferences.boundRemoteName.collect { name ->
                _boundRemoteName.value = name
            }
        }

        scope.launch {
            remotePreferences.doubleClickMode.collect { mode ->
                _doubleClickMode.value = mode
            }
        }
    }

    fun setDoubleClickMode(mode: String) {
        _doubleClickMode.value = mode
        scope.launch {
            remotePreferences.saveDoubleClickMode(mode)
        }
    }

    fun bindRemote(address: String, name: String) {
        _boundRemoteMac.value = address
        _boundRemoteName.value = name
        Log.i(TAG, "Binding Magicshine M0-C0 Remote MAC: $address Name: $name")
        scope.launch {
            remotePreferences.saveBoundRemote(address, name)
            startLowLatencyScan()
        }
    }

    fun unbindRemote() {
        scope.launch {
            Log.i(TAG, "Unbinding Magicshine Remote")
            stopScan()
            remotePreferences.clearBoundRemote()
            _boundRemoteMac.value = null
            _boundRemoteName.value = null
            _connectionState.value = ConnectionState.DISCONNECTED
            _lastClickText.value = "Remote Unbound / Reset"
            lastSequenceToken = -1
            lastButtonCode = -1
        }
    }

    fun startScanForRemotes() {
        startLowLatencyScan()
    }

    @SuppressLint("MissingPermission")
    private fun startLowLatencyScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        try {
            scanner.stopScan(scanCallback)
        } catch (_: Exception) {}

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Instant 15ms ScanRecord keypress capture
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
            isScanningInternal = true
            _connectionState.value = ConnectionState.CONNECTED
            Log.i(TAG, "Magicshine BLE Low Latency ScanRecord engine ACTIVE.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan: ${e.message}")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanningInternal) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        isScanningInternal = false
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device ?: return
            val address = device.address ?: return
            val realName = device.name ?: result.scanRecord?.deviceName ?: return
            val boundMac = _boundRemoteMac.value

            // 1. Bound Device Check: If packet is from our bound M0-C0 remote
            if (!boundMac.isNullOrBlank() && address.equals(boundMac, ignoreCase = true)) {
                _connectionState.value = ConnectionState.CONNECTED
                val bytes = extractSDataBytes(result)
                if (bytes.isNotEmpty()) {
                    handleSDataReceived(bytes)
                }
                return
            }

            // 2. Unbound Scanning Mode: Strictly filter M0-C0 / MJ_6558 ONLY
            val upper = realName.uppercase()
            val isStrictMj6558 = upper.contains("M0-C0") || upper.contains("MJ_6558") || upper.contains("MJ-6558") || upper.contains("MJ6558")

            if (isStrictMj6558) {
                val item = BleDeviceItem(name = realName, address = address, rssi = result.rssi)
                foundRemotesMap[address] = item
                _scannedRemotes.value = foundRemotesMap.values.toList()
            }
        }
    }

    private fun extractSDataBytes(result: ScanResult): ByteArray {
        val record = result.scanRecord ?: return byteArrayOf()
        val serviceData = record.serviceData ?: return byteArrayOf()

        if (serviceData.isNotEmpty()) {
            val ffe1Entry = serviceData.entries.firstOrNull { it.key.toString().contains("ffe1", ignoreCase = true) }
                ?: serviceData.entries.firstOrNull()
            if (ffe1Entry != null) {
                return ffe1Entry.value
            }
        }

        val mfgData = record.manufacturerSpecificData
        if (mfgData != null && mfgData.size() > 0) {
            val key = mfgData.keyAt(0)
            val bytes = mfgData.get(key)
            if (bytes != null && bytes.isNotEmpty()) {
                return bytes
            }
        }

        return record.bytes ?: byteArrayOf()
    }

    private fun handleSDataReceived(data: ByteArray) {
        if (data.size < 4) return

        val hex = data.joinToString("") { "%02x".format(it) }
        val seqToken = (data.getOrNull(3)?.toInt() ?: 0) and 0xFF
        val lastByte = (data.lastOrNull()?.toInt() ?: 0) and 0xFF

        val has11 = data.any { (it.toInt() and 0xFF) == 0x11 } || lastByte == 0x11
        val has12 = data.any { (it.toInt() and 0xFF) == 0x12 } || lastByte == 0x12
        val has21 = data.any { (it.toInt() and 0xFF) == 0x21 } || lastByte == 0x21
        val has22 = data.any { (it.toInt() and 0xFF) == 0x22 } || lastByte == 0x22

        if (!has11 && !has12 && !has21 && !has22) return

        // Token-Based Burst Filtering: Ignore duplicates with identical token and button code
        if (seqToken == lastSequenceToken && lastByte == lastButtonCode) {
            Log.d(TAG, "Discarding burst packet with duplicate token: 0x%02X".format(seqToken))
            return
        }

        lastSequenceToken = seqToken
        lastButtonCode = lastByte

        Log.i(TAG, "===> [M0-C0 BLIP NEW TOKEN EVENT] Token=0x%02X Hex=$hex (11=$has11, 12=$has12, 21=$has21, 22=$has22)".format(seqToken))

        when {
            has12 || has22 -> {
                val mode = _doubleClickMode.value
                val isSquare = has12
                val buttonName = if (isSquare) "Square [□]" else "Circle [○]"

                when (mode) {
                    "DISABLED" -> {
                        val msg = "DOUBLE CLICK ($buttonName) -> Disabled (Ignored)"
                        _lastClickText.value = msg
                        Log.i(TAG, "===> [BLIP EVENT] $msg")
                    }
                    "BURST_SHIFT" -> {
                        val directionName = if (isSquare) "+2 Gears Up" else "-2 Gears Down"
                        val msg = "DOUBLE CLICK ($buttonName) -> Burst Shift $directionName"
                        _lastClickText.value = msg
                        Log.i(TAG, "===> [BLIP EVENT] $msg")
                        scope.launch(Dispatchers.IO) {
                            val dir = if (isSquare) 2 else 1
                            wheeltopManager.shiftRear(dir)
                            delay(250)
                            wheeltopManager.shiftRear(dir)
                        }
                    }
                    else -> {
                        // "FD_TOGGLE" (Default)
                        val msg = "DOUBLE CLICK ($buttonName) -> FD Chainring Toggle"
                        _lastClickText.value = msg
                        Log.i(TAG, "===> [BLIP EVENT] $msg")
                        wheeltopManager.toggleFrontDerailleur()
                    }
                }
            }
            has11 -> {
                val msg = "Square [□] Single Click -> RD Shift Up"
                _lastClickText.value = msg
                Log.i(TAG, "===> [BLIP EVENT] $msg")
                wheeltopManager.shiftRear(2)
            }
            has21 -> {
                val msg = "Circle [○] Single Click -> RD Shift Down"
                _lastClickText.value = msg
                Log.i(TAG, "===> [BLIP EVENT] $msg")
                wheeltopManager.shiftRear(1)
            }
        }
    }
}
