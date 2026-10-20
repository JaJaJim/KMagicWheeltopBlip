package io.github.JaJaJim.kmagicwheeltopblip

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.JaJaJim.kmagicwheeltopblip.ble.BleDeviceItem
import io.github.JaJaJim.kmagicwheeltopblip.ble.MagicshineRemoteManager
import io.github.JaJaJim.kmagicwheeltopblip.ble.WheeltopManager
import java.text.SimpleDateFormat
import java.util.Date
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
                    color = Color(0xFFE6DAF0)
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
    val boundMac by wheeltopManager.boundMacAddress.collectAsState()
    val boundName by wheeltopManager.boundDeviceName.collectAsState()
    val isScanningDerailleurs by wheeltopManager.isScanningDerailleurs.collectAsState()
    val scannedDerailleurs by wheeltopManager.scannedDerailleurs.collectAsState()
    val isPullingGattBlocks by wheeltopManager.isPullingGattBlocks.collectAsState()
    val lastGattAsciiText by wheeltopManager.lastGattAsciiText.collectAsState()
    val lastGattTimestamp by wheeltopManager.lastGattTimestamp.collectAsState()
    val blockStatusText by wheeltopManager.blockStatusText.collectAsState()

    val boundRemoteMac by remoteManager.boundRemoteMac.collectAsState()
    val boundRemoteName by remoteManager.boundRemoteName.collectAsState()
    val isScanningRemotes by remoteManager.isScanningRemotes.collectAsState()
    val scannedRemotes by remoteManager.scannedRemotes.collectAsState()
    val lastClickText by remoteManager.lastClickText.collectAsState()
    val doubleClickMode by remoteManager.doubleClickMode.collectAsState()

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(14.dp)
    ) {
        // Header
        Text(
            text = "KMagicWheeltopBlip",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1E1B2E)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "v0.0.3 • Ham 🐵🚀 Release",
                fontSize = 12.sp,
                color = Color(0xFF5E546E)
            )

            Image(
                painter = painterResource(id = R.drawable.ic_logo),
                contentDescription = "Logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // --- SECTION 1: WHEELTOP EDS DERAILLEUR ---
        Text(
            text = "Wheeltop EDS Derailleur",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1E1B2E),
            modifier = Modifier.padding(vertical = 4.dp)
        )

        Button(
            onClick = { wheeltopManager.startScanForWheeltopDevices() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007A5E)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = if (isScanningDerailleurs) "Searching EDS..." else "Search for EDS",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        val derailleurList = when {
            !boundMac.isNullOrBlank() -> listOf(
                BleDeviceItem(
                    name = boundName ?: "EDS TX",
                    address = boundMac!!,
                    rssi = 0
                )
            )
            isScanningDerailleurs || scannedDerailleurs.isNotEmpty() -> scannedDerailleurs
            else -> emptyList()
        }

        if (derailleurList.isEmpty()) {
            Text(
                text = "No WheelTop derailleur bound. Click 'Search for EDS' to discover nearby derailleurs.",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF5E546E),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
            )
        } else {
            derailleurList.forEach { item ->
                val isBound = boundMac.equals(item.address, ignoreCase = true)
                DerailleurCard(
                    deviceName = item.name,
                    deviceMac = item.address,
                    isBound = isBound,
                    isPullingBlocks = isPullingGattBlocks,
                    blockStatusText = if (isBound) blockStatusText else null,
                    lastAsciiText = if (isBound) lastGattAsciiText else null,
                    lastTimestamp = if (isBound) lastGattTimestamp else 0L,
                    onBindChange = { enabled ->
                        if (enabled) {
                            wheeltopManager.bindDevice(item.address, item.name)
                        } else {
                            wheeltopManager.unbindDevice()
                        }
                    },
                    onPullBlocks = {
                        wheeltopManager.pullGattBlocks()
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // --- SECTION 2: MAGICSHINE REMOTE ---
        Text(
            text = "Magicshine Remote",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1E1B2E),
            modifier = Modifier.padding(vertical = 4.dp)
        )

        Button(
            onClick = { remoteManager.startScanForRemotes() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007A5E)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = if (isScanningRemotes) "Searching Blip..." else "Search for Blip",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        val remoteList = when {
            !boundRemoteMac.isNullOrBlank() -> listOf(
                BleDeviceItem(
                    name = boundRemoteName ?: "M0-C0 MJ_6558",
                    address = boundRemoteMac!!,
                    rssi = 0
                )
            )
            isScanningRemotes || scannedRemotes.isNotEmpty() -> scannedRemotes
            else -> emptyList()
        }

        if (remoteList.isEmpty()) {
            Text(
                text = "No Magicshine remote bound. Click 'Search for Blip' to discover nearby remotes.",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF5E546E),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
            )
        } else {
            remoteList.forEach { remoteItem ->
                val isThisRemoteBound = boundRemoteMac.equals(remoteItem.address, ignoreCase = true)
                RemoteCard(
                    deviceName = remoteItem.name,
                    deviceMac = remoteItem.address,
                    isBound = isThisRemoteBound,
                    lastClickText = if (isThisRemoteBound) lastClickText else null,
                    doubleClickMode = doubleClickMode,
                    onDoubleClickModeChange = { mode ->
                        remoteManager.setDoubleClickMode(mode)
                    },
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
private fun DerailleurCard(
    deviceName: String,
    deviceMac: String,
    isBound: Boolean,
    isPullingBlocks: Boolean,
    blockStatusText: String?,
    lastAsciiText: String?,
    lastTimestamp: Long,
    onBindChange: (Boolean) -> Unit,
    onPullBlocks: () -> Unit
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
            Text(
                text = deviceName,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color(0xFF1E1B2E)
            )
            Text(
                text = deviceMac,
                fontSize = 11.sp,
                color = Color(0xFF5E546E)
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (isBound) {
                Button(
                    onClick = onPullBlocks,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007A5E)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (isPullingBlocks) "Pulling GATT Blocks..." else "Pull GATT Blocks",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                val liveStatus = if (!blockStatusText.isNullOrBlank()) blockStatusText else "Ready for Pull"
                Text(
                    text = "Status: $liveStatus",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF006644)
                )

                if (lastTimestamp > 0L) {
                    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(lastTimestamp))
                    Text(
                        text = "Last Pull: $dateStr",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF5E546E)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0x1A000000),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (!lastAsciiText.isNullOrBlank()) lastAsciiText else "No GATT data pulled yet. Click 'Pull GATT Blocks'.",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF1E1B2E),
                        modifier = Modifier.padding(8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isBound) "Slide to unbind" else "Slide to bind",
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
private fun RemoteCard(
    deviceName: String,
    deviceMac: String,
    isBound: Boolean,
    lastClickText: String?,
    doubleClickMode: String,
    onDoubleClickModeChange: (String) -> Unit,
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
                        text = deviceMac,
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

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Double-Click Mode:",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E1B2E)
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = "Note: The remote automatically detects keypresses under ~500ms as double-clicks at the hardware level and emits dedicated double-click events.",
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF5E546E),
                modifier = Modifier.padding(horizontal = 2.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            DoubleClickOptionRow(
                title = "1. Shift Front Derailleur (FD Toggle)",
                description = "Double-click toggles chainrings (Big / Small)",
                isSelected = doubleClickMode == "FD_TOGGLE",
                onSelect = { onDoubleClickModeChange("FD_TOGGLE") }
            )

            Spacer(modifier = Modifier.height(4.dp))

            DoubleClickOptionRow(
                title = "2. Disable Double-Click",
                description = "No double-click action (protects Front Derailleur)",
                isSelected = doubleClickMode == "DISABLED",
                onSelect = { onDoubleClickModeChange("DISABLED") }
            )

            Spacer(modifier = Modifier.height(4.dp))

            DoubleClickOptionRow(
                title = "3. Shift 2 Gears (250ms Burst)",
                description = "□ 2x Up (+2 gears) / ○ 2x Down (-2 gears)",
                isSelected = doubleClickMode == "BURST_SHIFT",
                onSelect = { onDoubleClickModeChange("BURST_SHIFT") }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isBound) "Slide to unbind" else "Slide to bind",
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
private fun DoubleClickOptionRow(
    title: String,
    description: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0x33007A5E) else Color(0x1A000000)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) Color(0xFF007A5E) else Color(0x20000000)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E1B2E)
                )
                Text(
                    text = description,
                    fontSize = 10.sp,
                    color = Color(0xFF5E546E)
                )
            }

            Switch(
                checked = isSelected,
                onCheckedChange = { if (it) onSelect() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF007A5E),
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFF777777)
                )
            )
        }
    }
}
