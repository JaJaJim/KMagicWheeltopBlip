package io.github.JaJaJim.karooedscharge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.JaJaJim.karooedscharge.ble.BleDeviceItem
import io.github.JaJaJim.karooedscharge.ble.ConnectionState
import io.github.JaJaJim.karooedscharge.ble.MagicshineRemoteManager
import io.github.JaJaJim.karooedscharge.ble.WheeltopManager
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var wheeltopManager: WheeltopManager
    private lateinit var remoteManager: MagicshineRemoteManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wheeltopManager = WheeltopManager.getInstance(applicationContext)
        remoteManager = MagicshineRemoteManager.getInstance(applicationContext)

        checkPermissionsAndScan()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFE6DAF0) // Light soft violet background
                ) {
                    EdsChargeLightScreen(wheeltopManager = wheeltopManager, remoteManager = remoteManager)
                }
            }
        }
    }

    private fun checkPermissionsAndScan() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

@Composable
fun EdsChargeLightScreen(wheeltopManager: WheeltopManager, remoteManager: MagicshineRemoteManager) {
    val connectionState by wheeltopManager.connectionState.collectAsState()
    val boundMac by wheeltopManager.boundMacAddress.collectAsState()
    val boundName by wheeltopManager.boundDeviceName.collectAsState()
    val isMonitored by wheeltopManager.isMonitored.collectAsState()

    val remoteConnectionState by remoteManager.connectionState.collectAsState()
    val boundRemoteMac by remoteManager.boundRemoteMac.collectAsState()
    val boundRemoteName by remoteManager.boundRemoteName.collectAsState()
    val scannedRemotes by remoteManager.scannedRemotes.collectAsState()
    val lastClickText by remoteManager.lastClickText.collectAsState()

    val gattRearVolts by wheeltopManager.gattRearVolts.collectAsState()
    val gattFrontVolts by wheeltopManager.gattFrontVolts.collectAsState()
    val gattShifterRightVolts by wheeltopManager.gattShifterRightVolts.collectAsState()
    val gattShifterLeftVolts by wheeltopManager.gattShifterLeftVolts.collectAsState()

    val gattRearFirmware by wheeltopManager.gattRearFirmware.collectAsState()
    val gattFrontFirmware by wheeltopManager.gattFrontFirmware.collectAsState()
    val gattShifterRightFirmware by wheeltopManager.gattShifterRightFirmware.collectAsState()
    val gattShifterLeftFirmware by wheeltopManager.gattShifterLeftFirmware.collectAsState()

    val blockParameters by wheeltopManager.blockParameters.collectAsState()
    val blockStatusText by wheeltopManager.blockStatusText.collectAsState()

    val statusText = when {
        !isMonitored -> "BLE Disconnected"
        connectionState == ConnectionState.CONNECTED -> "GATT Connected"
        connectionState == ConnectionState.CONNECTING -> "Connecting GATT..."
        else -> "BLE Disconnected"
    }

    val remoteStatusText = when (remoteConnectionState) {
        ConnectionState.CONNECTED -> "Remote Active (Low Latency)"
        ConnectionState.CONNECTING -> "Connecting Remote..."
        else -> "Remote Standby"
    }

    val modelName = boundName ?: "EDS TX"
    val isOxModel = modelName.contains("OX", ignoreCase = true)
    val isGxModel = modelName.contains("GX", ignoreCase = true) || modelName.contains("GEX", ignoreCase = true)

    // Auto-scan for remotes if no remote is bound
    LaunchedEffect(boundRemoteMac) {
        if (boundRemoteMac.isNullOrBlank()) {
            remoteManager.startScanForRemotes()
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            // Header with Extension Title on Left and Logo on Right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "KMagicWheeltopBlip v0.0.3",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E1B2E)
                    )
                    Text(
                        text = "Ham 🐵🚀 Release",
                        fontSize = 12.sp,
                        color = Color(0xFF5E546E)
                    )
                }

                Image(
                    painter = painterResource(id = R.drawable.ic_logo),
                    contentDescription = "Logo",
                    modifier = Modifier.size(42.dp)
                )
            }

            Text(
                text = "Wheeltop EDS",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E1B2E),
                modifier = Modifier.padding(vertical = 6.dp)
            )

            GattMasterCard(
                deviceName = modelName,
                statusText = statusText,
                isMonitored = isMonitored,
                isOxModel = isOxModel,
                isGxModel = isGxModel,
                gattRearVolts = gattRearVolts,
                gattFrontVolts = gattFrontVolts,
                gattShifterRightVolts = gattShifterRightVolts,
                gattShifterLeftVolts = gattShifterLeftVolts,
                gattRearFirmware = gattRearFirmware,
                gattFrontFirmware = gattFrontFirmware,
                gattShifterRightFirmware = gattShifterRightFirmware,
                gattShifterLeftFirmware = gattShifterLeftFirmware,
                blockParameters = blockParameters,
                blockStatusText = blockStatusText,
                onMonitoredChange = { enabled ->
                    val mac = if (boundMac.isNullOrBlank()) "D4:85:A1:BE:24:3B" else boundMac!!
                    if (boundMac.isNullOrBlank()) {
                        wheeltopManager.bindDevice(mac, "EDS TX")
                    }
                    wheeltopManager.toggleMonitoring(enabled)
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Magicshine Remote",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E1B2E),
                modifier = Modifier.padding(vertical = 6.dp)
            )

            val displayRemotes = when {
                !boundRemoteMac.isNullOrBlank() -> listOf(
                    BleDeviceItem(
                        name = boundRemoteName ?: "M0-C0 MJ_6558",
                        address = boundRemoteMac!!,
                        rssi = 0
                    )
                )
                scannedRemotes.isNotEmpty() -> scannedRemotes
                else -> listOf(
                    BleDeviceItem(
                        name = "M0-C0 MJ_6558",
                        address = "60:03:00:08:CC:77",
                        rssi = 0
                    )
                )
            }

            displayRemotes.forEach { remoteItem ->
                val isThisRemoteBound = boundRemoteMac.equals(remoteItem.address, ignoreCase = true)
                RemoteCard(
                    deviceName = remoteItem.name,
                    deviceMac = remoteItem.address,
                    statusText = if (isThisRemoteBound) remoteStatusText else "Scanning for Remote...",
                    isBound = isThisRemoteBound,
                    lastClickText = if (isThisRemoteBound) lastClickText else null,
                    onBindChange = { enabled ->
                        if (enabled) {
                            remoteManager.bindRemote(remoteItem.address, remoteItem.name)
                        } else {
                            remoteManager.unbindRemote()
                        }
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun RemoteCard(
    deviceName: String,
    deviceMac: String,
    statusText: String,
    isBound: Boolean,
    lastClickText: String?,
    onBindChange: (Boolean) -> Unit
) {
    val containerColor = if (isBound) Color(0x3F45DDA0) else Color(0x3FE53935)
    val borderColor = if (isBound) Color(0x8045DDA0) else Color(0x80D32F2F)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = deviceName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color(0xFF1E1B2E)
                    )
                    Text(
                        text = "$deviceMac ($statusText)",
                        fontSize = 11.sp,
                        color = Color(0xFF5E546E)
                    )
                }
            }

            if (isBound && !lastClickText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Live Feedback: $lastClickText",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF004D40)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "• Square Button [□]: RD Shift Up (1 click)\n• Circle Button [○]: RD Shift Down (1 click)\n• Double Click [□/○]: FD Chainring Toggle",
                fontSize = 11.sp,
                color = Color(0xFF333333)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Slide to bind",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1E1B2E)
                )

                Switch(
                    checked = isBound,
                    onCheckedChange = onBindChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF007A5E),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFB00020)
                    )
                )
            }
        }
    }
}

@Composable
private fun GattMasterCard(
    deviceName: String,
    statusText: String,
    isMonitored: Boolean,
    isOxModel: Boolean,
    isGxModel: Boolean,
    gattRearVolts: Float?,
    gattFrontVolts: Float?,
    gattShifterRightVolts: Float?,
    gattShifterLeftVolts: Float?,
    gattRearFirmware: String?,
    gattFrontFirmware: String?,
    gattShifterRightFirmware: String?,
    gattShifterLeftFirmware: String?,
    blockParameters: Map<String, String>,
    blockStatusText: String?,
    onMonitoredChange: (Boolean) -> Unit
) {
    val containerColor = if (isMonitored) Color(0x3F45DDA0) else Color(0x3FE53935)
    val borderColor = if (isMonitored) Color(0x8045DDA0) else Color(0x80D32F2F)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = deviceName,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color(0xFF1E1B2E)
            )
            Text(
                text = statusText,
                fontSize = 12.sp,
                color = Color(0xFF5E546E)
            )

            if (isMonitored) {
                Spacer(modifier = Modifier.height(10.dp))

                // Rear Derailleur present on ALL models
                VoltageLine(label = "Rear Derailleur", volts = gattRearVolts, firmware = gattRearFirmware)

                if (!isOxModel && !isGxModel) {
                    // Front Derailleur present on EDS TX only
                    VoltageLine(label = "Front Derailleur", volts = gattFrontVolts, firmware = gattFrontFirmware)
                }

                if (isOxModel) {
                    // EDS OX has 1 Remote Shifter
                    VoltageLine(label = "Remote Shifter", volts = gattShifterLeftVolts, firmware = gattShifterLeftFirmware)
                } else {
                    // EDS TX and GX have 2 Shifters
                    VoltageLine(label = "Shifter Right", volts = gattShifterRightVolts, firmware = gattShifterRightFirmware)
                    VoltageLine(label = "Shifter Left", volts = gattShifterLeftVolts, firmware = gattShifterLeftFirmware)
                }

                // --- BLOCK 0..4 TELEMETRY & PARAMETERS ---
                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp),
                    color = Color(0x301E1B2E)
                ) {}

                Spacer(modifier = Modifier.height(8.dp))

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Block 0..4 Telemetry",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color(0xFF1E1B2E)
                    )

                    if (!blockStatusText.isNullOrBlank()) {
                        Text(
                            text = blockStatusText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF006644),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (blockParameters.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x1A000000), shape = RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        blockParameters.forEach { (key, value) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = key,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF332F4C),
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = value,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1E1B2E),
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(1.2f)
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Waiting for Block 0..4 response...",
                        fontSize = 11.sp,
                        color = Color(0xFF777777)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Slide to connect",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1E1B2E)
                )

                Switch(
                    checked = isMonitored,
                    onCheckedChange = onMonitoredChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF007A5E),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFB00020)
                    )
                )
            }
        }
    }
}

@Composable
private fun VoltageLine(label: String, volts: Float?, firmware: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = Color(0xFF333333)
            )
            Text(
                text = if (volts != null) "%.2f V".format(Locale.US, volts) else "-- V",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    volts == null -> Color(0xFF777777)
                    volts >= 7.2f -> Color(0xFF006644)
                    volts >= 2.7f -> Color(0xFF006644)
                    else -> Color(0xFFB91C1C)
                }
            )
        }
        if (firmware != null) {
            Text(
                text = "FW: v$firmware",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF5E546E),
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
