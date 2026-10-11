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
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.JaJaJim.karooedscharge.data.RemotePreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
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
    private var isScanningInternal = false
    private var sessionKeyByte: Byte? = null
    private var heartbeatJob: Job? = null
    private var reScanTimerJob: Job? = null

    private val writeMutex = Mutex()
    @Volatile
    private var pendingWriteDeferred: CompletableDeferred<Boolean>? = null

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

    private val _isReScanning = MutableStateFlow(false)
    val isReScanning: StateFlow<Boolean> = _isReScanning.asStateFlow()

    private val _batteryRearVolts = MutableStateFlow<Float?>(null)
    val batteryRearVolts: StateFlow<Float?> = _batteryRearVolts.asStateFlow()

    private val _batteryFrontVolts = MutableStateFlow<Float?>(null)
    val batteryFrontVolts: StateFlow<Float?> = _batteryFrontVolts.asStateFlow()

    private val _batteryShifterRightVolts = MutableStateFlow<Float?>(null)
    val batteryShifterRightVolts: StateFlow<Float?> = _batteryShifterRightVolts.asStateFlow()

    private val _batteryShifterLeftVolts = MutableStateFlow<Float?>(null)
    val batteryShifterLeftVolts: StateFlow<Float?> = _batteryShifterLeftVolts.asStateFlow()

    private val _currentRssi = MutableStateFlow<Int?>(null)
    val currentRssi: StateFlow<Int?> = _currentRssi.asStateFlow()

    private val _isAuthenticatedState = MutableStateFlow(false)
    val isAuthenticatedState: StateFlow<Boolean> = _isAuthenticatedState.asStateFlow()

    private val _diagnosticLogs = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val diagnosticLogs: StateFlow<List<DiagnosticLogEntry>> = _diagnosticLogs.asStateFlow()

    private val foundDevicesMap = mutableMapOf<String, BleDeviceItem>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoReconnectRunnable: Runnable? = null

    init {
        logDiagnostic("KarooEDSCharge WheeltopManager initialized.")

        scope.launch {
            remotePreferences.boundWheeltopMac.collectLatest { mac ->
                _boundMacAddress.value = mac
                if (mac.isNullOrBlank()) {
                    disconnectGatt()
                    _connectionState.value = ConnectionState.DISCONNECTED
                    logDiagnostic("Cleared bound gear.")
                } else {
                    logDiagnostic("Loaded bound gear: $mac")
                    connectToBoundDevice(mac)
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
                if (monitored) {
                    stopScan()
                } else if (_boundMacAddress.value == null) {
                    startScan()
                }
            }
        }

        // Automatic periodic background scan when not monitored
        scope.launch {
            while (isActive) {
                delay(2000)
                if (!_isMonitored.value && !_isReScanning.value && !isScanningInternal && _boundMacAddress.value == null) {
                    startScan()
                }
            }
        }
    }

    fun logDiagnostic(msg: String, isError: Boolean = false, isSuccess: Boolean = false) {
        Timber.i("$TAG: $msg")
        val entry = DiagnosticLogEntry(message = msg, isError = isError, isSuccess = isSuccess)
        val currentList = _diagnosticLogs.value.toMutableList()
        currentList.add(0, entry)
        if (currentList.size > 40) {
            currentList.removeAt(currentList.lastIndex)
        }
        _diagnosticLogs.value = currentList
    }

    fun clearDiagnosticLogs() {
        _diagnosticLogs.value = emptyList()
    }

    fun isWheeltopDerailleurDevice(name: String?, result: ScanResult?): Boolean {
        val rawName = name ?: result?.scanRecord?.deviceName ?: return false
        if (rawName.isBlank()) return false

        val uppercaseName = rawName.uppercase()
        return uppercaseName.contains("EDS XT") ||
                uppercaseName.contains("EDS-XT") ||
                uppercaseName.contains("EDS TX") ||
                uppercaseName.contains("EDS-TX") ||
                uppercaseName.contains("EDS OX") ||
                uppercaseName.contains("EDS-OX") ||
                uppercaseName.contains("EDS")
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            logDiagnostic("Bluetooth disabled", isError = true)
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        try {
            scanner.stopScan(scanCallback)
        } catch (_: Exception) {}

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
            isScanningInternal = true
            if (_boundMacAddress.value == null) {
                _connectionState.value = ConnectionState.SCANNING
            }
            logDiagnostic("BLE scan started")
        } catch (e: Exception) {
            logDiagnostic("Scan error: ${e.message}", isError = true)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanningInternal) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            logDiagnostic("Error stopping scan: ${e.message}", isError = true)
        }
        isScanningInternal = false
        if (_boundMacAddress.value == null && _connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
        logDiagnostic("BLE scan stopped.")
    }

    fun triggerReScan() {
        reScanTimerJob?.cancel()
        reScanTimerJob = scope.launch {
            _isReScanning.value = true
            logDiagnostic("Manual Re-Scan triggered (1-minute timer)")
            startScan()
            delay(60_000) // 1 minute max
            stopScan()
            _isReScanning.value = false
            logDiagnostic("Manual Re-Scan completed")
        }
    }

    fun toggleMonitoring(enabled: Boolean) {
        scope.launch {
            _isMonitored.value = enabled
            remotePreferences.saveIsMonitored(enabled)
            if (enabled) {
                stopScan()
            } else if (_boundMacAddress.value == null) {
                startScan()
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device ?: return
            val address = device.address ?: return
            val rawName = device.name ?: result.scanRecord?.deviceName
            val rssi = result.rssi

            val boundMac = _boundMacAddress.value
            if (!boundMac.isNullOrBlank() && address.equals(boundMac, ignoreCase = true)) {
                parseAdvertisingTelemetry(result)
                return
            }

            if (!isWheeltopDerailleurDevice(rawName, result)) return

            val item = BleDeviceItem(
                name = rawName!!.trim(),
                address = address,
                rssi = rssi
            )

            foundDevicesMap[address] = item
            _scannedDevices.value = foundDevicesMap.values.sortedByDescending { it.rssi }
        }
    }

    private fun parseAdvertisingTelemetry(result: ScanResult) {
        val record = result.scanRecord ?: return
        val rawBytes = record.bytes ?: return

        if (rawBytes.size >= 12) {
            val deviceType = (rawBytes.getOrNull(7)?.toInt() ?: 0) and 0xFF
            val byte10 = (rawBytes.getOrNull(10)?.toInt() ?: 0) and 0xFF
            val byte11 = (rawBytes.getOrNull(11)?.toInt() ?: 0) and 0xFF
            val voltageRaw = (byte10 shl 8) or byte11

            if (voltageRaw in 150..850) {
                val volts = voltageRaw / 100.0f
                when (deviceType) {
                    0x37, 0x38 -> {
                        _batteryRearVolts.value = volts
                    }
                    0x39 -> {
                        _batteryFrontVolts.value = volts
                    }
                    0x36 -> {
                        _batteryShifterRightVolts.value = volts
                        _batteryShifterLeftVolts.value = volts
                    }
                }
            }
        }
    }

    fun bindDevice(address: String, name: String) {
        scope.launch {
            _isMonitored.value = false
            remotePreferences.saveBoundWheeltop(address, name)
            logDiagnostic("Bound gear: $name ($address)", isSuccess = true)
            connectToBoundDevice(address)
        }
    }

    fun unbindDevice() {
        scope.launch {
            disconnectGatt()
            _isMonitored.value = false
            remotePreferences.clearBoundWheeltop()
            logDiagnostic("Unbound gear.")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToBoundDevice(macAddress: String) {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            logDiagnostic("Bluetooth disabled!", isError = true)
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect(macAddress)
            return
        }

        try {
            val device = bluetoothAdapter?.getRemoteDevice(macAddress) ?: return
            disconnectGatt()
            _connectionState.value = ConnectionState.CONNECTING
            logDiagnostic("Connecting GATT to $macAddress ...")

            activeGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            logDiagnostic("GATT connection error: ${e.message}", isError = true)
            scheduleReconnect(macAddress)
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        cancelScheduledReconnect()
        stopHeartbeat()
        try {
            activeGatt?.disconnect()
            activeGatt?.close()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error disconnecting Wheeltop GATT")
        }
        activeGatt = null
        txCharacteristic = null
        _isAuthenticatedState.value = false
        sessionKeyByte = null
        _currentRssi.value = null
        pendingWriteDeferred?.complete(false)
        pendingWriteDeferred = null
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                delay(3000)
                if (_isAuthenticatedState.value) {
                    sendAppProtocolCommand("Heartbeat (0xFA)", 0xFA, byteArrayOf(0x80.toByte()))
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun scheduleReconnect(macAddress: String) {
        cancelScheduledReconnect()
        val runnable = Runnable {
            if (_boundMacAddress.value == macAddress && _connectionState.value != ConnectionState.CONNECTED) {
                logDiagnostic("Re-connecting to $macAddress")
                connectToBoundDevice(macAddress)
            }
        }
        autoReconnectRunnable = runnable
        mainHandler.postDelayed(runnable, 5_000)
    }

    private fun cancelScheduledReconnect() {
        autoReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        autoReconnectRunnable = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            logDiagnostic("GATT State Change: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = ConnectionState.CONNECTING
                logDiagnostic("GATT Connected! Requesting MTU 247...")
                gatt.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = ConnectionState.DISCONNECTED
                txCharacteristic = null
                _isAuthenticatedState.value = false
                sessionKeyByte = null
                stopHeartbeat()
                _currentRssi.value = null
                pendingWriteDeferred?.complete(false)
                pendingWriteDeferred = null

                try {
                    gatt.close()
                } catch (_: Exception) {}
                if (activeGatt == gatt) {
                    activeGatt = null
                }

                logDiagnostic("GATT Disconnected (status=$status)", isError = true)
                val mac = _boundMacAddress.value
                if (!mac.isNullOrBlank()) {
                    scheduleReconnect(mac)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            logDiagnostic("MTU Result: mtu=$mtu status=$status. Discovering services...")
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logDiagnostic("Service Discovery Failed: status=$status", isError = true)
                return
            }

            val service = gatt.getService(NUS_SERVICE_UUID)
            if (service != null) {
                txCharacteristic = service.getCharacteristic(NUS_TX_UUID)
                val rxChar = service.getCharacteristic(NUS_RX_UUID)

                logDiagnostic("Nordic UART Service found! Enabling RX notifications...")

                if (rxChar != null) {
                    enableNotification(gatt, rxChar)
                } else {
                    logDiagnostic("RX Characteristic (6e400003) not found!", isError = true)
                }
            } else {
                logDiagnostic("Nordic UART Service (6e400001) not found!", isError = true)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            val isOk = (status == BluetoothGatt.GATT_SUCCESS)
            logDiagnostic("CCCD Notification Descriptor written: Status OK=$isOk", isSuccess = isOk, isError = !isOk)

            if (isOk) {
                _connectionState.value = ConnectionState.CONNECTED
                logDiagnostic("Starting auth handshake...")

                scope.launch(Dispatchers.IO) {
                    delay(250)
                    sendRawFrame("Auth Handshake", AUTH_PASS_FRAME)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            val isOk = (status == BluetoothGatt.GATT_SUCCESS)
            logDiagnostic("Characteristic Write Status: $status (OK=$isOk)")
            pendingWriteDeferred?.complete(isOk)
            pendingWriteDeferred = null
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _currentRssi.value = rssi
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            logDiagnostic("RX Notification: ${value.toHexString()}")
            handleRxNotification(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value ?: return
            logDiagnostic("RX Notification: ${bytes.toHexString()}")
            handleRxNotification(bytes)
        }
    }

    private fun startInitializationSequence() {
        scope.launch(Dispatchers.IO) {
            delay(300)
            sendAppProtocolCommand("startRead (0xFA)", 0xFA, byteArrayOf(0x80.toByte()))

            delay(300)
            sendAppProtocolCommand("read block 0 (0xFB)", 0xFB, byteArrayOf(0x00, 0x00, 0x51.toByte()))

            delay(300)
            sendAppProtocolCommand("read block 1 (0xFB)", 0xFB, byteArrayOf(0x00, 0x01, 0x51.toByte()))

            delay(300)
            sendAppProtocolCommand("read block 2 (0xFB)", 0xFB, byteArrayOf(0x00, 0x02, 0x51.toByte()))

            delay(300)
            sendAppProtocolCommand("read block 3 (0xFB)", 0xFB, byteArrayOf(0x00, 0x03, 0x51.toByte()))

            delay(300)
            sendAppProtocolCommand("read block 4 (0xFB)", 0xFB, byteArrayOf(0x00, 0x04, 0x51.toByte()))

            delay(300)
            sendAppProtocolCommand("getTransmissionVersionInfo (0x60)", 0x60)

            delay(300)
            sendAppProtocolCommand("backSetting1p (0x67)", 0x67, byteArrayOf(0x01, 0x01))

            delay(300)
            _isAuthenticatedState.value = true
            logDiagnostic("INITIALIZATION COMPLETE - READY!", isSuccess = true)

            startHeartbeat()
        }
    }

    private fun handleRxNotification(data: ByteArray) {
        if (data.isEmpty()) return

        if (data.size >= 30) {
            try {
                val asciiString = String(data, 4, data.size - 4, Charsets.US_ASCII)
                if (asciiString.contains("POWER") || asciiString.contains("H_") || asciiString.contains("Q_") || asciiString.contains("L_")) {
                    logDiagnostic("ASCII telemetry: $asciiString", isSuccess = true)
                    parseAsciiPowerString(asciiString)
                }
            } catch (_: Exception) {}
            return
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

        if (cmd == 0x11 && data.size >= 6) {
            val extractedKey = ((data[1].toInt() and 0xFF) - 0x32 and 0xFF).toByte()
            sessionKeyByte = extractedKey
            _isAuthenticatedState.value = true
            logDiagnostic("SESSION KEY ACTIVATED: 0x%02X".format(extractedKey), isSuccess = true)

            startInitializationSequence()
        }
    }

    private fun parseAsciiPowerString(text: String) {
        val parts = text.split(",", ";", " ", "\n", "\r")
        for (part in parts) {
            val kv = part.split(":", "=")
            if (kv.size == 2) {
                val key = kv[0].trim().uppercase()
                val valueInt = kv[1].trim().toIntOrNull() ?: continue
                val volts = valueInt / 100.0f

                if (key.contains("H_POWER") || key.contains("POWER_2") || key == "H_POWER") {
                    _batteryRearVolts.value = volts
                    logDiagnostic("Rear Derailleur: $volts V", isSuccess = true)
                } else if (key.contains("Q_POWER") || key.contains("POWER_1") || key == "Q_POWER") {
                    _batteryFrontVolts.value = volts
                    logDiagnostic("Front Derailleur: $volts V", isSuccess = true)
                } else if (key.contains("R_POWER") || key.contains("SHIFTER_R")) {
                    _batteryShifterRightVolts.value = volts
                    logDiagnostic("Shifter Right: $volts V", isSuccess = true)
                } else if (key.contains("L_POWER") || key.contains("SHIFTER_L")) {
                    _batteryShifterLeftVolts.value = volts
                    logDiagnostic("Shifter Left: $volts V", isSuccess = true)
                } else if (key.contains("POWER")) {
                    _batteryShifterRightVolts.value = volts
                    _batteryShifterLeftVolts.value = volts
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                logDiagnostic("Writing CCCD descriptor...")
                val props = characteristic.properties
                val valueBytes = if ((props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, valueBytes)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = valueBytes
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            } else {
                logDiagnostic("CCCD descriptor not found", isError = true)
            }
        } catch (e: Exception) {
            logDiagnostic("Error enabling notifications: ${e.message}", isError = true)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendRawFrame(actionName: String, bytes: ByteArray): Boolean = writeMutex.withLock {
        val gatt = activeGatt
        val tx = txCharacteristic

        if (gatt == null || tx == null) {
            logDiagnostic("Cannot send $actionName: GATT/TX not ready", isError = true)
            return@withLock false
        }

        val deferred = CompletableDeferred<Boolean>()
        pendingWriteDeferred = deferred

        try {
            val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

            val writeInitiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(tx, bytes, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                tx.value = bytes
                @Suppress("DEPRECATION")
                tx.writeType = writeType
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(tx)
            }

            if (!writeInitiated) {
                logDiagnostic("gatt.writeCharacteristic returned false for $actionName", isError = true)
                pendingWriteDeferred = null
                return@withLock false
            }

            val writeResult = withTimeoutOrNull(500) {
                deferred.await()
            } ?: true

            logDiagnostic("Sent [$actionName]", isSuccess = true)
            return@withLock true
        } catch (e: Exception) {
            logDiagnostic("Error sending $actionName: ${e.message}", isError = true)
            pendingWriteDeferred = null
            return@withLock false
        }
    }

    private fun buildAppFrame(commandId: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val key = sessionKeyByte ?: 0x29.toByte()
        val rawXorByte = 0x32.toByte()
        val xorKey = (rawXorByte.toInt() and 0xFF) - 0x32

        val headerAndPayload = mutableListOf<Byte>()
        headerAndPayload.add((key.toInt() xor xorKey).toByte())
        headerAndPayload.add((commandId xor xorKey).toByte())
        headerAndPayload.add((payload.size xor xorKey).toByte())
        for (b in payload) {
            headerAndPayload.add((b.toInt() xor xorKey).toByte())
        }

        val crc = calculateCrc16(headerAndPayload.toByteArray())
        val crcHigh = ((crc shr 8) and 0xFF).toByte()
        val crcLow = (crc and 0xFF).toByte()

        val fullFrame = ByteArray(5 + payload.size + 2)
        fullFrame[0] = 0xFE.toByte()
        fullFrame[1] = rawXorByte
        for (i in headerAndPayload.indices) {
            fullFrame[2 + i] = headerAndPayload[i]
        }
        fullFrame[fullFrame.size - 2] = crcHigh
        fullFrame[fullFrame.size - 1] = crcLow

        return fullFrame
    }

    private fun calculateCrc16(bytes: ByteArray): Int {
        var crc = 0x0000
        for (b in bytes) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            for (bit in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    suspend fun sendAppProtocolCommand(name: String, commandId: Int, payload: ByteArray = byteArrayOf()): Boolean {
        val frame = buildAppFrame(commandId, payload)
        return sendRawFrame(name, frame)
    }
}
