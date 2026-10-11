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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.JaJaJim.karooedscharge.ble.BleDeviceItem
import io.github.JaJaJim.karooedscharge.ble.ConnectionState
import io.github.JaJaJim.karooedscharge.ble.WheeltopManager
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var wheeltopManager: WheeltopManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            wheeltopManager.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wheeltopManager = WheeltopManager.getInstance(applicationContext)

        // Automatically check and request Bluetooth permissions on startup
        checkPermissionsAndScan()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFE6DAF0) // Light soft violet background
                ) {
                    EdsChargeLightScreen(wheeltopManager = wheeltopManager)
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
        } else {
            wheeltopManager.startScan()
        }
    }
}

@Composable
fun EdsChargeLightScreen(wheeltopManager: WheeltopManager) {
    val connectionState by wheeltopManager.connectionState.collectAsState()
    val boundMac by wheeltopManager.boundMacAddress.collectAsState()
    val boundName by wheeltopManager.boundDeviceName.collectAsState()
    val scannedDevices by wheeltopManager.scannedDevices.collectAsState()
    val isMonitored by wheeltopManager.isMonitored.collectAsState()
    val isReScanning by wheeltopManager.isReScanning.collectAsState()

    val rearVolts by wheeltopManager.batteryRearVolts.collectAsState()
    val frontVolts by wheeltopManager.batteryFrontVolts.collectAsState()
    val shifterRightVolts by wheeltopManager.batteryShifterRightVolts.collectAsState()
    val shifterLeftVolts by wheeltopManager.batteryShifterLeftVolts.collectAsState()

    val isConnected = (connectionState == ConnectionState.CONNECTED)
    val statusText = if (isConnected) "BLE connected" else "BLE disconnected"

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
                        text = "KarooEDSCharge",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E1B2E)
                    )
                    Text(
                        text = "Wheeltop EDS XT Battery Monitor",
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
                text = "Connected Gear",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E1B2E),
                modifier = Modifier.padding(vertical = 6.dp)
            )

            // Devices list or bound device
            val devicesToDisplay: List<BleDeviceItem> = when {
                !boundMac.isNullOrBlank() -> listOf(
                    BleDeviceItem(
                        name = boundName ?: "Wheeltop EDS XT",
                        address = boundMac!!,
                        rssi = 0
                    )
                )
                scannedDevices.isNotEmpty() -> scannedDevices
                else -> emptyList()
            }

            if (devicesToDisplay.isNotEmpty()) {
                devicesToDisplay.forEach { device ->
                    GearCard(
                        deviceName = device.name,
                        statusText = statusText,
                        isMonitored = isMonitored,
                        frontVolts = frontVolts,
                        rearVolts = rearVolts,
                        shifterRightVolts = shifterRightVolts,
                        shifterLeftVolts = shifterLeftVolts,
                        onMonitoredChange = { enabled ->
                            if (boundMac.isNullOrBlank()) {
                                wheeltopManager.bindDevice(device.address, device.name)
                            } else {
                                wheeltopManager.toggleMonitoring(enabled)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            } else {
                // Empty state card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0x3FE53935)),
                    border = BorderStroke(1.dp, Color(0x80D32F2F)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Search for shifting gear",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color(0xFF1E1B2E)
                        )
                        Text(
                            text = statusText,
                            fontSize = 12.sp,
                            color = Color(0xFF5E546E)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bottom Re-Scan Control Box
        ReScanBox(
            isReScanning = isReScanning,
            onReScanClick = { wheeltopManager.triggerReScan() }
        )
    }
}

@Composable
private fun GearCard(
    deviceName: String,
    statusText: String,
    isMonitored: Boolean,
    frontVolts: Float?,
    rearVolts: Float?,
    shifterRightVolts: Float?,
    shifterLeftVolts: Float?,
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

            // Line-by-line voltage details when monitored
            if (isMonitored) {
                Spacer(modifier = Modifier.height(10.dp))

                VoltageLine(label = "Front Derailleur", volts = frontVolts)
                VoltageLine(label = "Rear Derailleur", volts = rearVolts)
                VoltageLine(label = "Shifter Right", volts = shifterRightVolts)
                VoltageLine(label = "Shifter Left", volts = shifterLeftVolts)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Slide to monitor",
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
private fun VoltageLine(label: String, volts: Float?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
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
                volts >= 6.8f -> Color(0xFFB45309)
                else -> Color(0xFFB91C1C)
            }
        )
    }
}

@Composable
private fun ReScanBox(
    isReScanning: Boolean,
    onReScanClick: () -> Unit
) {
    val containerColor = if (isReScanning) Color(0x3F45DDA0) else Color(0x3FE53935)
    val borderColor = if (isReScanning) Color(0x8045DDA0) else Color(0x80D32F2F)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onClickClickable(onReScanClick),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isReScanning) "Searching..." else "Re-Scan",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = Color(0xFF1E1B2E)
            )

            if (isReScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color(0xFF007A5E),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

private fun Modifier.onClickClickable(onClick: () -> Unit) = this.then(
    Modifier.clickable { onClick() }
)
