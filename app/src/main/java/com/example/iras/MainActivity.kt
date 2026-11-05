package com.example.iras

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.iras.ble.BleManager
import com.example.iras.ble.BleState
import com.example.iras.data.IrasViewModel
import com.example.iras.ui.screens.DashboardScreen
import com.example.iras.ui.screens.IoTScreen
import com.example.iras.ui.screens.IrasBodyFont
import com.example.iras.ui.screens.MonitorScreen
import com.example.iras.ui.screens.RiwayatScreen
import com.example.iras.ui.screens.ScoreScreen
import com.example.iras.ui.screens.SessionConfig

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IrasApp()
        }
    }
}

// Urutan tab sesuai desain: Monitor, Dashboard, IoT, Riwayat
enum class IrasTab(val label: String, val icon: ImageVector) {
    Monitor("Monitor", Icons.Outlined.Videocam),
    Dashboard("Dashboard", Icons.Outlined.Dashboard),
    IoT("IoT", Icons.Outlined.Memory),
    Riwayat("Riwayat", Icons.Outlined.History)
}

private val NavyBlue = Color(0xFF0B4DA2)
private val NavGray = Color(0xFF8A94A6)

// Dipakai jika lokasi pada konfigurasi sesi kosong
private const val DEFAULT_LOCATION = "Tembalang"

// Jumlah ronde pada setiap sesi; jumlah panah per ronde mengikuti pilihan di Dashboard
private const val ROUNDS_PER_SESSION = 6

private val IrasColorScheme = lightColorScheme(
    primary = Color(0xFF0B3C8C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001B4D),
    secondary = Color(0xFFEF6C00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0B2),
    onSecondaryContainer = Color(0xFF4A2300),
    background = Color(0xFFF6F8FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF0F5)
)

private fun typographyWith(font: FontFamily): Typography {
    val d = Typography()
    return d.copy(
        displayLarge = d.displayLarge.copy(fontFamily = font),
        displayMedium = d.displayMedium.copy(fontFamily = font),
        displaySmall = d.displaySmall.copy(fontFamily = font),
        headlineLarge = d.headlineLarge.copy(fontFamily = font),
        headlineMedium = d.headlineMedium.copy(fontFamily = font),
        headlineSmall = d.headlineSmall.copy(fontFamily = font),
        titleLarge = d.titleLarge.copy(fontFamily = font),
        titleMedium = d.titleMedium.copy(fontFamily = font),
        titleSmall = d.titleSmall.copy(fontFamily = font),
        bodyLarge = d.bodyLarge.copy(fontFamily = font),
        bodyMedium = d.bodyMedium.copy(fontFamily = font),
        bodySmall = d.bodySmall.copy(fontFamily = font),
        labelLarge = d.labelLarge.copy(fontFamily = font),
        labelMedium = d.labelMedium.copy(fontFamily = font),
        labelSmall = d.labelSmall.copy(fontFamily = font)
    )
}

@Composable
fun IrasApp() {
    MaterialTheme(
        colorScheme = IrasColorScheme,
        typography = typographyWith(IrasBodyFont)
    ) {
        var selectedTab by rememberSaveable { mutableIntStateOf(IrasTab.Dashboard.ordinal) }
        var scoreSessionId by rememberSaveable { mutableStateOf<Int?>(null) }

        // Data sesi disimpan permanen di database Room lewat ViewModel
        val vm: IrasViewModel = viewModel()
        val sessions by vm.sessions.collectAsState()

        // Bluetooth (BLE) ke ESP32
        val context = LocalContext.current
        val ble = remember { BleManager(context) }
        DisposableEffect(Unit) { onDispose { ble.disconnect() } }
        val bleState by ble.state.collectAsState()
        val imuData by ble.imu.collectAsState()
        // Status IMU hijau jika ESP32 (apa pun UUID-nya) tersambung dan data x,y,z sudah diterima
        val imuConnected = bleState is BleState.Connected && imuData != null

        // Semua tab punya header navy, jadi ikon status bar selalu terang (putih)
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as Activity).window
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            }
        }

        // Sesi baru dibuat kosong dan langsung muncul di Recent Sessions (paling atas).
        // Skor diisi nanti lewat halaman skor.
        fun saveSession(config: SessionConfig) {
            vm.createSession(
                config = config.copy(location = config.location.ifBlank { DEFAULT_LOCATION }),
                roundCount = ROUNDS_PER_SESSION
            )
        }

        val scoreSession = sessions.firstOrNull { it.id == scoreSessionId }
        val showingScore = selectedTab == IrasTab.Dashboard.ordinal && scoreSession != null

        // Tombol Back sistem menutup halaman skor
        BackHandler(enabled = showingScore) { scoreSessionId = null }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0.dp),
            bottomBar = {
                Column {
                    HorizontalDivider(color = Color(0xFFE6EAF0))
                    NavigationBar(
                        containerColor = Color.White,
                        tonalElevation = 0.dp
                    ) {
                        IrasTab.entries.forEach { tab ->
                            val selected = selectedTab == tab.ordinal
                            NavigationBarItem(
                                selected = selected,
                                onClick = { selectedTab = tab.ordinal },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = {
                                    Text(
                                        text = tab.label,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = NavyBlue,
                                    selectedTextColor = NavyBlue,
                                    unselectedIconColor = NavGray,
                                    unselectedTextColor = NavGray,
                                    indicatorColor = Color.Transparent
                                ),
                                modifier = Modifier.drawBehind {
                                    if (selected) {
                                        val barWidth = 32.dp.toPx()
                                        drawRoundRect(
                                            color = NavyBlue,
                                            topLeft = Offset((size.width - barWidth) / 2f, 0f),
                                            size = Size(barWidth, 3.dp.toPx()),
                                            cornerRadius = CornerRadius(3.dp.toPx())
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            // Setiap layar punya header IRAS sendiri yang sudah memakai statusBarsPadding(),
            // jadi tidak perlu padding status bar tambahan di sini.
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                if (showingScore && scoreSession != null) {
                    ScoreScreen(
                        session = scoreSession,
                        onBack = { scoreSessionId = null },
                        onUpdateSession = { vm.updateSession(it) },
                        imuOn = imuConnected
                    )
                } else {
                    when (IrasTab.entries[selectedTab]) {
                        IrasTab.Dashboard -> DashboardScreen(
                            sessions = sessions,
                            // Menekan jarak hanya membuat sesi; tetap di Dashboard
                            onStartSession = { config -> saveSession(config) },
                            // Menekan sesi di Recent Sessions langsung membuka halaman skor
                            onOpenSession = { session -> scoreSessionId = session.id },
                            onSeeAll = { selectedTab = IrasTab.Riwayat.ordinal },
                            onOpenIoT = { selectedTab = IrasTab.IoT.ordinal },
                            imuConnected = imuConnected
                        )

                        IrasTab.Monitor -> MonitorScreen(
                            config = null,
                            imu = imuData,
                            onFinishSession = { _, _ ->
                                // Sesi sudah dibuat dari Dashboard, jadi tidak disimpan lagi
                                selectedTab = IrasTab.Dashboard.ordinal
                            }
                        )

                        IrasTab.IoT -> IoTScreen(ble = ble)

                        IrasTab.Riwayat -> RiwayatScreen(
                            sessions = sessions,
                            imuOn = imuConnected,
                            onDelete = { id -> vm.deleteSession(id) }
                        )
                    }
                }
            }
        }
    }
}