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
import android.os.Build
import android.util.Log
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

    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var activeGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var sessionKeyByte: Byte? = null
    private var initJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var blockWatchdogJob: Job? = null

    @Volatile
    private var isLatched = false

    @Volatile
    var isShiftInProgress = false
        private set

    @Volatile
    private var pingPongStep = 0

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BleDeviceItem>>(emptyList())
    val scannedDevices: StateFlow<List<BleDeviceItem>> = _scannedDevices.asStateFlow()

    private val _boundMacAddress = MutableStateFlow<String?>(null)
    val boundMacAddress: StateFlow<String?> = _boundMacAddress.asStateFlow()

    private val _boundDeviceName = MutableStateFlow<String?>(null)
    val boundDeviceName: StateFlow<String?> = _boundDeviceName.asStateFlow()

    private val _isMonitored = MutableStateFlow(false)
    val isMonitored: StateFlow<Boolean> = _isMonitored.asStateFlow()

    // GATT Master Voltage StateFlows
    private val _gattRearVolts = MutableStateFlow<Float?>(null)
    val gattRearVolts: StateFlow<Float?> = _gattRearVolts.asStateFlow()

    private val _gattFrontVolts = MutableStateFlow<Float?>(null)
    val gattFrontVolts: StateFlow<Float?> = _gattFrontVolts.asStateFlow()

    private val _gattShifterRightVolts = MutableStateFlow<Float?>(null)
    val gattShifterRightVolts: StateFlow<Float?> = _gattShifterRightVolts.asStateFlow()

    private val _gattShifterLeftVolts = MutableStateFlow<Float?>(null)
    val gattShifterLeftVolts: StateFlow<Float?> = _gattShifterLeftVolts.asStateFlow()

    // GATT Master Firmware StateFlows
    private val _gattRearFirmware = MutableStateFlow<String?>(null)
    val gattRearFirmware: StateFlow<String?> = _gattRearFirmware.asStateFlow()

    private val _gattFrontFirmware = MutableStateFlow<String?>(null)
    val gattFrontFirmware: StateFlow<String?> = _gattFrontFirmware.asStateFlow()

    private val _gattShifterRightFirmware = MutableStateFlow<String?>(null)
    val gattShifterRightFirmware: StateFlow<String?> = _gattShifterRightFirmware.asStateFlow()

    private val _gattShifterLeftFirmware = MutableStateFlow<String?>(null)
    val gattShifterLeftFirmware: StateFlow<String?> = _gattShifterLeftFirmware.asStateFlow()

    private val _currentRssi = MutableStateFlow<Int?>(null)
    val currentRssi: StateFlow<Int?> = _currentRssi.asStateFlow()

    private val _isAuthenticatedState = MutableStateFlow(false)
    val isAuthenticatedState: StateFlow<Boolean> = _isAuthenticatedState.asStateFlow()

    // Block 0..4 Telemetry StateFlows
    private val _blockParameters = MutableStateFlow<Map<String, String>>(emptyMap())
    val blockParameters: StateFlow<Map<String, String>> = _blockParameters.asStateFlow()

    private val _rawBlockText = MutableStateFlow<String>("")
    val rawBlockText: StateFlow<String> = _rawBlockText.asStateFlow()

    private val _blockStatusText = MutableStateFlow<String?>("Block 0..4 Locked")
    val blockStatusText: StateFlow<String?> = _blockStatusText.asStateFlow()

    private val asciiBuffer = StringBuilder()

    init {
        Log.i(TAG, "WheeltopManager initialized.")

        // Rule 1: Preference observers ONLY update state, NEVER call connectToBoundDevice concurrently!
        scope.launch {
            remotePreferences.boundWheeltopMac.collectLatest { mac ->
                val activeMac = if (mac.isNullOrBlank()) "D4:85:A1:BE:24:3B" else mac
                _boundMacAddress.value = activeMac
                if (_boundDeviceName.value.isNullOrBlank()) {
                    _boundDeviceName.value = "EDS TX"
                }
            }
        }

        scope.launch {
            remotePreferences.boundWheeltopName.collectLatest { name ->
                _boundDeviceName.value = name
            }
        }

        scope.launch {
            remotePreferences.isMonitored.collectLatest { monitored ->
                _isMonitored.value = monitored
            }
        }
    }

    fun toggleMonitoring(enabled: Boolean) {
        scope.launch {
            _isMonitored.value = enabled
            remotePreferences.saveIsMonitored(enabled)
            if (enabled) {
                isLatched = false
                val mac = _boundMacAddress.value ?: "D4:85:A1:BE:24:3B"
                Log.i(TAG, "[RULE 1] toggleMonitoring(true) -> Initiating SINGLE GATT connection to $mac")
                connectToBoundDevice(mac)
            } else {
                isLatched = false
                Log.i(TAG, "[RULE 4] toggleMonitoring(false) -> Executing 4ms Disconnect Release")
                disconnectGatt()
                _connectionState.value = ConnectionState.DISCONNECTED
                _gattRearVolts.value = null
                _gattFrontVolts.value = null
                _gattShifterRightVolts.value = null
                _gattShifterLeftVolts.value = null
            }
        }
    }

    fun bindDevice(address: String, name: String) {
        _boundMacAddress.value = address
        _boundDeviceName.value = name
        scope.launch {
            Log.i(TAG, "Binding Wheeltop device MAC: $address Name: $name")
            remotePreferences.saveBoundWheeltop(address, name)
        }
    }

    fun unbindDevice() {
        scope.launch {
            Log.i(TAG, "Unbinding Wheeltop device")
            disconnectGatt()
            _isMonitored.value = false
            remotePreferences.clearBoundWheeltop()
        }
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    fun connectToBoundDevice(macAddress: String) {
        _isMonitored.value = true
        _boundMacAddress.value = macAddress

        // Rule 1: Single Client-ID Guard (prevents conn_id 5 vs conn_id 6 collisions in Qualcomm driver)
        if (activeGatt != null || _connectionState.value == ConnectionState.CONNECTING || _connectionState.value == ConnectionState.CONNECTED) {
            Log.i(TAG, "[RULE 1 GUARD] GATT Connection active or connecting. Ignoring duplicate connectToBoundDevice request.")
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "connectToBoundDevice failed: Bluetooth adapter disabled")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        _connectionState.value = ConnectionState.CONNECTING

        blockWatchdogJob?.cancel()
        blockWatchdogJob = scope.launch(Dispatchers.IO) {
            delay(5000)
            if (_isMonitored.value && _blockStatusText.value != "Blocks 0..4 Unlocked (5/5 Complete)") {
                Log.w(TAG, "===> [WATCHDOG 5s TIMEOUT] Block readout incomplete. Executing Auto-Reconnect...")
                _blockStatusText.value = "Auto-Reconnecting..."
                disconnectGatt()
                delay(1500)
                val boundMac = _boundMacAddress.value
                if (_isMonitored.value && !boundMac.isNullOrBlank()) {
                    connectToBoundDevice(boundMac)
                }
            }
        }

        scope.launch(Dispatchers.Main) {
            try {
                Log.i(TAG, "[RULE 1] Connecting DIRECT SINGLE GATT Client to MAC: $macAddress ...")
                val device = bluetoothAdapter?.getRemoteDevice(macAddress) ?: return@launch

                activeGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

                connectTimeoutJob?.cancel()
                connectTimeoutJob = scope.launch(Dispatchers.IO) {
                    delay(12_000)
                    if (_connectionState.value == ConnectionState.CONNECTING) {
                        Log.e(TAG, "[CONNECT TIMEOUT] GATT connection to $macAddress timed out after 12s. Forcing disconnect & cleanup...")
                        disconnectGatt()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "GATT connection error to $macAddress: ${e.message}")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanForWheeltopDevices() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        val foundList = mutableListOf<BleDeviceItem>()
        val scanCallback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
                val device = result?.device ?: return
                val name = device.name ?: result.scanRecord?.deviceName ?: return
                val upper = name.uppercase()

                val isMasterDerailleur = (upper.contains("EDS TX") || upper.contains("EDS OX") || upper.contains("EDS GX") || upper.contains("EDS GEX"))
                        && !upper.contains("WHEELTOP") && !upper.contains("M0-C0") && !upper.contains("MJ_")

                if (isMasterDerailleur) {
                    if (foundList.none { it.address == device.address }) {
                        foundList.add(BleDeviceItem(name = name, address = device.address, rssi = result.rssi))
                        _scannedDevices.value = foundList.toList()
                    }
                }
            }
        }

        scope.launch(Dispatchers.Main) {
            try {
                scanner.startScan(scanCallback)
                delay(3000)
                scanner.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning for WheelTop devices: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        Log.i(TAG, "[RULE 4] disconnectGatt() invoked. Force cancelling jobs, closing GATT socket, and resetting state...")
        blockWatchdogJob?.cancel()
        blockWatchdogJob = null
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        initJob?.cancel()
        initJob = null
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

        asciiBuffer.clear()
        _blockParameters.value = emptyMap()
        _rawBlockText.value = ""
        _blockStatusText.value = "Block 0..4 Locked"
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange: status=$status, newState=$newState")

            if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                Log.w(TAG, "onConnectionStateChange status=$status, newState=$newState. Scheduling self-healing auto-reconnect with 1500ms quiet period...")
                if (_isMonitored.value) {
                    _blockStatusText.value = "Auto-Reconnecting..."
                }
                disconnectGatt()
                scope.launch(Dispatchers.Main) {
                    delay(1500)
                    val boundMac = _boundMacAddress.value
                    if (_isMonitored.value && !boundMac.isNullOrBlank()) {
                        connectToBoundDevice(boundMac)
                    }
                }
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectTimeoutJob?.cancel()
                connectTimeoutJob = null
                activeGatt = gatt
                _connectionState.value = ConnectionState.CONNECTED
                Log.i(TAG, "GATT Physical Link Connected! Requesting CONNECTION_PRIORITY_HIGH (15ms interval) & Starting Service Discovery in 300ms...")

                try {
                    gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                } catch (e: Exception) {
                    Log.e(TAG, "Error requesting CONNECTION_PRIORITY_HIGH: ${e.message}")
                }

                scope.launch(Dispatchers.IO) {
                    delay(300)
                    try {
                        gatt?.discoverServices()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in discoverServices: ${e.message}")
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged: mtu=$mtu, status=$status")
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
            Log.i(TAG, "CCCD Descriptor Write Status: $status (OK=$isOk). Triggering Auth Handshake...")
            if (isOk) {
                _connectionState.value = ConnectionState.CONNECTED
                scope.launch(Dispatchers.IO) {
                    delay(250)
                    sendRawFrame("Auth Handshake", AUTH_PASS_FRAME)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            Log.i(TAG, "onCharacteristicRead: status=$status, len=${value.size}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleRxNotification(value)
            }
        }

        @Deprecated("Deprecated in API 33")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            @Suppress("DEPRECATION")
            val bytes = characteristic?.value ?: return
            Log.i(TAG, "onCharacteristicRead (Deprecated): status=$status, len=${bytes.size}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleRxNotification(bytes)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            val isOk = (status == BluetoothGatt.GATT_SUCCESS)
            Log.i(TAG, "onCharacteristicWrite: status=$status (OK=$isOk)")
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _currentRssi.value = rssi
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
            delay(250)

            pingPongStep = 2
            _blockStatusText.value = "Reading Block 0 (0/5)..."
            Log.i(TAG, "[FIREWORK FLOW] Step 2: Sending read block 0 (0xFB, 00 00 51)...")
            sendAppProtocolCommand("read block 0 (0xFB)", 0xFB, byteArrayOf(0x00, 0x00, 0x51.toByte()))
        }
    }

    private fun handleRxNotification(data: ByteArray) {
        if (data.isEmpty()) return

        val hexData = data.toHexString()
        Log.i(TAG, "===> [RAW RX NOTIFICATION] Len=${data.size} Hex=$hexData")

        // Read plain US-ASCII text fragment directly for 132-byte block notifications (data.size >= 100)
        if (data.size >= 100) {
            try {
                val rawAscii = String(data, 4, data.size - 4, Charsets.US_ASCII)
                val cleanFragment = rawAscii.filter { it.code in 32..126 || it == '\n' || it == '\r' || it == ',' || it == ';' }
                Log.i(TAG, "===> [ASCII PLAIN TEXT (OFFSET 4..N)]: \"$cleanFragment\"")
                asciiBuffer.append(cleanFragment)
                val fullText = asciiBuffer.toString()
                _rawBlockText.value = fullText
                Log.i(TAG, "===> [SPEICHER-STAND (${asciiBuffer.length} BYTES)]: \"$fullText\"")
                parseAsciiPowerString(fullText)
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
            val currentMap = _blockParameters.value.toMutableMap()
            currentMap["SESSION_KEY"] = "0x%02X".format(cleanKey)
            _blockParameters.value = currentMap
            Log.i(TAG, "VALID SESSION KEY ACTIVATED: 0x%02X. Triggering FIREWORK Flow...".format(cleanKey))
            startInitializationSequence()
        } else if (data.size >= 100) {
            if (pingPongStep == 2) {
                pingPongStep = 3
                _blockStatusText.value = "Block 0 Received (1/5)..."
                Log.i(TAG, "===> [BLOCK 0 RECEIVED (132 BYTES)!] Buffer 200ms -> Sending read block 1 (0xFB, 00 01 51)...")
                scope.launch(Dispatchers.IO) {
                    delay(200)
                    sendAppProtocolCommand("read block 1 (0xFB)", 0xFB, byteArrayOf(0x00, 0x01, 0x51.toByte()))
                }
            } else if (pingPongStep == 3) {
                pingPongStep = 4
                _blockStatusText.value = "Block 1 Received (2/5)..."
                Log.i(TAG, "===> [BLOCK 1 RECEIVED (132 BYTES)!] Buffer 200ms -> Sending read block 2 (0xFB, 00 02 51)...")
                scope.launch(Dispatchers.IO) {
                    delay(200)
                    sendAppProtocolCommand("read block 2 (0xFB)", 0xFB, byteArrayOf(0x00, 0x02, 0x51.toByte()))
                }
            } else if (pingPongStep == 4) {
                pingPongStep = 5
                _blockStatusText.value = "Block 2 Received (3/5)..."
                Log.i(TAG, "===> [BLOCK 2 RECEIVED (132 BYTES)!] Buffer 200ms -> Sending read block 3 (0xFB, 00 03 51)...")
                scope.launch(Dispatchers.IO) {
                    delay(200)
                    sendAppProtocolCommand("read block 3 (0xFB)", 0xFB, byteArrayOf(0x00, 0x03, 0x51.toByte()))
                }
            } else if (pingPongStep == 5) {
                pingPongStep = 6
                _blockStatusText.value = "Block 3 Received (4/5)..."
                Log.i(TAG, "===> [BLOCK 3 RECEIVED (132 BYTES)!] Buffer 200ms -> Sending read block 4 (0xFB, 00 04 51)...")
                scope.launch(Dispatchers.IO) {
                    delay(200)
                    sendAppProtocolCommand("read block 4 (0xFB)", 0xFB, byteArrayOf(0x00, 0x04, 0x51.toByte()))
                }
            } else if (pingPongStep == 6) {
                blockWatchdogJob?.cancel()
                blockWatchdogJob = null
                _blockStatusText.value = "Blocks 0..4 Unlocked (5/5 Complete)"
                Log.i(TAG, "===> [BLOCK 4 RECEIVED (132 BYTES)!] ALL BLOCKS 0..4 FULLY UNLOCKED!")
            }
        }

        if (cmd == 0x88 || cmd == 0x89 || cmd == 0x98 || cmd == 0x99) {
            parseBinaryBlockTelemetry(decoded)
        }
    }

    private fun parseBinaryBlockTelemetry(decoded: ByteArray) {
        if (decoded.size < 5) return
        val cmd = decoded.getOrNull(3)?.toInt()?.and(0xFF) ?: 0

        // Parse Front Derailleur (FD) Shift Events (CMD 0x88 / 0x89)
        if (cmd == 0x88 || cmd == 0x89) {
            val marker = (decoded.getOrNull(5)?.toInt() ?: 0) and 0xFF
            val gearPos = (decoded.getOrNull(9)?.toInt() ?: 0) and 0xFF

            if (decoded.size == 8) {
                Log.i(TAG, "===> [FD SHIFT] Front Derailleur Motor Running...")
            } else if (marker == 0x00) {
                Log.i(TAG, "===> [FD SHIFT] Front Derailleur Shift Initiated...")
            } else if (gearPos == 6 || gearPos == 5) {
                Log.i(TAG, "===> [FD SHIFT] Front Derailleur Trimming / Overshifting...")
            } else {
                val ringName = if (gearPos == 2) "Big Chainring" else "Small Chainring"
                Log.i(TAG, "===> [FD SHIFT COMPLETE] Front Derailleur Shifted to $ringName (Position $gearPos)")
                isShiftInProgress = false
                val currentMap = _blockParameters.value.toMutableMap()
                currentMap["FD_POSITION"] = "$ringName (Pos $gearPos)"
                _blockParameters.value = currentMap
            }
            return
        }

        // Parse Rear Derailleur (RD) Shift Events (CMD 0x98 / 0x99)
        if (cmd == 0x98 || cmd == 0x99) {
            val marker = (decoded.getOrNull(5)?.toInt() ?: 0) and 0xFF
            val gearPos = (decoded.getOrNull(9)?.toInt() ?: 0) and 0xFF

            if (decoded.size == 8 && marker != 0x4E && marker != 0x4F && marker != 0x50) {
                Log.i(TAG, "===> [RD SHIFT] Rear Derailleur Motor Running...")
            } else if (gearPos in 1..14) {
                Log.i(TAG, "===> [RD SHIFT COMPLETE] Rear Derailleur Shifted to Gear $gearPos")
                isShiftInProgress = false
                val currentMap = _blockParameters.value.toMutableMap()
                currentMap["RD_GEAR"] = "Gear $gearPos"
                _blockParameters.value = currentMap
            } else if (decoded.size >= 8) {
                // Parse battery telemetry if present in 0x98 frame
                val voltsRaw = (((decoded.getOrNull(6)?.toInt() ?: 0) and 0xFF) shl 8) or ((decoded.getOrNull(7)?.toInt() ?: 0) and 0xFF)
                if (voltsRaw in 240..330) {
                    val volts = voltsRaw / 100.0f
                    if (marker == 0x4E) {
                        _gattShifterLeftVolts.value = volts
                        _gattShifterLeftFirmware.value = "3.26"
                    } else if (marker == 0x4F || marker == 0x50) {
                        _gattShifterRightVolts.value = volts
                        _gattShifterRightFirmware.value = "4.33"
                    }
                }
            }
        }
    }

    private fun parseAsciiPowerString(text: String) {
        val parts = text.split(",", ";", "\n", "\r")
        val parsedMap = mutableMapOf<String, String>()
        for (part in parts) {
            val kv = part.split(":", "=")
            if (kv.size == 2) {
                val key = kv[0].trim().uppercase()
                val valStr = kv[1].trim()

                if (key.isNotEmpty() && valStr.isNotEmpty()) {
                    parsedMap[key] = valStr
                }

                val cleanValStr = valStr.replace("V", "").trim()

                when (key) {
                    "L_VER" -> _gattShifterLeftFirmware.value = cleanValStr
                    "L_POWER" -> cleanValStr.toFloatOrNull()?.let {
                        val volts = if (it > 20f) it / 100.0f else it
                        _gattShifterLeftVolts.value = volts
                        Log.i(TAG, "Parsed WheelTop ASCII L_POWER: $volts V")
                    }

                    "R_VER" -> _gattShifterRightFirmware.value = cleanValStr
                    "R_POWER" -> cleanValStr.toFloatOrNull()?.let {
                        val volts = if (it > 20f) it / 100.0f else it
                        _gattShifterRightVolts.value = volts
                        Log.i(TAG, "Parsed WheelTop ASCII R_POWER: $volts V")
                    }

                    "Q_VER", "FD_VER" -> _gattFrontFirmware.value = cleanValStr
                    "Q_POWER", "FD_POWER" -> cleanValStr.toFloatOrNull()?.let {
                        val volts = if (it > 20f) it / 100.0f else it
                        _gattFrontVolts.value = volts
                        Log.i(TAG, "Parsed WheelTop ASCII Q_POWER: $volts V")
                    }

                    "H_VER", "RD_VER" -> _gattRearFirmware.value = cleanValStr
                    "H_POWER", "RD_POWER" -> cleanValStr.toFloatOrNull()?.let {
                        val volts = if (it > 20f) it / 100.0f else it
                        _gattRearVolts.value = volts
                        Log.i(TAG, "Parsed WheelTop ASCII H_POWER: $volts V")
                    }
                }
            }
        }
        if (parsedMap.isNotEmpty()) {
            val currentMap = _blockParameters.value.toMutableMap()
            currentMap.putAll(parsedMap)
            _blockParameters.value = currentMap
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

    // Rule 5: Fire-and-Forget GATT Write with 250ms Pacing
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
        isShiftInProgress = true
        scope.launch(Dispatchers.IO) {
            Log.i(TAG, "[ADB SHIFT] Shifting Rear Derailleur (RD) direction: $direction")
            sendAppProtocolCommand("Rear Shift ($direction)", 0x99, byteArrayOf(direction.toByte()))
            delay(1200)
            isShiftInProgress = false
        }
    }

    fun toggleFrontDerailleur() {
        isShiftInProgress = true
        scope.launch(Dispatchers.IO) {
            Log.i(TAG, "[ADB SHIFT] Toggling Front Derailleur (FD)...")
            sendAppProtocolCommand("Front Derailleur Toggle", 0x89, byteArrayOf(0x02.toByte()))
            delay(1200)
            isShiftInProgress = false
        }
    }
}
