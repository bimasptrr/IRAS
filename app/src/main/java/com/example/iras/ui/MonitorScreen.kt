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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.iras.ble.ImuData
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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
    config: SessionConfig?,
    onFinishSession: (SessionConfig, Int) -> Unit,
    modifier: Modifier = Modifier,
    imu: ImuData? = null
) {
    val activeConfig = config ?: SessionConfig("Tembalang", 6, "30m")

    var analyzing by remember { mutableStateOf(false) }
    var hasAnalyzed by remember { mutableStateOf(false) }
    var fps by remember { mutableIntStateOf(30) }
    var elbowAngle by remember { mutableIntStateOf(170) }
    var shoulderTilt by remember { mutableFloatStateOf(1.5f) }
    var anchorLocked by remember { mutableStateOf(true) }
    var jitter by remember { mutableFloatStateOf(0f) }

    // Simulasi data biomekanik real-time (ganti dengan data MediaPipe nantinya)
    LaunchedEffect(analyzing) {
        while (analyzing) {
            delay(400)
            fps = Random.nextInt(28, 32)
            jitter = Random.nextFloat() * 2f - 1f
            elbowAngle = Random.nextInt(150, 176)
            shoulderTilt = Random.nextFloat() * 6f
            anchorLocked = Random.nextInt(10) > 2
        }
    }

    val elbowOk = elbowAngle in 160..180
    val shoulderOk = shoulderTilt <= 3f
    // Jika IMU terhubung, tremor dihitung dari kecepatan sudut (gyro, derajat/detik);
    // jika tidak, memakai simulasi. Angka pembagi 30 adalah patokan awal, silakan kalibrasi.
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
                    // Live CV: buka kamera perangkat
                    CameraPreview(modifier = Modifier.fillMaxSize())
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

                // Overlay skeleton di atas kamera
                SkeletonCanvas(
                    jitter = if (analyzing) effectiveJitter else 0f,
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

            Button(
                onClick = {
                    analyzing = !analyzing
                    if (analyzing) hasAnalyzed = true
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
                    onFinishSession(activeConfig, score)
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

// ---------- Skeleton (pose Draw) ----------
@Composable
private fun SkeletonCanvas(
    jitter: Float,
    analyzing: Boolean,
    elbowAngle: Int,
    elbowOk: Boolean,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val dx = 0.06f // geser agar skeleton berada di tengah
        fun p(x: Float, y: Float) = Offset(w * (x + dx) + jitter * 4f, h * y * 0.9f + h * 0.04f)

        // Grid latar halus (disembunyikan saat kamera aktif)
        if (!analyzing) {
            val gridColor = Color.White.copy(alpha = 0.04f)
            for (i in 1..9) {
                drawLine(gridColor, Offset(w * i / 10f, 0f), Offset(w * i / 10f, h), 1f)
                drawLine(gridColor, Offset(0f, h * i / 10f), Offset(w, h * i / 10f), 1f)
            }
        }

        val alpha = if (analyzing) 1f else 0.35f
        val bone = SkeletonColor.copy(alpha = alpha)
        val joint = SkeletonColor.copy(alpha = alpha)
        val strokeW = 4f

        val head = p(0.42f, 0.17f)
        val neck = p(0.42f, 0.29f)
        val shL = p(0.32f, 0.33f)
        val shR = p(0.52f, 0.33f)
        val elL = p(0.20f, 0.36f)
        val wrL = p(0.12f, 0.45f)
        val elR = p(0.68f, 0.30f)
        val wrR = p(0.46f, 0.27f)
        val hipL = p(0.37f, 0.62f)
        val hipR = p(0.47f, 0.62f)
        val knL = p(0.36f, 0.78f)
        val knR = p(0.48f, 0.78f)
        val anL = p(0.35f, 0.94f)
        val anR = p(0.49f, 0.94f)

        // Busur, tali, dan anak panah
        val cx = w * (0.10f + dx) + jitter * 4f
        val cy = h * 0.33f
        val rx = w * 0.08f
        val ry = h * 0.22f
        drawArc(
            color = Color(0xFFFFB74D).copy(alpha = alpha),
            startAngle = 110f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(cx - rx, cy - ry),
            size = Size(rx * 2, ry * 2),
            style = Stroke(width = 5f, cap = StrokeCap.Round)
        )
        val a1 = 110f * PI.toFloat() / 180f
        val a2 = 250f * PI.toFloat() / 180f
        val top = Offset(cx + rx * cos(a1), cy + ry * sin(a1))
        val bottom = Offset(cx + rx * cos(a2), cy + ry * sin(a2))
        drawLine(Color.White.copy(alpha = alpha), top, wrR, 2f)
        drawLine(Color.White.copy(alpha = alpha), bottom, wrR, 2f)
        drawLine(Color(0xFFFF5252).copy(alpha = alpha), wrL, wrR, 3f)

        fun seg(a: Offset, b: Offset) = drawLine(bone, a, b, strokeW, StrokeCap.Round)
        seg(neck, shL); seg(neck, shR)
        seg(shL, elL); seg(elL, wrL)
        seg(shR, elR); seg(elR, wrR)
        seg(neck, hipL); seg(neck, hipR); seg(hipL, hipR)
        seg(hipL, knL); seg(knL, anL)
        seg(hipR, knR); seg(knR, anR)
        seg(neck, head)

        drawCircle(bone, radius = w * 0.045f, center = head, style = Stroke(width = 3f))
        listOf(neck, shL, shR, elL, wrL, elR, wrR, hipL, hipR, knL, knR, anL, anR)
            .forEach { drawCircle(joint, radius = 6f, center = it) }

        // Label sudut siku di dekat siku kanan
        if (analyzing) {
            drawText(
                textMeasurer = textMeasurer,
                text = "$elbowAngle°",
                topLeft = Offset(elR.x + 12f, elR.y - 28f),
                style = TextStyle(
                    color = if (elbowOk) Color(0xFF4ADE80) else Color(0xFFFF5252),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}