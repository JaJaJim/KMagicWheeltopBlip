@file:OptIn(ExperimentalStdlibApi::class)

package io.github.JaJaJim.kmagicwheeltopblip.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import io.github.JaJaJim.kmagicwheeltopblip.BuildConfig
import io.github.JaJaJim.kmagicwheeltopblip.data.RemotePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

enum class GattOwner {
    NONE,
    RIDE_DATAFIELD,
    APP_CONFIG
}

class WheeltopManager private constructor(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "WheeltopManager"

        val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_TX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val AUTH_PASS_FRAME = byteArrayOf(
            0xFE.toByte(), 0x32.toByte(), 0x29.toByte(), 0x11.toByte(),
            0x08.toByte(), 0x79.toByte(), 0x4F.toByte(), 0x54.toByte(),
            0x6D.toByte(), 0x4B.toByte(), 0x35.toByte(), 0x30.toByte(),
            0x7A.toByte(), 0x41.toByte(), 0x15.toByte()
        )

        @Volatile
        private var instance: WheeltopManager? = null

        fun getInstance(context: Context): WheeltopManager {
            return instance ?: synchronized(this) {
                instance ?: WheeltopManager(context).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val remotePreferences = RemotePreferences(appContext)
    private val remoteManager by lazy { MagicshineRemoteManager.getInstance(appContext) }

    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var activeGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var sessionKeyByte: Byte? = null
    private var initJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var blockWatchdogJob: Job? = null
    private var wakeUpPingJob: Job? = null
    private var pullRetryCount = 0

    @Volatile
    private var isLatched = false

    @Volatile
    var isShiftInProgress = false
        private set

    @Volatile
    private var pingPongStep = 0

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _activeGattOwner = MutableStateFlow(GattOwner.NONE)
    val activeGattOwner: StateFlow<GattOwner> = _activeGattOwner.asStateFlow()

    private val _isRideActive = MutableStateFlow(false)
    val isRideActive: StateFlow<Boolean> = _isRideActive.asStateFlow()

    private val _isScanningDerailleurs = MutableStateFlow(false)
    val isScanningDerailleurs: StateFlow<Boolean> = _isScanningDerailleurs.asStateFlow()

    private val _scannedDerailleurs = MutableStateFlow<List<BleDeviceItem>>(emptyList())
    val scannedDerailleurs: StateFlow<List<BleDeviceItem>> = _scannedDerailleurs.asStateFlow()

    private val _boundMacAddress = MutableStateFlow<String?>(null)
    val boundMacAddress: StateFlow<String?> = _boundMacAddress.asStateFlow()

    private val _boundDeviceName = MutableStateFlow<String?>(null)
    val boundDeviceName: StateFlow<String?> = _boundDeviceName.asStateFlow()

    private val _isPullingGattBlocks = MutableStateFlow(false)
    val isPullingGattBlocks: StateFlow<Boolean> = _isPullingGattBlocks.asStateFlow()

    private val _lastGattAsciiText = MutableStateFlow<String?>(null)
    val lastGattAsciiText: StateFlow<String?> = _lastGattAsciiText.asStateFlow()

    private val _lastGattTimestamp = MutableStateFlow<Long>(0L)
    val lastGattTimestamp: StateFlow<Long> = _lastGattTimestamp.asStateFlow()

    private val _isAuthenticatedState = MutableStateFlow(false)
    val isAuthenticatedState: StateFlow<Boolean> = _isAuthenticatedState.asStateFlow()

    private val _currentRssi = MutableStateFlow<Int?>(null)
    val currentRssi: StateFlow<Int?> = _currentRssi.asStateFlow()

    private val _blockStatusText = MutableStateFlow<String?>("Ready for Pull")
    val blockStatusText: StateFlow<String?> = _blockStatusText.asStateFlow()

    private val asciiBuffer = StringBuilder()

    init {
        Log.i(TAG, "WheeltopManager initialized. Checking build version migration...")

        scope.launch {
            remotePreferences.checkAndClearOnNewBuild("${BuildConfig.VERSION_NAME}_${BuildConfig.VERSION_CODE}")
        }

        scope.launch {
            remotePreferences.boundWheeltopMac.collectLatest { mac ->
                _boundMacAddress.value = mac
            }
        }

        scope.launch {
            remotePreferences.boundWheeltopName.collectLatest { name ->
                if (!name.isNullOrBlank()) {
                    _boundDeviceName.value = name
                }
            }
        }

        scope.launch {
            remotePreferences.lastGattAsciiText.collectLatest { text ->
                _lastGattAsciiText.value = text
            }
        }

        scope.launch {
            remotePreferences.lastGattTimestamp.collectLatest { timestamp ->
                _lastGattTimestamp.value = timestamp
            }
        }

        // Keep-Alive Ping Ticker when Active use as Blip AND Ride Active are enabled
        scope.launch {
            remotePreferences.activeUseAsBlip.collectLatest { active ->
                wakeUpPingJob?.cancel()
                if (active && _isRideActive.value) {
                    val boundMac = _boundMacAddress.value
                    if (!boundMac.isNullOrBlank() && _connectionState.value == ConnectionState.DISCONNECTED) {
                        connectToBoundDevice(boundMac)
                    } else if (_connectionState.value == ConnectionState.CONNECTED) {
                        start3SecondWakeUpPing()
                    }
                } else if (!active) {
                    if (!_isPullingGattBlocks.value && _connectionState.value == ConnectionState.CONNECTED) {
                        disconnectGatt()
                    }
                }
            }
        }
    }

    fun requestRideDatafieldGattAccess() {
        if (_activeGattOwner.value == GattOwner.APP_CONFIG) {
            Log.w(TAG, "===> [PRIORITY PREEMPTION] Ride Datafield requesting VIP GATT access. Releasing App Config GATT lock...")
            disconnectGatt()
        }
        _activeGattOwner.value = GattOwner.RIDE_DATAFIELD
    }

    fun requestAppConfigGattAccess() {
        if (_activeGattOwner.value == GattOwner.RIDE_DATAFIELD) {
            Log.i(TAG, "===> [PRIORITY YIELD] Ride Datafield active on screen. App Config deferring to Ride Datafield...")
            return
        }
        _activeGattOwner.value = GattOwner.APP_CONFIG
    }

    fun releaseAppConfigGattLock() {
        Log.i(TAG, "===> [AUTO-YIELD] App Config releasing GATT lock on pause/stop...")
        _activeGattOwner.value = GattOwner.NONE
        if (!_isRideActive.value && !_isPullingGattBlocks.value) {
            wakeUpPingJob?.cancel()
            wakeUpPingJob = null
            Log.i(TAG, "===> [HARD CANCEL] Cancelling 3s Keep-Alive Ping & disconnecting GATT on Config UI exit...")
            disconnectGatt()
        }
    }

    fun setIsRideActive(active: Boolean) {
        _isRideActive.value = active
        Log.i(TAG, "===> [RIDE LIFECYCLE] setIsRideActive: $active")
        if (active) {
            val boundMac = _boundMacAddress.value
            if (!boundMac.isNullOrBlank() && remoteManager.isActiveUseAsBlip.value) {
                if (_connectionState.value == ConnectionState.DISCONNECTED) {
                    connectToBoundDevice(boundMac)
                } else if (_connectionState.value == ConnectionState.CONNECTED) {
                    start3SecondWakeUpPing()
                }
            }
        } else {
            wakeUpPingJob?.cancel()
            wakeUpPingJob = null
            if (!_isPullingGattBlocks.value) {
                Log.i(TAG, "===> [RIDE ENDED] Ride active session finished. Hard cancelling 3s Keep-Alive Ping & releasing GATT socket...")
                disconnectGatt()
            }
        }
    }

    private fun start3SecondWakeUpPing() {
        wakeUpPingJob?.cancel()
        wakeUpPingJob = scope.launch(Dispatchers.IO) {
            while (
                _connectionState.value == ConnectionState.CONNECTED &&
                remoteManager.isActiveUseAsBlip.value &&
                _isRideActive.value
            ) {
                delay(3000)
                if (
                    _connectionState.value == ConnectionState.CONNECTED &&
                    !_isPullingGattBlocks.value &&
                    !isShiftInProgress
                ) {
                    Log.d(TAG, "===> [3s KEEP-ALIVE PING] Sending 1-byte 0xFA Pipe Opener to keep WheelTop MCU awake...")
                    sendAppProtocolCommand("3s Keep-Alive", 0xFA, byteArrayOf(0x80.toByte()))
                }
            }
        }
    }

    private var derailleurScanCallback: android.bluetooth.le.ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun startScanForWheeltopDevices() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        stopScanForWheeltopDevices()

        _isScanningDerailleurs.value = true
        val foundMap = mutableMapOf<String, BleDeviceItem>()

        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
                val device = result?.device ?: return
                val name = device.name ?: result.scanRecord?.deviceName ?: return
                val upper = name.uppercase()

                val isMasterDerailleur = (upper.contains("EDS TX") || upper.contains("EDS OX") || upper.contains("EDS GX") || upper.contains("EDS GEX"))
                        && !upper.contains("WHEELTOP") && !upper.contains("M0-C0") && !upper.contains("MJ_")

                if (isMasterDerailleur) {
                    foundMap[device.address] = BleDeviceItem(name = name, address = device.address, rssi = result.rssi)
                    _scannedDerailleurs.value = foundMap.values.toList()
                }
            }
        }
        derailleurScanCallback = callback

        scope.launch(Dispatchers.Main) {
            try {
                val settings = android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED)
                    .build()
                scanner.startScan(null, settings, callback)
                delay(30_000)
                stopScanForWheeltopDevices()
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning for WheelTop devices: ${e.message}")
                stopScanForWheeltopDevices()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanForWheeltopDevices() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        val callback = derailleurScanCallback
        if (scanner != null && callback != null) {
            try {
                scanner.stopScan(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping WheelTop scan: ${e.message}")
            }
        }
        derailleurScanCallback = null
        _isScanningDerailleurs.value = false
    }

    fun bindDevice(address: String, name: String) {
        stopScanForWheeltopDevices()
        _boundMacAddress.value = address
        _boundDeviceName.value = name
        _scannedDerailleurs.value = emptyList()
        scope.launch {
            Log.i(TAG, "Binding Wheeltop device MAC: $address Name: $name")
            remotePreferences.saveBoundWheeltop(address, name)
        }
    }

    fun unbindDevice() {
        scope.launch {
            Log.i(TAG, "Unbinding Wheeltop device")
            disconnectGatt()
            _boundMacAddress.value = null
            _boundDeviceName.value = null
            _scannedDerailleurs.value = emptyList()
            _lastGattAsciiText.value = null
            _lastGattTimestamp.value = 0L
            remotePreferences.clearBoundWheeltop()
        }
    }

    fun pullGattBlocks() {
        val mac = _boundMacAddress.value ?: return
        if (_isPullingGattBlocks.value && pullRetryCount > 0) {
            Log.i(TAG, "pullGattBlocks() already in progress (Attempt $pullRetryCount/5). Skipping duplicate trigger.")
            return
        }

        stopScanForWheeltopDevices()
        _isPullingGattBlocks.value = true
        pullRetryCount = 0
        executePullAttempt(mac)
    }

    private fun executePullAttempt(mac: String) {
        pullRetryCount++
        if (pullRetryCount > 5) {
            Log.e(TAG, "===> [PULL GATT BLOCKS FAILED] Exceeded 5 attempts. Marking as Connection Failed.")
            _isPullingGattBlocks.value = false
            _blockStatusText.value = "Connection Failed"
            disconnectGatt()
            return
        }

        Log.i(TAG, "===> [PULL GATT BLOCKS ATTEMPT $pullRetryCount/5] MAC: $mac")
        _blockStatusText.value = "Syncing ($pullRetryCount/5)..."
        asciiBuffer.clear()
        pingPongStep = 0

        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch(Dispatchers.IO) {
            delay(8_000) // 8-second hard safety timeout
            if (_isPullingGattBlocks.value && pingPongStep < 6) {
                Log.w(TAG, "[PULL ATTEMPT $pullRetryCount/5 TIMED OUT] Performing 3.5s modem cooldown & retrying...")
                val currentMac = mac
                disconnectGatt()

                scope.launch(Dispatchers.IO) {
                    delay(3500) // 3.5s Qualcomm modem quiet cooldown
                    if (_isPullingGattBlocks.value) {
                        if (pullRetryCount < 5) {
                            executePullAttempt(currentMac)
                        } else {
                            Log.e(TAG, "===> [PULL GATT BLOCKS FAILED] All 5 attempts exhausted.")
                            _isPullingGattBlocks.value = false
                            _blockStatusText.value = "Connection Failed"
                        }
                    }
                }
            }
        }

        if (activeGatt != null && _connectionState.value == ConnectionState.CONNECTED) {
            Log.i(TAG, "GATT already CONNECTED. Triggering fast block readout directly...")
            startInitializationSequence()
        } else {
            connectToBoundDevice(mac)
        }
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    fun connectToBoundDevice(macAddress: String) {
        _boundMacAddress.value = macAddress
        asciiBuffer.clear()
        pingPongStep = 0

        if (activeGatt != null || _connectionState.value == ConnectionState.CONNECTING || _connectionState.value == ConnectionState.CONNECTED) {
            Log.i(TAG, "[RULE 1 GUARD] GATT Connection active or connecting. Re-triggering initialization if needed...")
            if (activeGatt != null && _connectionState.value == ConnectionState.CONNECTED) {
                startInitializationSequence()
            }
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "connectToBoundDevice failed: Bluetooth adapter disabled")
            _connectionState.value = ConnectionState.DISCONNECTED
            _isPullingGattBlocks.value = false
            return
        }

        _connectionState.value = ConnectionState.CONNECTING

        scope.launch(Dispatchers.Main) {
            try {
                remoteManager.pauseScan()
                Log.i(TAG, "[RULE 1] Instant Direct GATT Connect (autoConnect=false) to MAC: $macAddress ...")
                val device = bluetoothAdapter?.getRemoteDevice(macAddress) ?: return@launch

                activeGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: Exception) {
                Log.e(TAG, "GATT connection error to $macAddress: ${e.message}")
                _connectionState.value = ConnectionState.DISCONNECTED
                _isPullingGattBlocks.value = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectGatt() {
        Log.i(TAG, "[RULE 4] disconnectGatt() invoked. Force cancelling jobs, closing GATT socket, and resetting state...")
        blockWatchdogJob?.cancel()
        blockWatchdogJob = null
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        initJob?.cancel()
        initJob = null
        wakeUpPingJob?.cancel()
        wakeUpPingJob = null
        isLatched = false

        try {
            activeGatt?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error calling activeGatt.disconnect(): ${e.message}")
        }

        try {
            activeGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error calling activeGatt.close(): ${e.message}")
        }

        activeGatt = null
        txCharacteristic = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _isAuthenticatedState.value = false
        sessionKeyByte = null
        _currentRssi.value = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange: status=$status, newState=$newState")

            if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                Log.w(TAG, "onConnectionStateChange status=$status, newState=$newState. Disconnecting...")
                disconnectGatt()
                _isPullingGattBlocks.value = false
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                activeGatt = gatt
                _connectionState.value = ConnectionState.CONNECTED
                Log.i(TAG, "GATT Physical Link Connected! Requesting CONNECTION_PRIORITY_HIGH (15ms interval) & Starting Service Discovery in 400ms...")

                try {
                    gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                } catch (e: Exception) {
                    Log.e(TAG, "Error requesting CONNECTION_PRIORITY_HIGH: ${e.message}")
                }

                scope.launch(Dispatchers.IO) {
                    delay(400)
                    try {
                        gatt?.discoverServices()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in discoverServices: ${e.message}")
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            Log.i(TAG, "onServicesDiscovered: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val service = gatt.getService(NUS_SERVICE_UUID)
                if (service != null) {
                    val rxChar = service.getCharacteristic(NUS_RX_UUID)
                    txCharacteristic = service.getCharacteristic(NUS_TX_UUID)
                    if (rxChar != null && txCharacteristic != null) {
                        Log.i(TAG, "Nordic UART Service & Characteristics found. Enabling RX notifications...")
                        enableNotification(gatt, rxChar)
                    } else {
                        Log.e(TAG, "Nordic UART Characteristics NOT found!")
                    }
                } else {
                    Log.e(TAG, "Nordic UART Service (6e400001) NOT found!")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            val isOk = (status == BluetoothGatt.GATT_SUCCESS)
            Log.i(TAG, "CCCD Descriptor Write Status: $status (OK=$isOk). Triggering Auth Handshake in 400ms...")
            if (isOk) {
                _connectionState.value = ConnectionState.CONNECTED
                _isAuthenticatedState.value = true
                scope.launch(Dispatchers.IO) {
                    delay(400)
                    sendRawFrame("Auth Handshake", AUTH_PASS_FRAME)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleRxNotification(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value ?: return
            handleRxNotification(bytes)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startInitializationSequence() {
        initJob?.cancel()
        pingPongStep = 1
        _blockStatusText.value = "Authenticating..."
        initJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "[FIREWORK FLOW] Step 1: Sending 1-byte 0xFA Pipe Opener (0x80)...")
            sendAppProtocolCommand("0xFA (Pipe Opener)", 0xFA, byteArrayOf(0x80.toByte()))
            delay(350)

            pingPongStep = 2
            _blockStatusText.value = "Reading Block 0 (0/5)..."
            Log.i(TAG, "[FIREWORK FLOW] Step 2: Sending read block 0 (0xFB, 00 00 51)...")
            sendAppProtocolCommand("read block 0 (0xFB)", 0xFB, byteArrayOf(0x00, 0x00, 0x51.toByte()))
        }
    }

    private fun handleRxNotification(data: ByteArray) {
        if (data.isEmpty()) return

        // Read plain US-ASCII text fragment directly for 132-byte block notifications (data.size >= 100)
        if (data.size >= 100) {
            try {
                val asciiFragment = String(data, 4, data.size - 4, Charsets.US_ASCII)
                val cleanFragment = asciiFragment.filter { it.code in 32..126 || it == '\n' || it == '\r' || it == ',' || it == ';' }
                asciiBuffer.append(cleanFragment)
                Log.i(TAG, "===> [SPEICHER-STAND (${asciiBuffer.length} BYTES)]: \"${asciiBuffer}\"")
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing ASCII fragment: ${e.message}")
            }
        }

        if (data.size < 5) return

        val rawXorByte = data[1].toInt() and 0xFF
        val xorKey = (rawXorByte - 0x32) and 0xFF

        val decoded = ByteArray(data.size)
        decoded[0] = data[0]
        decoded[1] = data[1]
        for (i in 2 until data.size) {
            decoded[i] = (data[i].toInt() xor xorKey).toByte()
        }

        val cmd = decoded[3].toInt() and 0xFF

        if (cmd == 0x11 && data.size >= 5) {
            val rawKey = ((data[1].toInt() and 0xFF) - 0x32) and 0xFF
            val cleanKey = (if (rawKey == 0) 0x14 else (rawKey and 0x7F)).toByte()
            sessionKeyByte = cleanKey
            _isAuthenticatedState.value = true
            _blockStatusText.value = "Session Active (Key 0x%02X)".format(cleanKey)
            Log.i(TAG, "VALID SESSION KEY ACTIVATED: 0x%02X. Triggering FIREWORK Flow...".format(cleanKey))
            startInitializationSequence()
        } else if (data.size >= 100) {
            if (pingPongStep == 2) {
                pingPongStep = 3
                Log.i(TAG, "===> [BLOCK 0 RECEIVED (132 BYTES)!] Buffer 200ms -> Sending read block 1 (0xFB, 00 01 51)...")
                scope.launch(Dispatchers.IO) {
                    delay(200)
                    sendAppProtocolCommand("read block 1 (0xFB)", 0xFB, byteArrayOf(0x00, 0x01, 0x51.toByte()))
                }
            } else if (pingPongStep == 3) {
                pingPongStep = 4
                Log.i(TAG, "===> [BLOCK 1 RECEIVED (132 BYTES)!] Buffer 200ms -> Sending read block 2 (0xFB, 00 02 51)...")
                scope.launch(Dispatchers.IO) {
                    delay(200)
                    sendAppProtocolCommand("read block 2 (0xFB)", 0xFB, byteArrayOf(0x00, 0x02, 0x51.toByte()))
                }
            } else if (pingPongStep == 4) {
                pingPongStep = 5
                Log.i(TAG, "===> [BLOCK 2 RECEIVED (132 BYTES)!] Buffer 200ms -> Sending read block 3 (0xFB, 00 03 51)...")
                scope.launch(Dispatchers.IO) {
                    delay(200)
                    sendAppProtocolCommand("read block 3 (0xFB)", 0xFB, byteArrayOf(0x00, 0x03, 0x51.toByte()))
                }
            } else if (pingPongStep == 5) {
                pingPongStep = 6
                Log.i(TAG, "===> [BLOCK 3 RECEIVED (132 BYTES)!] Buffer 200ms -> Sending read block 4 (0xFB, 00 04 51)...")
                scope.launch(Dispatchers.IO) {
                    delay(200)
                    sendAppProtocolCommand("read block 4 (0xFB)", 0xFB, byteArrayOf(0x00, 0x04, 0x51.toByte()))
                }
            } else if (pingPongStep == 6) {
                connectTimeoutJob?.cancel()
                connectTimeoutJob = null
                _blockStatusText.value = "Ready"
                Log.i(TAG, "===> [BLOCK 4 RECEIVED (132 BYTES)!] ALL BLOCKS 0..4 FULLY UNLOCKED!")

                val fullAscii = asciiBuffer.toString()
                val currentTimestamp = System.currentTimeMillis()
                _lastGattAsciiText.value = fullAscii
                _lastGattTimestamp.value = currentTimestamp
                _isPullingGattBlocks.value = false

                scope.launch {
                    remotePreferences.saveGattBlockDataset(fullAscii, currentTimestamp)
                }

                if (!remoteManager.isActiveUseAsBlip.value || !_isRideActive.value) {
                    Log.i(TAG, "Active use as Blip or Ride Active is INACTIVE. Disconnecting GATT socket after successful block pull...")
                    disconnectGatt()
                } else {
                    Log.i(TAG, "Active use as Blip AND Ride Active are TRUE. Keeping GATT open & starting 3s Keep-Alive Ping...")
                    start3SecondWakeUpPing()
                }

                remoteManager.resumeScan()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                val valueBytes = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                @Suppress("DEPRECATION")
                descriptor.value = valueBytes

                @Suppress("DEPRECATION")
                val writeOk = gatt.writeDescriptor(descriptor)
                Log.i(TAG, "[ANDROID 12 NATIVE] gatt.writeDescriptor(CCCD) initiated: $writeOk")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling notifications: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendRawFrame(actionName: String, bytes: ByteArray): Boolean {
        val gatt = activeGatt
        val tx = txCharacteristic

        if (gatt == null || tx == null) {
            Log.e(TAG, "Cannot send $actionName: GATT/TX is null")
            return false
        }

        try {
            val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

            @Suppress("DEPRECATION")
            tx.value = bytes
            @Suppress("DEPRECATION")
            tx.writeType = writeType

            Log.i(TAG, "[ANDROID 12 NATIVE TX] [$actionName] -> Hex: ${bytes.toHexString()}")

            @Suppress("DEPRECATION")
            val writeInitiated = gatt.writeCharacteristic(tx)

            if (!writeInitiated) {
                Log.e(TAG, "gatt.writeCharacteristic returned false for $actionName")
                return false
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending $actionName: ${e.message}")
            return false
        }
    }

    private fun buildAppFrame(commandId: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val key = sessionKeyByte ?: 0x29.toByte()
        val rawXorByte = 0x32.toByte()

        val frameWithoutCrc = ByteArray(5 + payload.size)
        frameWithoutCrc[0] = 0xFE.toByte()
        frameWithoutCrc[1] = rawXorByte
        frameWithoutCrc[2] = key
        frameWithoutCrc[3] = commandId.toByte()
        frameWithoutCrc[4] = payload.size.toByte()
        for (i in payload.indices) {
            frameWithoutCrc[5 + i] = payload[i]
        }

        val crc = calculateCrc16(frameWithoutCrc)
        val crcHigh = ((crc shr 8) and 0xFF).toByte()
        val crcLow = (crc and 0xFF).toByte()

        val fullFrame = ByteArray(frameWithoutCrc.size + 2)
        System.arraycopy(frameWithoutCrc, 0, fullFrame, 0, frameWithoutCrc.size)
        fullFrame[fullFrame.size - 2] = crcHigh
        fullFrame[fullFrame.size - 1] = crcLow

        return fullFrame
    }

    private fun calculateCrc16(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (b in bytes) {
            crc = crc xor (b.toInt() and 0xFF)
            for (i in 0 until 8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    suspend fun sendAppProtocolCommand(name: String, commandId: Int, payload: ByteArray = byteArrayOf()): Boolean {
        val frame = buildAppFrame(commandId, payload)
        return sendRawFrame(name, frame)
    }

    fun shiftRear(direction: Int) {
        if (activeGatt == null || _connectionState.value != ConnectionState.CONNECTED || !_isAuthenticatedState.value || _isPullingGattBlocks.value) {
            Log.w(TAG, "[SAFETY DISCARD] GATT not connected or handshake in progress. Discarding shiftRear($direction) for rider safety.")
            return
        }
        scope.launch(Dispatchers.IO) {
            val payload = byteArrayOf(direction.toByte())
            sendAppProtocolCommand("shiftRear($direction)", 0x99, payload)
        }
    }

    fun toggleFrontDerailleur() {
        if (activeGatt == null || _connectionState.value != ConnectionState.CONNECTED || !_isAuthenticatedState.value || _isPullingGattBlocks.value) {
            Log.w(TAG, "[SAFETY DISCARD] GATT not connected or handshake in progress. Discarding toggleFrontDerailleur for rider safety.")
            return
        }
        scope.launch(Dispatchers.IO) {
            val payload = byteArrayOf(0x02.toByte())
            sendAppProtocolCommand("toggleFrontDerailleur", 0x89, payload)
        }
    }
}
