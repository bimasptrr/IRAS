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
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.example.iras.ai.BiomechanicsCalculator
import com.example.iras.ai.Point3D
import com.example.iras.ai.TechniqueEvaluator
import com.example.iras.ai.VoiceCoach
import com.example.iras.ai.VoiceCuePlanner
import com.example.iras.ai.PoseFrame
import com.example.iras.ble.ImuData
import com.example.iras.data.IrasViewModel
import com.example.iras.ui.CameraPreview
import com.example.iras.ui.MonitorRecorder
import com.example.iras.ui.SavedVideo
import com.example.iras.ui.playVideo
import com.example.iras.ui.saveVideoToGallery
import android.os.SystemClock
import kotlinx.coroutines.launch
import java.io.File
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

    // ---------- Rekam video (opsional) ----------
    val recorder = remember { MonitorRecorder() }
    val scope = rememberCoroutineScope()
    var recordOn by remember { mutableStateOf(false) }
    var recordedFile by remember { mutableStateOf<File?>(null) }
    var savedVideo by remember { mutableStateOf<SavedVideo?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { recorder.stop() } }
    fun stopAnalysis() {
        recorder.stop()
        analyzing = false
    }
    var hasAnalyzed by remember { mutableStateOf(false) }
    var fps by remember { mutableIntStateOf(30) }
    var lastEvalTs by remember { mutableStateOf(0L) }
    var drawElbowAngle by remember { mutableStateOf<Float?>(null) }
    var drawElbowFaceRatio by remember { mutableStateOf<Float?>(null) }
    var drawHandFaceRatio by remember { mutableStateOf<Float?>(null) }
    var bowElbowAngle by remember { mutableStateOf<Float?>(null) }
    var shoulderTilt by remember { mutableStateOf<Float?>(null) }
    var stanceTilt by remember { mutableStateOf<Float?>(null) }

    // Data AI MediaPipe. Nilai null berarti metrik itu tidak terbaca pada frame
    // ini - sengaja dibiarkan kosong, bukan diisi angka tebakan.
    LaunchedEffect(analyzing, evaluationState) {
        val eval = evaluationState
        if (analyzing && eval != null && eval.isValid) {
            drawElbowAngle = eval.metrics.drawArmElbowAngleDeg
            drawElbowFaceRatio = eval.metrics.drawElbowFaceRatio
            drawHandFaceRatio = eval.metrics.drawHandFaceRatio
            bowElbowAngle = eval.metrics.bowArmElbowAngleDeg
            shoulderTilt = eval.metrics.bodyAlignmentDeviationDeg
            stanceTilt = eval.metrics.stanceDeviationDeg
        } else {
            drawElbowAngle = null
            drawElbowFaceRatio = null
            drawHandFaceRatio = null
            bowElbowAngle = null
            shoulderTilt = null
            stanceTilt = null
        }

        // FPS dihitung dari selisih timestamp frame MediaPipe.
        val ts = eval?.timestampMs ?: 0L
        if (ts > 0L) {
            val dt = ts - lastEvalTs
            if (lastEvalTs > 0L && dt in 1..1000L) {
                fps = (1000f / dt).toInt().coerceIn(1, 60)
            }
            lastEvalTs = ts
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

    // Salinan lokal supaya nilainya bisa di-smart-cast saat dipakai di dalam when.
    val angleValue = drawElbowAngle
    val elbowFaceValue = drawElbowFaceRatio
    val handFaceValue = drawHandFaceRatio
    val bowValue = bowElbowAngle
    val shoulderValue = shoulderTilt
    val stanceValue = stanceTilt

    val elbowOk = angleValue?.let {
        it >= TechniqueEvaluator.DRAW_ELBOW_MIN_DEG && it <= TechniqueEvaluator.DRAW_ELBOW_MAX_DEG
    }
    val elbowFaceOk = elbowFaceValue?.let {
        it >= TechniqueEvaluator.DRAW_ELBOW_FACE_MIN && it <= TechniqueEvaluator.DRAW_ELBOW_FACE_MAX
    }
    val anchorOk = handFaceValue?.let {
        it >= TechniqueEvaluator.ANCHOR_FACE_MIN && it <= TechniqueEvaluator.ANCHOR_FACE_MAX
    }
    val bowOk = bowValue?.let {
        it >= TechniqueEvaluator.BOW_ELBOW_MIN_DEG && it <= TechniqueEvaluator.BOW_ELBOW_MAX_DEG
    }
    val shoulderOk = shoulderValue?.let { it <= TechniqueEvaluator.ALIGNMENT_MAX_DEG }
    val stanceOk = stanceValue?.let { it <= TechniqueEvaluator.STANCE_MAX_DEG }

    // Tremor hanya bisa diukur kalau wearable IMU tersambung.
    val tremorValue = imu?.gyroMagnitude?.div(30f)?.coerceIn(0f, 1f)
    val tremorOk = tremorValue?.let { abs(it) < 0.6f }

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
                        useFrontCamera = useFrontCamera,
                        videoCapture = if (recordOn) recorder.videoCapture else null,
                        onCameraBound = {
                            if (recordOn && !recorder.isRecording) {
                                recorder.start(context) { file -> recordedFile = file }
                            }
                        }
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
                    elbowAngle = drawElbowAngle,
                    elbowOk = elbowOk,
                    drawElbowId = if (viewModel.isRightHanded) {
                        BiomechanicsCalculator.ELBOW_RIGHT
                    } else {
                        BiomechanicsCalculator.ELBOW_LEFT
                    },
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
                        label = "Sudut Siku Penarik",
                        value = angleValue?.let { "${it.toInt()}°" } ?: "—",
                        sub = when {
                            angleValue == null -> "Tidak terbaca"
                            angleValue < TechniqueEvaluator.DRAW_ELBOW_MIN_DEG -> "Terlalu Tertutup"
                            angleValue > TechniqueEvaluator.DRAW_ELBOW_MAX_DEG -> "Terlalu Terbuka"
                            else -> "Sesuai"
                        },
                        ok = elbowOk
                    )
                    HorizontalDivider(color = Color(0xFFF0F2F6))
                    MetricRow(
                        label = "Posisi Siku",
                        value = elbowFaceValue?.let { "${(it * 100).toInt()}%" } ?: "—",
                        sub = when {
                            elbowFaceValue == null -> "Wajah tidak terbaca"
                            elbowFaceValue < TechniqueEvaluator.DRAW_ELBOW_FACE_MIN -> "Terlalu Tinggi"
                            elbowFaceValue > TechniqueEvaluator.DRAW_ELBOW_FACE_MAX -> "Kurang Naik"
                            else -> "Antara Hidung-Dagu"
                        },
                        ok = elbowFaceOk
                    )
                    HorizontalDivider(color = Color(0xFFF0F2F6))
                    MetricRow(
                        label = "Anchor Point",
                        value = when (anchorOk) {
                            true -> "Tepat"
                            false -> "Perlu Dikoreksi"
                            null -> "—"
                        },
                        sub = when {
                            handFaceValue == null -> "Wajah tidak terbaca"
                            anchorOk == true -> "Tangan di area rahang"
                            else -> "Tangan di luar rahang"
                        },
                        ok = anchorOk
                    )
                    HorizontalDivider(color = Color(0xFFF0F2F6))
                    MetricRow(
                        label = "Lengan Busur",
                        value = bowValue?.let { "${it.toInt()}°" } ?: "—",
                        sub = when {
                            bowValue == null -> "Tidak terbaca"
                            bowValue < TechniqueEvaluator.BOW_ELBOW_MIN_DEG -> "Belum Lurus"
                            bowValue > TechniqueEvaluator.BOW_ELBOW_MAX_DEG -> "Terkunci"
                            else -> "Lurus"
                        },
                        ok = bowOk
                    )
                    HorizontalDivider(color = Color(0xFFF0F2F6))
                    MetricRow(
                        label = "Bahu (Alignment)",
                        value = shoulderValue?.let { "${"%.1f".format(it)}°" } ?: "—",
                        sub = when {
                            shoulderValue == null -> "Hanya terbaca dari depan"
                            shoulderOk == true -> "Sejajar"
                            else -> "Miring"
                        },
                        ok = shoulderOk
                    )
                    HorizontalDivider(color = Color(0xFFF0F2F6))
                    MetricRow(
                        label = "Posisi Kaki",
                        value = stanceValue?.let { "${"%.1f".format(it)}°" } ?: "—",
                        sub = when {
                            stanceValue == null -> "Hanya terbaca dari depan"
                            stanceOk == true -> "Rata"
                            else -> "Tidak Rata"
                        },
                        ok = stanceOk
                    )
                    HorizontalDivider(color = Color(0xFFF0F2F6))
                    MetricRow(
                        label = "Tremor Tangan",
                        value = when (tremorOk) {
                            true -> "Stabil"
                            false -> "Tremor"
                            null -> "—"
                        },
                        sub = when (tremorOk) {
                            true -> "Minimal"
                            false -> "Terdeteksi"
                            null -> "Butuh wearable IMU"
                        },
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
                        if (!selected && !(analyzing && recordOn)) {
                            useFrontCamera = front
                            // Hapus frame lama agar skeleton tidak melompat saat kamera berganti
                            viewModel.resetSession()
                        }
                    }
                    if (selected) {
                        Button(
                            onClick = onPick,
                            enabled = !(analyzing && recordOn),
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
                            enabled = !(analyzing && recordOn),
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

            // ---------- Tangan penarik ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tangan penarik", fontSize = 14.sp, color = Color(0xFF374151))
                    Text(
                        "Lengan yang menarik tali busur",
                        fontSize = 11.sp,
                        color = Color(0xFF6B7280)
                    )
                }
                listOf(true to "Kanan", false to "Kiri").forEach { (right, label) ->
                    FilterChip(
                        selected = viewModel.isRightHanded == right,
                        onClick = { viewModel.updateHandedness(right) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            // ---------- Rekam video (opsional) ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Videocam, contentDescription = null, tint = NavyLight)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Rekam video", fontSize = 14.sp, color = Color(0xFF374151))
                    Text(
                        when {
                            analyzing && recordOn -> "Sedang merekam... (kamera dikunci)"
                            analyzing -> "Aktifkan sebelum menekan MULAI ANALISIS"
                            else -> "Opsional. Anda memilih simpan atau buang setelah analisis"
                        },
                        fontSize = 11.sp,
                        color = if (analyzing && recordOn) BadColor else Color(0xFF6B7280)
                    )
                }
                Switch(
                    checked = recordOn,
                    onCheckedChange = { recordOn = it },
                    enabled = !analyzing
                )
            }

            // ---------- Hasil rekaman: Simpan / Buang ----------
            val pending = recordedFile
            val saved = savedVideo
            if (pending != null || saved != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, BorderGrey)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (saved != null) {
                            Text("Video tersimpan", fontWeight = FontWeight.Bold, color = GoodColor)
                            Text(saved.where, fontSize = 12.sp, color = Color(0xFF6B7280))
                            Row(
                                modifier = Modifier.padding(top = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (saved.uri != null) {
                                    Button(onClick = { playVideo(context, saved.uri) }) { Text("Putar") }
                                }
                                OutlinedButton(onClick = { savedVideo = null }) { Text("Tutup") }
                            }
                        } else if (pending != null) {
                            val mb = pending.length() / (1024f * 1024f)
                            Text(
                                "Video rekaman siap (${"%.1f".format(mb)} MB)",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF111827)
                            )
                            Text(
                                if (saveError) "Gagal menyimpan. Coba lagi atau buang."
                                else "Simpan ke HP, atau buang jika tidak diperlukan. Selesaikan pilihan ini sebelum menyelesaikan sesi.",
                                fontSize = 12.sp,
                                color = if (saveError) BadColor else Color(0xFF6B7280)
                            )
                            Row(
                                modifier = Modifier.padding(top = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    enabled = !saving,
                                    onClick = {
                                        scope.launch {
                                            saving = true
                                            saveError = false
                                            val result = saveVideoToGallery(context, pending)
                                            saving = false
                                            if (result != null) {
                                                savedVideo = result
                                                recordedFile = null
                                            } else {
                                                saveError = true
                                            }
                                        }
                                    }
                                ) { Text(if (saving) "Menyimpan..." else "Simpan ke HP") }
                                OutlinedButton(
                                    enabled = !saving,
                                    onClick = {
                                        pending.delete()
                                        recordedFile = null
                                        saveError = false
                                    }
                                ) { Text("Buang") }
                            }
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
                    if (!analyzing) {
                        // Rekaman lama yang belum disimpan dibuang saat analisis baru dimulai
                        recordedFile?.delete()
                        recordedFile = null
                        savedVideo = null
                        saveError = false
                        analyzing = true
                        hasAnalyzed = true
                    } else {
                        stopAnalysis()
                        viewModel.resetSession()
                    }
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
                    val busy = recorder.isRecording
                    stopAnalysis()
                    // Jika ada video yang belum disimpan/dibuang, tetap di halaman ini dulu
                    if (busy || recordedFile != null) return@OutlinedButton
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
    ok: Boolean?
) {
    // null = metrik tidak terbaca, ditampilkan netral dengan tanda strip.
    val color = when (ok) {
        true -> GoodColor
        false -> BadColor
        null -> Color(0xFF9CA3AF)
    }
    val icon: ImageVector = when (ok) {
        true -> Icons.Outlined.CheckCircle
        false -> Icons.Outlined.WarningAmber
        null -> Icons.Outlined.Visibility
    }

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
    elbowAngle: Float?,
    elbowOk: Boolean?,
    drawElbowId: Int,
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

        // Tampilkan teks sudut siku pada posisi siku lengan penarik
        val drawElbow = landmarks[drawElbowId]
        if (drawElbow != null && drawElbow.visibility >= visibilityThreshold && elbowAngle != null) {
            val elbowPos = mapPoint(drawElbow)
            drawText(
                textMeasurer = textMeasurer,
                text = "${elbowAngle.toInt()}°",
                topLeft = Offset(elbowPos.x + 12f, elbowPos.y - 28f),
                style = TextStyle(
                    color = when (elbowOk) {
                        true -> Color(0xFF4ADE80)
                        false -> Color(0xFFFF5252)
                        null -> Color(0xFFB0BEC5)
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}