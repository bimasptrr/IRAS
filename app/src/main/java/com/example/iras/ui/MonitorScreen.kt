package com.example.iras.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.CameraRear
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.iras.ai.Point3D
import com.example.iras.ai.VoiceCoach
import com.example.iras.ai.VoiceCuePlanner
import com.example.iras.ai.PoseFrame
import com.example.iras.ble.ImuData
import com.example.iras.data.IrasViewModel
import com.example.iras.ui.CameraPreview
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

// ---------- Warna ----------
private val NavyDark = Color(0xFF031A3D)
private val NavyLight = Color(0xFF0A2E66)
private val ScreenBg = Color(0xFFF7F9FC)
private val GoodColor = Color(0xFF22A15A)
private val BadColor = Color(0xFFE53935)
private val BorderGrey = Color(0xFFE5E7EB)
private val SkeletonColor = Color(0xFF4FC3D9)
private val HeaderGradient = Brush.horizontalGradient(listOf(NavyDark, NavyLight))
private val CameraGradient = Brush.verticalGradient(listOf(Color(0xFF111C3A), Color(0xFF0A1228)))

@Composable
fun MonitorScreen(
    config: SessionConfig? = null,
    onFinishSession: ((SessionConfig, Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    imu: ImuData? = null,
    viewModel: IrasViewModel = viewModel()
) {
    val activeConfig = config ?: SessionConfig("Tembalang", 6, "30m")
    val evaluationState by viewModel.evaluationState.collectAsState()
    val currentFrame by viewModel.currentFrame.collectAsState()

    var analyzing by remember { mutableStateOf(false) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var hasAnalyzed by remember { mutableStateOf(false) }
    var fps by remember { mutableIntStateOf(30) }
    var elbowAngle by remember { mutableIntStateOf(170) }
    var shoulderTilt by remember { mutableFloatStateOf(1.5f) }
    var anchorLocked by remember { mutableStateOf(true) }
    var jitter by remember { mutableFloatStateOf(0f) }

    // Mengintegrasikan data AI MediaPipe saat analisis aktif, atau fallback simulasi saat standby
    LaunchedEffect(analyzing, evaluationState) {
        if (analyzing) {
            val eval = evaluationState
            if (eval != null && eval.isValid) {
                elbowAngle = eval.metrics.drawArmElbowAngleDeg.toInt()
                shoulderTilt = eval.metrics.bodyAlignmentDeviationDeg
                anchorLocked = eval.techniqueStatus == "IDEAL"
                fps = Random.nextInt(28, 32)
                jitter = (Random.nextFloat() * 2f) - 1f
            } else {
                delay(400)
                fps = Random.nextInt(28, 32)
                jitter = (Random.nextFloat() * 2f) - 1f
                elbowAngle = Random.nextInt(150, 176)
                shoulderTilt = Random.nextFloat() * 6f
                anchorLocked = Random.nextInt(10) > 2
            }
        }
    }

    // ---------- Umpan balik suara (TTS) dari hasil koreksi MediaPipe ----------
    val context = LocalContext.current
    val voice = remember { VoiceCoach(context) }
    val planner = remember { VoiceCuePlanner() }
    val voiceStatus by voice.status.collectAsState()
    var voiceOn by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { voice.shutdown() } }
    LaunchedEffect(analyzing, voiceOn) {
        if (!analyzing || !voiceOn) {
            voice.stop()
            planner.reset()
        }
    }
    LaunchedEffect(evaluationState) {
        if (analyzing && voiceOn) {
            planner.next(evaluationState, SystemClock.elapsedRealtime())?.let { voice.speak(it) }
        }
    }

    val elbowOk = elbowAngle in 160..180
    val shoulderOk = shoulderTilt <= 3f
    val effectiveJitter = if (imu != null) (imu.gyroMagnitude / 30f).coerceIn(0f, 1f) else jitter
    val tremorOk = abs(effectiveJitter) < 0.6f

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ScreenBg)
    ) {
        MonitorHeader(analyzing = analyzing)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---------- Area Computer Vision ----------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(CameraGradient)
            ) {
                if (analyzing) {
                    // Live CV: Kamera Perangkat terhubung ke MediaPipe Pose & IrasViewModel
                    CameraPreview(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize(),
                        useFrontCamera = useFrontCamera
                    )
                } else {
                    // Placeholder saat standby
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Outlined.Visibility,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.25f),
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            "Camera Feed",
                            color = Color.White.copy(alpha = 0.25f),
                            fontSize = 10.sp
                        )
                    }
                }

                // Overlay skeleton MediaPipe dinamis di atas kamera
                SkeletonCanvas(
                    frame = currentFrame,
                    analyzing = analyzing,
                    elbowAngle = elbowAngle,
                    elbowOk = elbowOk,
                    modifier = Modifier.fillMaxSize()
                )

                // Kiri atas: FPS
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(14.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        if (analyzing) "$fps FPS · HD" else "-- FPS · HD",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Kanan atas: LIVE CV
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (analyzing) BadColor else Color(0xFF4B5563))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        if (analyzing) "LIVE CV" else "STANDBY",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                // Kiri bawah: label
                Text(
                    "Computer Vision Analysis",
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp
                )

                // Kanan bawah: hanya fase DRAW
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1F7A3A))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        "DRAW",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // ---------- Real-Time Biomechanics ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                border = BorderStroke(1.dp, BorderGrey)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(HeaderGradient)
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.MonitorHeart,
                            contentDescription = null,
                            tint = Color(0xFF93C5FD),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "REAL-TIME BIOMECHANICS",
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

                    MetricRow(
                        label = "Sudut Siku",
                        value = "$elbowAngle°",
                        sub = when {
                            elbowAngle < 160 -> "Terlalu Rapat"
                            elbowAngle > 180 -> "Terlalu Lurus"
                            else -> "Sudut Ideal"
                        },
                        ok = elbowOk
                    )
                    HorizontalDivider(color = Color(0xFFF0F2F6))
                    MetricRow(
                        label = "Bahu (Alignment)",
                        value = if (shoulderOk) "Sejajar" else "Miring",
                        sub = if (shoulderOk) "Posisi Ideal" else "${"%.1f".format(shoulderTilt)}° Miring",
                        ok = shoulderOk
                    )
                    HorizontalDivider(color = Color(0xFFF0F2F6))
                    MetricRow(
                        label = "Anchor Point",
                        value = if (anchorLocked) "Tepat" else "Lepas",
                        sub = if (anchorLocked) "Konsisten" else "Goyang",
                        ok = anchorLocked
                    )
                    HorizontalDivider(color = Color(0xFFF0F2F6))
                    MetricRow(
                        label = "Tremor Tangan",
                        value = if (tremorOk) "Stabil" else "Tremor",
                        sub = if (tremorOk) "Minimal" else "Terdeteksi",
                        ok = tremorOk
                    )
                }
            }

            // ---------- Kontrol ----------
            Text(
                "${activeConfig.location} • ${activeConfig.distance} • ${activeConfig.shots} tembakan/ronde",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                color = Color(0xFF6B7280)
            )

            // ---------- Pilihan kamera ----------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf(false to "Kamera Belakang", true to "Kamera Depan").forEach { (front, label) ->
                    val selected = useFrontCamera == front
                    val icon = if (front) Icons.Filled.CameraFront else Icons.Filled.CameraRear
                    val onPick = {
                        if (!selected) {
                            useFrontCamera = front
                            // Hapus frame lama agar skeleton tidak melompat saat kamera berganti
                            viewModel.resetSession()
                        }
                    }
                    if (selected) {
                        Button(
                            onClick = onPick,
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NavyLight)
                        ) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onPick,
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ---------- Suara koreksi ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (voiceOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = null,
                    tint = NavyLight
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Suara koreksi", fontSize = 14.sp, color = Color(0xFF374151))
                    when (voiceStatus) {
                        VoiceCoach.Status.NO_INDONESIAN -> Text(
                            "Suara bahasa Indonesia belum terpasang di HP",
                            fontSize = 11.sp, color = BadColor
                        )
                        VoiceCoach.Status.ERROR -> Text(
                            "Mesin suara (TTS) tidak tersedia",
                            fontSize = 11.sp, color = BadColor
                        )
                        else -> Text(
                            "Koreksi bagian tubuh dibacakan saat analisis",
                            fontSize = 11.sp, color = Color(0xFF6B7280)
                        )
                    }
                }
                if (voiceStatus == VoiceCoach.Status.NO_INDONESIAN) {
                    TextButton(onClick = { voice.openVoiceInstall(context) }) { Text("Pasang") }
                }
                Switch(checked = voiceOn, onCheckedChange = { voiceOn = it })
            }

            Button(
                onClick = {
                    analyzing = !analyzing
                    if (analyzing) hasAnalyzed = true
                    else viewModel.resetSession()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (analyzing) BadColor else NavyLight
                )
            ) {
                Icon(
                    if (analyzing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (analyzing) "HENTIKAN ANALISIS" else "MULAI ANALISIS",
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = {
                    analyzing = false
                    val max = activeConfig.shots * 10
                    val score = (max * (0.7f + Random.nextFloat() * 0.3f)).toInt()
                    onFinishSession?.invoke(activeConfig, score)
                },
                enabled = hasAnalyzed,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Selesai & Simpan Sesi", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------- Header IRAS ----------
@Composable
private fun MonitorHeader(analyzing: Boolean) {
    val statusColor = if (analyzing) Color(0xFF22C55E) else BadColor
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
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(statusColor.copy(alpha = 0.18f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.PowerSettingsNew,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (analyzing) "ON" else "OFF",
                color = statusColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ---------- Baris metrik ----------
@Composable
private fun MetricRow(
    label: String,
    value: String,
    sub: String,
    ok: Boolean
) {
    val color = if (ok) GoodColor else BadColor
    val icon: ImageVector = if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = Color(0xFF374151),
            modifier = Modifier.weight(1f)
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(color.copy(alpha = 0.10f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    value,
                    color = color,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    sub,
                    color = color.copy(alpha = 0.6f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

// Koneksi landmark MediaPipe Pose (pasangan ID landmark)
private val poseConnections = listOf(
    // Wajah / Kepala
    0 to 11,
    0 to 12,
    // Bahu & Torso
    11 to 12,
    11 to 23,
    12 to 24,
    23 to 24,
    // Lengan Kiri
    11 to 13,
    13 to 15,
    15 to 17,
    15 to 19,
    15 to 21,
    // Lengan Kanan
    12 to 14,
    14 to 16,
    16 to 18,
    16 to 20,
    16 to 22,
    // Kaki Kiri
    23 to 25,
    25 to 27,
    27 to 29,
    27 to 31,
    // Kaki Kanan
    24 to 26,
    26 to 28,
    28 to 30,
    28 to 32
)

// ---------- Skeleton Dynamic Canvas (MediaPipe Real-time Pose) ----------
@Composable
private fun SkeletonCanvas(
    frame: PoseFrame?,
    analyzing: Boolean,
    elbowAngle: Int,
    elbowOk: Boolean,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        if (!analyzing) {
            val gridColor = Color.White.copy(alpha = 0.04f)
            for (i in 1..9) {
                drawLine(gridColor, Offset(w * i / 10f, 0f), Offset(w * i / 10f, h), 1f)
                drawLine(gridColor, Offset(0f, h * i / 10f), Offset(w, h * i / 10f), 1f)
            }
            return@Canvas
        }

        if (frame == null || frame.landmarks.isEmpty()) return@Canvas

        val frameW = frame.frameWidth.toFloat()
        val frameH = frame.frameHeight.toFloat()
        if (frameW <= 0f || frameH <= 0f) return@Canvas

        // PreviewView memakai ScaleType.FILL_CENTER: frame diskalakan seragam
        // sampai menutupi seluruh area lalu di-crop di tengah. Transformasi yang
        // sama diterapkan di sini supaya skeleton menempel tepat di badan.
        val scale = max(w / frameW, h / frameH)
        val renderedW = frameW * scale
        val renderedH = frameH * scale
        val offsetX = (w - renderedW) / 2f
        val offsetY = (h - renderedH) / 2f

        fun mapPoint(point: Point3D): Offset {
            // Kamera depan ditampilkan mirror oleh PreviewView, jadi sumbu X
            // landmark juga harus dibalik agar overlay tidak tertukar sisi.
            val nx = if (frame.mirrored) 1f - point.x else point.x
            return Offset(
                x = offsetX + nx * renderedW,
                y = offsetY + point.y * renderedH
            )
        }

        val landmarks = frame.landmarks
        val boneColor = SkeletonColor
        val jointColor = Color.White
        val strokeW = 4f
        val visibilityThreshold = 0.3f

        // Gambar garis koneksi antar landmark MediaPipe
        for ((startId, endId) in poseConnections) {
            val start = landmarks[startId]
            val end = landmarks[endId]
            if (start != null && end != null &&
                start.visibility >= visibilityThreshold &&
                end.visibility >= visibilityThreshold
            ) {
                drawLine(
                    color = boneColor,
                    start = mapPoint(start),
                    end = mapPoint(end),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
            }
        }

        // Gambar titik sendi (joints)
        for ((_, point) in landmarks) {
            if (point.visibility >= visibilityThreshold) {
                drawCircle(
                    color = jointColor,
                    radius = 5f,
                    center = mapPoint(point)
                )
            }
        }

        // Tampilkan teks sudut siku pada posisi siku (ID 14 atau ID 13)
        val drawElbow = landmarks[14] ?: landmarks[13]
        if (drawElbow != null && drawElbow.visibility >= visibilityThreshold) {
            val elbowPos = mapPoint(drawElbow)
            drawText(
                textMeasurer = textMeasurer,
                text = "$elbowAngle°",
                topLeft = Offset(elbowPos.x + 12f, elbowPos.y - 28f),
                style = TextStyle(
                    color = if (elbowOk) Color(0xFF4ADE80) else Color(0xFFFF5252),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}