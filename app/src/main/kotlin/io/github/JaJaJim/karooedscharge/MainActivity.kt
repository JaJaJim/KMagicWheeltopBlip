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
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wheeltopManager = WheeltopManager.getInstance(applicationContext)

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

    val gattRearVolts by wheeltopManager.gattRearVolts.collectAsState()
    val gattFrontVolts by wheeltopManager.gattFrontVolts.collectAsState()
    val gattShifterRightVolts by wheeltopManager.gattShifterRightVolts.collectAsState()
    val gattShifterLeftVolts by wheeltopManager.gattShifterLeftVolts.collectAsState()

    val gattRearFirmware by wheeltopManager.gattRearFirmware.collectAsState()
    val gattFrontFirmware by wheeltopManager.gattFrontFirmware.collectAsState()
    val gattShifterRightFirmware by wheeltopManager.gattShifterRightFirmware.collectAsState()
    val gattShifterLeftFirmware by wheeltopManager.gattShifterLeftFirmware.collectAsState()

    val statusText = when {
        !isMonitored -> "BLE Disconnected"
        connectionState == ConnectionState.CONNECTED -> "GATT Connected"
        connectionState == ConnectionState.CONNECTING -> "Connecting GATT..."
        else -> "BLE Disconnected"
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
                text = "Master GATT Gear",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E1B2E),
                modifier = Modifier.padding(vertical = 6.dp)
            )

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
                    GattMasterCard(
                        deviceName = device.name,
                        statusText = statusText,
                        isMonitored = isMonitored,
                        gattRearVolts = gattRearVolts,
                        gattFrontVolts = gattFrontVolts,
                        gattShifterRightVolts = gattShifterRightVolts,
                        gattShifterLeftVolts = gattShifterLeftVolts,
                        gattRearFirmware = gattRearFirmware,
                        gattFrontFirmware = gattFrontFirmware,
                        gattShifterRightFirmware = gattShifterRightFirmware,
                        gattShifterLeftFirmware = gattShifterLeftFirmware,
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
            }
        }
    }
}

@Composable
private fun GattMasterCard(
    deviceName: String,
    statusText: String,
    isMonitored: Boolean,
    gattRearVolts: Float?,
    gattFrontVolts: Float?,
    gattShifterRightVolts: Float?,
    gattShifterLeftVolts: Float?,
    gattRearFirmware: String?,
    gattFrontFirmware: String?,
    gattShifterRightFirmware: String?,
    gattShifterLeftFirmware: String?,
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

                VoltageLine(label = "Rear Derailleur", volts = gattRearVolts, firmware = gattRearFirmware)
                VoltageLine(label = "Front Derailleur", volts = gattFrontVolts, firmware = gattFrontFirmware)
                VoltageLine(label = "Shifter Right", volts = gattShifterRightVolts, firmware = gattShifterRightFirmware)
                VoltageLine(label = "Shifter Left", volts = gattShifterLeftVolts, firmware = gattShifterLeftFirmware)
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
