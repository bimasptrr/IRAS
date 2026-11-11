package com.example.iras.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
val IrasBodyFont: FontFamily = FontFamily.Default
val IrasHeaderFont: FontFamily = FontFamily.Default

private val PageBg = Color(0xFFF6F8FB)
private val HeaderTop = Color(0xFF021B4A)
private val HeaderBottom = Color(0xFF032A6B)
private val CardGradientStart = Color(0xFF021B4A)
private val CardGradientEnd = Color(0xFF0B3A8C)
private val BorderColor = Color(0xFFE3E8F0)
private val TextDark = Color(0xFF0F172A)
private val TextGray = Color(0xFF9AA3B2)
private val LabelGray = Color(0xFF8A94A6)
private val ToggleBg = Color(0xFFEDF0F5)
private val PinRed = Color(0xFFE53935)
private val PbYellow = Color(0xFFFFD60A)
private val PbBg = Color(0xFFFFF6D6)
private val PbText = Color(0xFFB45309)
private val StatusRed = Color(0xFFFF4D5E)
private val StatusGreen = Color(0xFF3DDC84)
private val SubtitleBlue = Color(0xFF8FA6D0)



private data class DistanceOption(val label: String, val subtitle: String)

private val distanceOptions = listOf(
    DistanceOption("30m", "Short outdoor"),
    DistanceOption("50m", "Medium"),
    DistanceOption("70m", "Olympic"),
    DistanceOption("Custom", "Any distance")
)

@Composable
fun DashboardScreen(
    sessions: List<SessionRecord>,
    onStartSession: (SessionConfig) -> Unit,
    onOpenSession: (SessionRecord) -> Unit,
    modifier: Modifier = Modifier,
    onSeeAll: () -> Unit = {},
    imuConnected: Boolean = false,
    windConnected: Boolean = false,
    onOpenIoT: () -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var location by rememberSaveable { mutableStateOf("Tembalang") }
    var shots by rememberSaveable { mutableIntStateOf(6) }
    var showLocationDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomDialog by rememberSaveable { mutableStateOf(false) }

    // Saat sesi baru ditambahkan, gulir ke bawah agar terlihat di Recent Sessions
    var lastCount by remember { mutableIntStateOf(sessions.size) }
    LaunchedEffect(sessions.size) {
        if (sessions.size > lastCount) {
            withFrameNanos { } // tunggu layout selesai supaya maxValue sudah terbarui
            scrollState.animateScrollTo(scrollState.maxValue)
        }
        lastCount = sessions.size
    }

    // Hanya membuat sesi; tidak berpindah halaman
    fun startSession(distance: String) {
        onStartSession(
            SessionConfig(
                location = location.ifBlank { "Tembalang" },
                shots = shots,
                distance = distance
            )
        )
        Toast.makeText(context, "Sesi $distance dibuat", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PageBg)
    ) {
        IrasHeader(
            imuConnected = imuConnected,
            windConnected = windConnected,
            onStatusClick = onOpenIoT
        )

        // Seluruh isi bisa di-scroll agar tidak terpotong di layar kecil
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            GreetingCard()

            Spacer(Modifier.height(24.dp))
            SectionLabel("LOKASI")
            Spacer(Modifier.height(10.dp))
            LocationField(
                location = location.ifBlank { "Tembalang" },
                onClick = { showLocationDialog = true }
            )

            Spacer(Modifier.height(24.dp))
            SectionLabel("PENGATURAN SESI")
            Spacer(Modifier.height(10.dp))
            ShotsToggle(selected = shots, onSelect = { shots = it })

            Spacer(Modifier.height(12.dp))
            distanceOptions.chunked(2).forEachIndexed { index, rowItems ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowItems.forEach { option ->
                        DistanceCard(
                            option = option,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (option.label == "Custom") {
                                    showCustomDialog = true
                                } else {
                                    startSession(option.label)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("RECENT SESSIONS")
            Spacer(Modifier.height(10.dp))
            if (sessions.isEmpty()) {
                Text(
                    "Belum ada sesi. Pilih jarak untuk memulai!",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    color = TextGray
                )
            } else {
                sessions.take(3).forEachIndexed { index, session ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    RecentSessionCard(session = session, onClick = { onOpenSession(session) })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showLocationDialog) {
        var temp by remember { mutableStateOf(location) }
        AlertDialog(
            onDismissRequest = { showLocationDialog = false },
            title = { Text("Ubah Lokasi", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = temp,
                    onValueChange = { temp = it },
                    label = { Text("Nama lokasi") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    location = temp.trim()
                    showLocationDialog = false
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { showLocationDialog = false }) { Text("Batal") }
            }
        )
    }

    if (showCustomDialog) {
        var temp by remember { mutableStateOf("") }
        val valid = temp.toIntOrNull()?.let { it in 1..300 } == true
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Jarak Custom", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = temp,
                    onValueChange = { input -> temp = input.filter { it.isDigit() }.take(3) },
                    label = { Text("Jarak (meter, 1-300)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        showCustomDialog = false
                        startSession("${temp}m")
                    }
                ) { Text("Buat Sesi") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) { Text("Batal") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Header: logo IRAS + status IMU / WIND
// ---------------------------------------------------------------------------
@Composable
private fun IrasHeader(
    imuConnected: Boolean,
    windConnected: Boolean,
    onStatusClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(HeaderTop, HeaderBottom)))
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("", fontSize = 24.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "IRAS",
                fontFamily = IrasHeaderFont,
                fontWeight = FontWeight.Black,
                fontSize = 24.sp,
                letterSpacing = 1.sp,
                color = Color.White
            )
            Spacer(Modifier.weight(1f))
            StatusPill("IMU", imuConnected, onStatusClick)
            Spacer(Modifier.width(8.dp))
            StatusPill("WIND", windConnected, onStatusClick)
        }
    }
}

@Composable
private fun StatusPill(label: String, connected: Boolean, onClick: () -> Unit) {
    val color = if (connected) StatusGreen else StatusRed
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.18f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Kartu sapaan
// ---------------------------------------------------------------------------
@Composable
private fun GreetingCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(listOf(CardGradientStart, CardGradientEnd))
            )
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        // Lingkaran dekoratif kanan atas
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 36.dp, y = (-36).dp)
                .size(120.dp)
                .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Hi, Archer! 🏹",
                fontFamily = IrasHeaderFont,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp,
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Ready to hit your target?",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = SubtitleBlue
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Komponen kecil
// ---------------------------------------------------------------------------
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        color = LabelGray
    )
}

@Composable
private fun LocationField(location: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.LocationOn,
                contentDescription = null,
                tint = PinRed,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = location,
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextDark
            )
            Icon(
                Icons.Outlined.Edit,
                contentDescription = "Ubah lokasi",
                tint = TextGray,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun ShotsToggle(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ToggleBg, RoundedCornerShape(14.dp))
            .padding(4.dp)
    ) {
        listOf(6, 12).forEach { option ->
            val isSelected = selected == option
            Surface(
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(11.dp),
                color = if (isSelected) Color.White else Color.Transparent,
                shadowElevation = if (isSelected) 2.dp else 0.dp
            ) {
                Text(
                    text = "$option tembakan / ronde",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 11.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) TextDark else LabelGray
                )
            }
        }
    }
}

@Composable
private fun DistanceCard(
    option: DistanceOption,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(76.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = option.label,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextDark
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = option.subtitle,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextGray
            )
        }
    }
}

@Composable
private fun RecentSessionCard(session: SessionRecord, onClick: () -> Unit) {
    val hasScore = session.rounds.any { it.arrows.isNotEmpty() }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(
            width = if (session.isPB) 2.dp else 1.dp,
            color = if (session.isPB) PbYellow else BorderColor
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.distance,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextDark
                    )
                    if (session.isPB) {
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = PbBg) {
                            Text(
                                text = "PB 🏆",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PbText
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${session.date} · ${session.rounds.size} Ronde",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextGray
                )
            }
            Text(
                text = if (hasScore) "${session.score}" else "–",
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (hasScore) TextDark else TextGray
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = TextGray,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}