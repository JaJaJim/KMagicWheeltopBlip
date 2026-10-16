@file:OptIn(ExperimentalStdlibApi::class)

package io.github.JaJaJim.karooedscharge.ble

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
import io.github.JaJaJim.karooedscharge.data.RemotePreferences
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

    @Volatile
    private var isLatched = false

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

        scope.launch(Dispatchers.Main) {
            Log.i(TAG, "[AUTO-SCAN CACHE] Triggering 1-second BLE Scan to populate $macAddress in Android GATT cache...")
            val scanner = bluetoothAdapter?.bluetoothLeScanner
            val scanCallback = object : android.bluetooth.le.ScanCallback() {}

            try {
                scanner?.startScan(scanCallback)
                delay(1000)
                scanner?.stopScan(scanCallback)
                delay(300)
            } catch (e: Exception) {
                Log.e(TAG, "Scan error: ${e.message}")
            }

            try {
                Log.i(TAG, "[RULE 1] Connecting SINGLE GATT Client to MAC: $macAddress ...")
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
    private fun disconnectGatt() {
        Log.i(TAG, "[RULE 4] disconnectGatt() invoked. Force cancelling jobs, closing GATT socket, and resetting state...")
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
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange: status=$status, newState=$newState")

            if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                Log.w(TAG, "onConnectionStateChange status=$status, newState=$newState. Scheduling self-healing auto-reconnect in 1s...")
                disconnectGatt()
                scope.launch(Dispatchers.Main) {
                    delay(1000)
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
        initJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "[btsnoop_hci.log 1:1 FINAL] Step 1: Sending 1-byte 0xFA startRead (0x80)...")
            sendAppProtocolCommand("0xFA (startRead)", 0xFA, byteArrayOf(0x80.toByte()))
        }
    }

    private fun checkAndLatchIfComplete() {
        if (_gattRearVolts.value != null && _gattFrontVolts.value != null && _gattShifterLeftVolts.value != null && _gattShifterRightVolts.value != null) {
            isLatched = true
            Log.i(TAG, "[RULE 2 LATCHED] All 4 GATT components captured! Freezing UI state and ignoring further RX traffic until Slide to monitor = OFF.")
            initJob?.cancel()
            initJob = null
        }
    }

    private fun handleRxNotification(data: ByteArray) {
        if (data.isEmpty()) return

        val hexData = data.toHexString()
        Log.i(TAG, "===> [RAW RX NOTIFICATION] Len=${data.size} Hex=$hexData")

        // Unmask and accumulate text ONLY for 132-byte block notifications (data.size >= 100)
        if (data.size >= 100) {
            try {
                val rawXorByte = data[1].toInt() and 0xFF
                val xorMask = rawXorByte xor 0x32

                val unmasked = ByteArray(data.size - 4)
                for (i in 4 until data.size) {
                    unmasked[i - 4] = (data[i].toInt() xor xorMask).toByte()
                }

                val asciiFragment = String(unmasked, Charsets.US_ASCII)
                Log.i(TAG, "===> [ASCII BLOCK TEXT (OFFSET 4..N)]: \"$asciiFragment\"")
                asciiBuffer.append(asciiFragment)
                val fullText = asciiBuffer.toString()
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
            Log.i(TAG, "SESSION KEY ACTIVATED: 0x%02X. Triggering 10-Byte startRead Flow...".format(cleanKey))
            startInitializationSequence()
        } else {
            // Event-Driven Progression upon startRead ACK & Block Notifications
            if (pingPongStep == 1) {
                pingPongStep = 2
                Log.i(TAG, "===> [0xFA startRead ACK RECEIVED!] Buffer 100ms -> Sending read block 0 (0xFB, 00 00 51)...")
                scope.launch(Dispatchers.IO) {
                    delay(100)
                    sendAppProtocolCommand("read block 0 (0xFB)", 0xFB, byteArrayOf(0x00, 0x00, 0x51.toByte()))
                }
            } else if (pingPongStep == 2 && data.size >= 100) {
                pingPongStep = 3
                Log.i(TAG, "===> [BLOCK 0 RECEIVED (132 BYTES)!] Buffer 200ms -> Sending read block 1 (0xFB, 00 01 51)...")
                scope.launch(Dispatchers.IO) {
                    delay(200)
                    sendAppProtocolCommand("read block 1 (0xFB)", 0xFB, byteArrayOf(0x00, 0x01, 0x51.toByte()))
                }
            } else if (pingPongStep == 3 && data.size >= 100) {
                pingPongStep = 4
                Log.i(TAG, "===> [BLOCK 1 RECEIVED (132 BYTES)!] Buffer 200ms -> Sending read block 2 (0xFB, 00 02 51)...")
                scope.launch(Dispatchers.IO) {
                    delay(200)
                    sendAppProtocolCommand("read block 2 (0xFB)", 0xFB, byteArrayOf(0x00, 0x02, 0x51.toByte()))
                }
            } else if (pingPongStep == 4 && data.size >= 100) {
                pingPongStep = 5
                Log.i(TAG, "===> [BLOCK 2 RECEIVED (132 BYTES)!] Buffer 200ms -> Sending read block 3 (0xFB, 00 03 51)...")
                scope.launch(Dispatchers.IO) {
                    delay(200)
                    sendAppProtocolCommand("read block 3 (0xFB)", 0xFB, byteArrayOf(0x00, 0x03, 0x51.toByte()))
                }
            } else if (pingPongStep == 5 && data.size >= 100) {
                pingPongStep = 6
                Log.i(TAG, "===> [BLOCK 3 RECEIVED (132 BYTES)!] Buffer 200ms -> Sending read block 4 (0xFB, 00 04 51)...")
                scope.launch(Dispatchers.IO) {
                    delay(200)
                    sendAppProtocolCommand("read block 4 (0xFB)", 0xFB, byteArrayOf(0x00, 0x04, 0x51.toByte()))
                }
            } else if (pingPongStep == 6 && data.size >= 100) {
                Log.i(TAG, "===> [BLOCK 4 RECEIVED (132 BYTES)!] ALL BLOCKS 0..4 FULLY UNLOCKED!")
            }
        }
    }

    private fun parseBinaryBlockTelemetry(decoded: ByteArray) {
        if (decoded.size < 7) return
        val cmd = decoded.getOrNull(3)?.toInt()?.and(0xFF) ?: 0

        // Suppress CMD 0x88 shift notifications completely
        if (cmd == 0x88) return

        if (cmd == 0x98 && decoded.size >= 8) {
            val marker = (decoded.getOrNull(5)?.toInt() ?: 0) and 0xFF
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
        checkAndLatchIfComplete()
    }

    private fun parseAsciiPowerString(text: String) {
        val parts = text.split(",", ";", "\n", "\r")
        for (part in parts) {
            val kv = part.split(":", "=")
            if (kv.size == 2) {
                val key = kv[0].trim().uppercase()
                val valStr = kv[1].trim().replace("V", "")

                when (key) {
                    "L_VER" -> _gattShifterLeftFirmware.value = valStr
                    "L_POWER" -> valStr.toFloatOrNull()?.let {
                        val volts = if (it > 20f) it / 100.0f else it
                        _gattShifterLeftVolts.value = volts
                        Log.i(TAG, "Parsed WheelTop ASCII L_POWER: $volts V")
                    }

                    "R_VER" -> _gattShifterRightFirmware.value = valStr
                    "R_POWER" -> valStr.toFloatOrNull()?.let {
                        val volts = if (it > 20f) it / 100.0f else it
                        _gattShifterRightVolts.value = volts
                        Log.i(TAG, "Parsed WheelTop ASCII R_POWER: $volts V")
                    }

                    "Q_VER", "FD_VER" -> _gattFrontFirmware.value = valStr
                    "Q_POWER", "FD_POWER" -> valStr.toFloatOrNull()?.let {
                        val volts = if (it > 20f) it / 100.0f else it
                        _gattFrontVolts.value = volts
                        Log.i(TAG, "Parsed WheelTop ASCII Q_POWER: $volts V")
                    }

                    "H_VER", "RD_VER" -> _gattRearFirmware.value = valStr
                    "H_POWER", "RD_POWER" -> valStr.toFloatOrNull()?.let {
                        val volts = if (it > 20f) it / 100.0f else it
                        _gattRearVolts.value = volts
                        Log.i(TAG, "Parsed WheelTop ASCII H_POWER: $volts V")
                    }
                }
            }
        }
        checkAndLatchIfComplete()
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
}
