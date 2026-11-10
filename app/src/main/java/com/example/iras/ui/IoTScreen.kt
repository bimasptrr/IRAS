package com.example.iras.ui.screens

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.iras.ble.BleDevice
import com.example.iras.ble.BleManager
import com.example.iras.ble.BleState
import com.example.iras.ble.ImuData
import com.example.iras.ble.MovementEvent
import com.example.iras.ble.WindData
import com.example.iras.ble.WindEvent
import com.example.iras.ble.RawPacket
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt
import kotlin.random.Random

// ---------- Warna ----------
private val NavyDark = Color(0xFF031A3D)
private val NavyLight = Color(0xFF0A2E66)
private val ScreenBg = Color(0xFFF4F6FA)
private val GoodColor = Color(0xFF22C55E)
private val WarnColor = Color(0xFFEF6C00)
private val BadColor = Color(0xFFEF4444)
private val BlueValue = Color(0xFF3B82F6)
private val GreyText = Color(0xFF6B7280)
private val ChipGrey = Color(0xFFF1F3F7)

private val OffGradient = Brush.linearGradient(listOf(Color(0xFF4A0E0E), Color(0xFF7A1F1F)))
private val OnGradient = Brush.linearGradient(listOf(Color(0xFF062E24), Color(0xFF0F6B4B)))
private val HeaderGradient = Brush.horizontalGradient(listOf(NavyDark, NavyLight))

/** Menjaga selisih sudut tetap di rentang -180..180 derajat. */
private fun wrapDegrees(angle: Float): Float {
    var a = angle
    while (a > 180f) a -= 360f
    while (a < -180f) a += 360f
    return a
}


@Composable
fun IoTScreen(
    ble: BleManager,
    bleWind: BleManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bleState by ble.state.collectAsState()
    val devices by ble.devices.collectAsState()
    val imu by ble.imu.collectAsState()
    val packetCount by ble.packetCount.collectAsState()
    val status by ble.statusText.collectAsState()
    val movementHistory by ble.movementHistory.collectAsState()
    val movementCount by ble.movementCount.collectAsState()
    val latestImu by rememberUpdatedState(imu)

    // Node sensor angin: koneksi BLE kedua, terpisah dari wearable IMU
    val windState by bleWind.state.collectAsState()
    val windDevices by bleWind.devices.collectAsState()
    val wind by bleWind.wind.collectAsState()
    val windHistory by bleWind.windHistory.collectAsState()
    val windMax by bleWind.windMax.collectAsState()
    val windConnected = windState as? BleState.Connected
    val windConnecting = windState is BleState.Connecting
    val windOn = windConnected != null && wind != null

    val connected = bleState as? BleState.Connected
    val isConnecting = bleState is BleState.Connecting

    // Status sensor IMU: hijau hanya jika sudah ada paket x,y,z yang berhasil dibaca dari ESP32 mana pun
    val imuOn = connected != null && (imu != null || status != null)

    var showScanner by remember { mutableStateOf(false) }
    var scanTarget by remember { mutableIntStateOf(0) }  // 0 = IMU, 1 = angin
    var imuBattery by remember { mutableStateOf<Int?>(null) }  // null = belum ada data dari ESP32

    var roll by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(0f) }
    var magnitude by remember { mutableFloatStateOf(0f) }
    var handDirection by remember { mutableStateOf("-") }
    var angleOnly by remember { mutableStateOf(false) }  // true = ESP32 hanya mengirim 2 sudut

    // ---------- Izin & pembukaan pemindai ----------
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun scanner() = if (scanTarget == 0) ble else bleWind

    fun openScanner() {
        showScanner = true
        scanner().startScan()
    }

    val enableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            showScanner = true
            scanner().startScan()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            if (ble.isBluetoothEnabled) {
                showScanner = true
                scanner().startScan()
            } else {
                enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        } else {
            Toast.makeText(context, "Izin Bluetooth diperlukan untuk mencari perangkat", Toast.LENGTH_LONG).show()
        }
    }

    fun startFlow(target: Int) {
        scanTarget = target
        when {
            !hasPermissions() -> permissionLauncher.launch(permissions)
            !ble.isBluetoothEnabled -> enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> openScanner()
        }
    }

    // ---------- Data IMU sungguhan dari ESP32 (diperbarui tiap 0,5 detik) ----------
    LaunchedEffect(imuOn) {
        if (!imuOn) {
            roll = 0f
            pitch = 0f
            magnitude = 0f
            handDirection = "-"
            angleOnly = false
            imuBattery = null
            return@LaunchedEffect
        }
        // Posisi awal saat terhubung dipakai sebagai acuan arah gerak
        var baseRoll: Float? = null
        var basePitch: Float? = null
        while (true) {
            delay(500)
            val d = latestImu ?: continue
            angleOnly = !d.hasAccel
            if (d.hasAccel) magnitude = d.accelMagnitudeG
            // Arah tangan butuh roll & pitch; paket x,y,z saja tidak memuatnya
            if (d.hasOrientation) {
                if (d.hasAccel) {
                    // Acuan nol = posisi saat pertama terhubung
                    val br = baseRoll ?: d.roll.also { baseRoll = it }
                    val bp = basePitch ?: d.pitch.also { basePitch = it }
                    roll = wrapDegrees(d.roll - br)
                    pitch = wrapDegrees(d.pitch - bp)
                } else {
                    // Paket 2 sudut: ESP32 sendiri yang mengatur titik nol (tombol atau perintah reset)
                    roll = wrapDegrees(d.roll)
                    pitch = wrapDegrees(d.pitch)
                    magnitude = sqrt(roll * roll + pitch * pitch)
                }
                val horizontal = if (roll < 0) "Kiri" else "Kanan"
                val vertical = if (pitch < 0) "Bawah" else "Atas"
                handDirection = "$horizontal $vertical"
            } else {
                handDirection = "-"
            }
            if (d.battery >= 0) imuBattery = d.battery
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ScreenBg)
    ) {
        IrasHeader(imuOn = imuOn, windOn = windOn)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DeviceCard(
                title = "Sensor IMU BNO055",
                subtitle = "Perangkat Wearable Pergelangan",
                icon = Icons.Filled.Bluetooth,
                isOn = imuOn,
                statusLabel = when {
                    imuOn -> "Terhubung"
                    connected != null && packetCount > 0 -> "Terhubung, format data tidak dikenali"
                    connected != null -> "Terhubung, menunggu data..."
                    isConnecting -> "Menyambung..."
                    bleState is BleState.Scanning -> "Mencari..."
                    else -> "Terputus"
                },
                buttonLabel = if (connected != null || isConnecting) "PUTUSKAN" else "CARI",
                onToggle = {
                    if (connected != null || isConnecting) ble.disconnect() else startFlow(0)
                }
            )

            (bleState as? BleState.Error)?.let { error ->
                Text(
                    error.message,
                    fontSize = 12.sp,
                    color = BadColor,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            DeviceCard(
                title = "Stasiun Sensor Angin",
                subtitle = "Node ESP32 IRAS-WindNode",
                icon = Icons.Filled.Air,
                isOn = windOn,
                statusLabel = when {
                    windOn -> "Terhubung"
                    windConnected != null -> "Terhubung, menunggu data..."
                    windConnecting -> "Menyambung..."
                    windState is BleState.Scanning -> "Mencari..."
                    else -> "Terputus"
                },
                buttonLabel = if (windConnected != null || windConnecting) "PUTUSKAN" else "CARI",
                onToggle = {
                    if (windConnected != null || windConnecting) bleWind.disconnect() else startFlow(1)
                }
            )

            (windState as? BleState.Error)?.let { error ->
                Text(
                    error.message,
                    fontSize = 12.sp,
                    color = BadColor,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            if (windConnected != null) {
                WindCard(wind = wind, history = windHistory, maxSpeed = windMax)
            }

            BatteryCard(
                imuBattery = imuBattery,
                imuOn = imuOn,
                windBattery = null,   // firmware node angin belum mengirim level baterai
                windOn = windOn
            )

            MovementHistoryCard(
                connected = connected != null,
                status = status,
                history = movementHistory,
                movementCount = movementCount,
                hasAngle = imuOn && imu != null,
                direction = if (imuOn && imu?.hasOrientation == true) handDirection else "--",
                magnitude = when {
                    imuOn && angleOnly -> "${"%.1f".format(magnitude)}°"
                    imuOn && magnitude > 0f -> "${"%.1f".format(magnitude)} g"
                    else -> "-- g"
                }
            )
        }
    }

    // ---------- Dialog pemindai Bluetooth ----------
    if (showScanner) {
        val activeBle = if (scanTarget == 0) ble else bleWind
        val activeState = if (scanTarget == 0) bleState else windState
        // Perangkat yang sudah dipakai koneksi lain tidak ditawarkan lagi
        val otherAddress = (if (scanTarget == 0) windConnected?.address else connected?.address)
        val activeDevices = (if (scanTarget == 0) devices else windDevices)
            .filter { it.address != otherAddress }
        ScannerDialog(
            title = if (scanTarget == 0) "Pilih wearable IMU" else "Pilih node sensor angin",
            scanning = activeState is BleState.Scanning,
            devices = activeDevices,
            onRescan = { activeBle.startScan() },
            onSelect = { device ->
                showScanner = false
                activeBle.connect(device.address)
            },
            onDismiss = {
                showScanner = false
                activeBle.stopScan()
            }
        )
    }
}

// ---------- Dialog daftar perangkat ----------
@Composable
private fun ScannerDialog(
    title: String,
    scanning: Boolean,
    devices: List<BleDevice>,
    onRescan: () -> Unit,
    onSelect: (BleDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        },
        text = {
            if (devices.isEmpty()) {
                Text(
                    if (scanning) "Mencari perangkat di sekitar..." else "Tidak ada perangkat ditemukan.",
                    fontSize = 13.sp,
                    color = GreyText
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices, key = { it.address }) { device ->
                        DeviceRow(device = device, onClick = { onSelect(device) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRescan, enabled = !scanning) { Text("Pindai Ulang") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Tutup") }
        }
    )
}

@Composable
private fun DeviceRow(device: BleDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (device.isIras) Color(0xFFE8F7EE) else ChipGrey)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Bluetooth,
            contentDescription = null,
            tint = if (device.isIras) GoodColor else GreyText,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                device.name ?: "Tanpa nama",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111827)
            )
            Text(device.address, fontSize = 11.sp, color = GreyText)
        }
        if (device.isIras) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(GoodColor.copy(alpha = 0.18f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("IRAS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF15803D))
            }
            Spacer(Modifier.width(8.dp))
        }
        Text("${device.rssi} dBm", fontSize = 11.sp, color = GreyText)
    }
}

// ---------- Header ----------
@Composable
private fun IrasHeader(imuOn: Boolean, windOn: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HeaderGradient)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(12.dp))
        Text(
            "IRAS",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 3.sp,
            modifier = Modifier.weight(1f)
        )
        StatusChip("IMU", imuOn)
        Spacer(Modifier.width(8.dp))
        StatusChip("WIND", windOn)
    }
}

@Composable
private fun StatusChip(label: String, active: Boolean) {
    val dot = if (active) GoodColor else BadColor
    val bg = if (active) GoodColor.copy(alpha = 0.18f) else BadColor.copy(alpha = 0.18f)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(dot, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = dot, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- Kartu perangkat ----------
@Composable
private fun DeviceCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isOn: Boolean,
    statusLabel: String,
    buttonLabel: String,
    onToggle: () -> Unit
) {
    val statusColor = if (isOn) Color(0xFF6EE7A8) else Color(0xFFFF6B6B)
    val subtitleColor = if (isOn) Color(0xFFA7F3D0) else Color(0xFFF2A9A0)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(if (isOn) OnGradient else OffGradient)
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Kotak ikon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = statusColor, modifier = Modifier.size(26.dp))
            }

            // Teks tengah
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    subtitle,
                    color = subtitleColor,
                    fontSize = 11.sp
                )
                Text(
                    "● $statusLabel",
                    color = statusColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Tombol aksi
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    buttonLabel,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ---------- Kartu baterai ----------
@Composable
private fun BatteryCard(
    imuBattery: Int?,
    imuOn: Boolean,
    windBattery: Int?,
    windOn: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, Color(0xFFE5E7EB))
    ) {
        Column {
            // Header navy
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HeaderGradient)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.BatteryFull,
                    contentDescription = null,
                    tint = Color(0xFF93C5FD),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "STATUS MONITOR BATTERY IOT",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = Color(0xFFCBD5E1),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "LIVE",
                        color = Color(0xFFCBD5E1),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                BatteryRow("Baterai Wearable LiPo 3.7V", imuBattery, imuOn)
                BatteryRow("Baterai Stasiun Angin 18650 3S", windBattery, windOn)
            }
        }
    }
}

@Composable
private fun BatteryRow(name: String, level: Int?, active: Boolean) {
    // level null = belum ada data baterai dari perangkat
    val shown = if (active) level else null
    val color = when {
        shown == null -> GreyText
        shown < 20 -> BadColor
        shown < 50 -> WarnColor
        else -> GoodColor
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.BatteryFull,
                contentDescription = null,
                tint = GreyText,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2937),
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ChipGrey)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    if (shown != null) "$shown%" else "--%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
        }
        LinearProgressIndicator(
            progress = { if (shown != null) shown / 100f else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = color,
            trackColor = Color(0xFFE9EDF3)
        )
        if (shown != null && shown < 20) {
            Text("Baterai rendah, segera isi daya.", fontSize = 11.sp, color = BadColor)
        }
    }
}

// ---------- Kondisi angin ----------
@Composable
private fun WindCard(wind: WindData?, history: List<WindEvent>, maxSpeed: Float) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FB)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Color(0xFFE5E7EB))
    ) {
        Column {
            Text(
                "KONDISI ANGIN",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = GreyText
            )
            HorizontalDivider(color = Color(0xFFE5E7EB))

            HistoryRow(
                label = "Kecepatan Angin",
                value = wind?.let { "${"%.2f".format(it.speed)} m/s" } ?: "--",
                valueColor = if (wind != null) BlueValue else GreyText,
                showCheck = false,
                subValue = wind?.let { "${"%.1f".format(it.speed * 3.6f)} km/jam" }
            )
            HorizontalDivider(color = Color(0xFFE5E7EB))
            HistoryRow(
                label = "Kategori",
                value = wind?.category ?: "--",
                valueColor = if (wind != null) GoodColor else GreyText,
                showCheck = false
            )
            HorizontalDivider(color = Color(0xFFE5E7EB))
            HistoryRow(
                label = "Arah Angin",
                value = when {
                    wind == null -> "--"
                    wind.directionOk -> wind.direction
                    else -> "Sensor arah error"
                },
                valueColor = when {
                    wind == null -> GreyText
                    wind.directionOk -> BlueValue
                    else -> BadColor
                },
                showCheck = false,
                subValue = if (wind != null && wind.directionOk) "${"%.1f".format(wind.degrees)}°" else null
            )
            HorizontalDivider(color = Color(0xFFE5E7EB))
            HistoryRow(
                label = "Kecepatan Maksimum",
                value = if (wind != null) "${"%.2f".format(maxSpeed)} m/s" else "--",
                valueColor = if (wind != null) WarnColor else GreyText,
                showCheck = false
            )

            HorizontalDivider(color = Color(0xFFE5E7EB))
            Text(
                "RIWAYAT PERUBAHAN ANGIN",
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = GreyText
            )
            if (history.isEmpty()) {
                Text(
                    "Menunggu data dari node angin...",
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    fontSize = 13.sp,
                    color = GreyText
                )
            } else {
                history.take(10).forEach { e ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(BlueValue)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                e.category,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF111827)
                            )
                            Text(
                                "${e.direction} · ${"%.2f".format(e.speed)} m/s",
                                fontSize = 12.sp,
                                color = GreyText
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            timeFormat.format(Date(e.timestamp)),
                            fontSize = 12.sp,
                            color = GreyText
                        )
                    }
                }
                if (history.size > 10) {
                    Text(
                        "${history.size - 10} perubahan lebih lama disimpan (maks. 50).",
                        modifier = Modifier.padding(start = 20.dp, top = 4.dp),
                        fontSize = 11.sp,
                        color = GreyText
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

// ---------- Histori gerakan ----------
private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale("id", "ID"))

@Composable
private fun MovementHistoryCard(
    connected: Boolean,
    status: String?,
    history: List<MovementEvent>,
    movementCount: Int,
    hasAngle: Boolean,
    direction: String,
    magnitude: String
) {
    val still = status?.trim()?.equals("diam", ignoreCase = true) == true
    val lastMove = history.firstOrNull { !it.isStill }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FB)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Color(0xFFE5E7EB))
    ) {
        Column {
            Text(
                "HISTORI GERAKAN TANGAN BUSUR",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = GreyText
            )
            HorizontalDivider(color = Color(0xFFE5E7EB))

            HistoryRow(
                label = "Status Gerakan Saat Ini",
                value = status ?: "--",
                valueColor = when {
                    status == null -> GreyText
                    still -> GoodColor
                    else -> WarnColor
                },
                showCheck = status != null && still
            )
            HorizontalDivider(color = Color(0xFFE5E7EB))
            HistoryRow(
                label = "Jumlah Gerakan Terdeteksi",
                value = if (connected || history.isNotEmpty()) "$movementCount kali" else "--",
                valueColor = if (movementCount > 0) BlueValue else GreyText,
                showCheck = false
            )
            HorizontalDivider(color = Color(0xFFE5E7EB))
            HistoryRow(
                label = "Gerakan Terakhir",
                value = lastMove?.text ?: "--",
                subValue = lastMove?.let { timeFormat.format(Date(it.timestamp)) },
                valueColor = if (lastMove != null) BlueValue else GreyText,
                showCheck = false
            )

            // Hanya tampil bila wearable juga mengirim sudut (format "x,y" atau x,y,z)
            if (hasAngle) {
                HorizontalDivider(color = Color(0xFFE5E7EB))
                HistoryRow(
                    label = "Arah Tangan Setelah Release",
                    value = direction,
                    valueColor = if (direction != "--") BlueValue else GreyText,
                    showCheck = direction != "--"
                )
                HorizontalDivider(color = Color(0xFFE5E7EB))
                HistoryRow(
                    label = "Besar Gerakan (Magnitude)",
                    value = magnitude,
                    valueColor = GoodColor,
                    showCheck = false
                )
            }

            HorizontalDivider(color = Color(0xFFE5E7EB))
            Text(
                "RIWAYAT PERUBAHAN STATUS",
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = GreyText
            )
            if (history.isEmpty()) {
                Text(
                    if (connected) "Menunggu status dari wearable..." else "Hubungkan sensor IMU untuk melihat riwayat.",
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    fontSize = 13.sp,
                    color = GreyText
                )
            } else {
                history.take(10).forEach { e ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (e.isStill) GoodColor else WarnColor)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            e.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF111827),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            timeFormat.format(Date(e.timestamp)),
                            fontSize = 12.sp,
                            color = GreyText
                        )
                    }
                }
                if (history.size > 10) {
                    Text(
                        "${history.size - 10} kejadian lebih lama disimpan (maks. 50).",
                        modifier = Modifier.padding(start = 20.dp, top = 4.dp),
                        fontSize = 11.sp,
                        color = GreyText
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

/**
 * Satu baris label + nilai. Label dan nilai berbagi lebar (45% : 55%) dan nilai boleh turun
 * ke beberapa baris, sehingga teks panjang seperti "Ke Kanan, Ke Bawah dan Ke Depan"
 * tidak mendesak label dan tidak terpotong.
 */
@Composable
private fun HistoryRow(
    label: String,
    value: String,
    valueColor: Color,
    showCheck: Boolean,
    subValue: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = Color(0xFF4B5563),
            modifier = Modifier.weight(0.45f)
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(0.55f),
            horizontalAlignment = Alignment.End
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = valueColor,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (showCheck) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = valueColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            if (subValue != null) {
                Text(subValue, fontSize = 12.sp, color = GreyText, textAlign = TextAlign.End)
            }
        }
    }
}