package io.github.JaJaJim.karooedscharge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.JaJaJim.karooedscharge.ble.BleDeviceItem
import io.github.JaJaJim.karooedscharge.ble.ConnectionState
import io.github.JaJaJim.karooedscharge.ble.DiagnosticLogEntry
import io.github.JaJaJim.karooedscharge.ble.WheeltopManager
import java.text.SimpleDateFormat
import java.util.Date
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
        super.onCreate()
        wheeltopManager = WheeltopManager.getInstance(applicationContext)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A)
                ) {
                    EdsChargeScreen(
                        wheeltopManager = wheeltopManager,
                        onRequestPermissionsAndScan = { checkPermissionsAndScan() }
                    )
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
fun EdsChargeScreen(
    wheeltopManager: WheeltopManager,
    onRequestPermissionsAndScan: () -> Unit
) {
    val connectionState by wheeltopManager.connectionState.collectAsState()
    val boundMac by wheeltopManager.boundMacAddress.collectAsState()
    val boundName by wheeltopManager.boundDeviceName.collectAsState()
    val scannedDevices by wheeltopManager.scannedDevices.collectAsState()
    val isAuth by wheeltopManager.isAuthenticatedState.collectAsState()

    val rearVolts by wheeltopManager.batteryRearVolts.collectAsState()
    val frontVolts by wheeltopManager.batteryFrontVolts.collectAsState()
    val shifterVolts by wheeltopManager.batteryShifterVolts.collectAsState()

    val diagnosticLogs by wheeltopManager.diagnosticLogs.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text(
            text = "⚡ KarooEDSCharge",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Wheeltop EDS Akku-Spannungsüberwachung",
            fontSize = 12.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Battery Voltages Display Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "LIVE AKKU-SPANNUNGEN (GATT)",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BatteryChip(label = "Schaltwerk (H)", volts = rearVolts)
                    BatteryChip(label = "Umwerfer (V)", volts = frontVolts)
                    BatteryChip(label = "Hebel / Blips", volts = shifterVolts)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Connection Status Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isAuth -> Color(0xFF065F46)
                    connectionState == ConnectionState.CONNECTING -> Color(0xFF92400E)
                    else -> Color(0xFF1E293B)
                }
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = if (!boundName.isNullOrBlank()) "Schaltung: $boundName" else "Keine Schaltung gebunden",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Text(
                        text = when {
                            isAuth -> "🟢 AUTHENTIFIZIERT & BEREIT"
                            connectionState == ConnectionState.CONNECTING -> "🟡 VERBINDE GATT..."
                            else -> "🔴 GETRENNT"
                        },
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                }

                if (!boundMac.isNullOrBlank()) {
                    Button(
                        onClick = { wheeltopManager.unbindDevice() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Entbinden", fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Pairing & Search Buttons
        if (boundMac.isNullOrBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onRequestPermissionsAndScan,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Schaltung Suchen")
                }

                if (connectionState == ConnectionState.SCANNING) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(24.dp).height(24.dp),
                        color = Color(0xFF38BDF8),
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Color(0xFF0F172A), shape = RoundedCornerShape(8.dp))
            ) {
                items(scannedDevices) { device ->
                    ScannedDeviceRow(device = device, onBind = {
                        wheeltopManager.bindDevice(device.address, device.name)
                    })
                    HorizontalDivider(color = Color(0xFF334155))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        // Live Diagnostic Log Console
        Text(
            text = "LIVE GLOBAER DIAGNOSE-LOG",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF020617), shape = RoundedCornerShape(8.dp))
                .padding(6.dp)
        ) {
            items(diagnosticLogs) { log ->
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
                Text(
                    text = "[$timeStr] ${log.message}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = when {
                        log.isError -> Color(0xFFF87171)
                        log.isSuccess -> Color(0xFF4ADE80)
                        else -> Color(0xFF94A3B8)
                    }
                )
            }
        }
    }
}

@Composable
private fun BatteryChip(label: String, volts: Float?) {
    Box(
        modifier = Modifier
            .background(Color(0xFF0F172A), shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, fontSize = 10.sp, color = Color.Gray)
            Text(
                text = if (volts != null) "%.2f V".format(Locale.US, volts) else "-- V",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    volts == null -> Color.Gray
                    volts >= 7.2f -> Color(0xFF4ADE80)
                    volts >= 6.8f -> Color(0xFFF59E0B)
                    else -> Color(0xFFEF4444)
                }
            )
        }
    }
}

@Composable
private fun ScannedDeviceRow(device: BleDeviceItem, onBind: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onBind() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = device.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
            Text(text = device.address, fontSize = 11.sp, color = Color.Gray)
        }
        Button(
            onClick = onBind,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text("Binden", fontSize = 11.sp)
        }
    }
}
