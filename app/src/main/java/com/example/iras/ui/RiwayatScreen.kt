package com.example.iras.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Model ----------
data class RoundRecord(
    val number: Int,
    val arrows: List<String>,                 // "X", "10", "9", ..., "M"
    val points: List<Offset?> = emptyList(),  // posisi ketukan di target (relatif terhadap pusat)
    val capacity: Int = arrows.size           // jumlah panah per ronde
) {
    val score: Int get() = arrows.sumOf { arrowValue(it) }
    val maxScore: Int get() = capacity * 10
    val average: Float get() = if (arrows.isEmpty()) 0f else score.toFloat() / arrows.size
}

data class SessionRecord(
    val id: Int,
    val date: String,
    val distance: String,
    val location: String,
    val rounds: List<RoundRecord>,
    val isPB: Boolean
) {
    val score: Int get() = rounds.sumOf { it.score }
    val maxScore: Int get() = rounds.sumOf { it.maxScore }
    val status: String get() = statusFor(score, maxScore)
}

fun arrowValue(arrow: String): Int = when (arrow) {
    "X" -> 10
    "M" -> 0
    else -> arrow.toIntOrNull() ?: 0
}

fun statusFor(score: Int, maxScore: Int): String {
    val ratio = if (maxScore == 0) 0f else score.toFloat() / maxScore
    return when {
        ratio >= 0.9f -> "Luar Biasa"
        ratio >= 0.75f -> "Bagus"
        else -> "Terus Berlatih"
    }
}

fun sampleSessions(): List<SessionRecord> = emptyList()

// ---------- Laporan TXT ----------

/** Nama file laporan, contoh: IRAS_50m_21_Apr_2026_Ronde1.txt */
fun reportFileName(session: SessionRecord, round: RoundRecord): String {
    fun clean(s: String) = s.trim().replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
    return "IRAS_${clean(session.distance)}_${clean(session.date)}_Ronde${round.number}.txt"
}

/** Menyusun isi laporan tembakan untuk satu ronde. */
fun buildRoundReport(session: SessionRecord, round: RoundRecord): String {
    val status = statusFor(round.score, round.maxScore)
    val percent = if (round.maxScore > 0) round.score * 100f / round.maxScore else 0f
    val best = round.arrows.maxOfOrNull { arrowValue(it) } ?: 0
    val createdAt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID")).format(Date())
    val line = "=".repeat(40)
    val thin = "-".repeat(40)

    return buildString {
        appendLine(line)
        appendLine("IRAS - LAPORAN TEMBAKAN RONDE ${round.number}")
        appendLine("Intelligent Real-time Archery System")
        appendLine(line)
        appendLine()
        appendLine("INFORMASI SESI")
        appendLine(thin)
        appendLine("Tanggal Sesi   : ${session.date}")
        appendLine("Tempat         : ${session.location}")
        appendLine("Jarak          : ${session.distance}")
        appendLine("Ronde          : ${round.number} dari ${session.rounds.size}")
        appendLine("Kapasitas      : ${round.capacity} panah")
        appendLine("Personal Best  : ${if (session.isPB) "Ya" else "Tidak"}")
        appendLine()
        appendLine("RINGKASAN SKOR")
        appendLine(thin)
        appendLine("Skor           : ${round.score} / ${round.maxScore}")
        appendLine("Persentase     : ${String.format(Locale.US, "%.1f", percent)}%")
        appendLine("Rata-rata      : ${String.format(Locale.US, "%.2f", round.average)} per panah")
        appendLine("Nilai Terbaik  : $best")
        appendLine("Panah Dicatat  : ${round.arrows.size} / ${round.capacity}")
        appendLine("Status         : $status")
        appendLine()
        appendLine("RINCIAN PANAH")
        appendLine(thin)
        if (round.arrows.isEmpty()) {
            appendLine("Belum ada panah yang dicatat.")
        } else {
            round.arrows.forEachIndexed { index, arrow ->
                val p = round.points.getOrNull(index)
                val pos = if (p != null) {
                    "  (x=${String.format(Locale.US, "%.2f", p.x)}, y=${String.format(Locale.US, "%.2f", p.y)})"
                } else ""
                appendLine("Panah ${(index + 1).toString().padStart(2)} : ${arrow.padEnd(2)} (${arrowValue(arrow)} poin)$pos")
            }
        }
        appendLine()
        appendLine("DISTRIBUSI NILAI")
        appendLine(thin)
        val labels = listOf("X") + (10 downTo 1).map { it.toString() } + listOf("M")
        labels.forEach { label ->
            val count = round.arrows.count { it == label }
            if (count > 0) appendLine("${label.padEnd(2)} : $count panah")
        }
        appendLine()
        appendLine(thin)
        appendLine("Dibuat pada    : $createdAt")
        appendLine("Catatan        : posisi x,y relatif terhadap pusat target (1.00 = tepi luar).")
    }
}

// ---------- Warna ----------
private val NavyDark = Color(0xFF031A3D)
private val NavyLight = Color(0xFF0A2E66)
private val ScreenBg = Color(0xFFF7F9FC)
private val BlueScore = Color(0xFF0B4F9E)
private val GoldBorder = Color(0xFFFFD60A)
private val GreyText = Color(0xFF6B7280)
private val LightGreyText = Color(0xFF9CA3AF)
private val BorderGrey = Color(0xFFE5E7EB)
private val BadColor = Color(0xFFEF4444)
private val HeaderGradient = Brush.horizontalGradient(listOf(NavyDark, NavyLight))

private fun statusColor(status: String): Color = when (status) {
    "Luar Biasa" -> Color(0xFF22A15A)
    "Bagus" -> Color(0xFF1565C0)
    else -> Color(0xFFEF6C00)
}

// ---------- Layar utama ----------
@Composable
fun RiwayatScreen(
    sessions: List<SessionRecord>,
    modifier: Modifier = Modifier,
    onDelete: (Int) -> Unit = {},
    onDownloadVideo: (SessionRecord) -> Unit = {},
    imuOn: Boolean = false,
    windOn: Boolean = false
) {
    val context = LocalContext.current
    var selectedId by remember { mutableStateOf<Int?>(null) }
    val selected = sessions.firstOrNull { it.id == selectedId }

    // Isi laporan yang menunggu dipilih lokasi simpannya
    var pendingReport by remember { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = pendingReport
        pendingReport = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)
        Toast.makeText(
            context,
            if (ok) "Laporan berhasil disimpan" else "Gagal menyimpan laporan",
            Toast.LENGTH_SHORT
        ).show()
    }

    val downloadReport: (SessionRecord, RoundRecord) -> Unit = { session, round ->
        pendingReport = buildRoundReport(session, round)
        saveLauncher.launch(reportFileName(session, round))
    }

    BackHandler(enabled = selected != null) { selectedId = null }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ScreenBg)
    ) {
        RiwayatHeader(imuOn = imuOn, windOn = windOn)

        if (selected == null) {
            RiwayatList(sessions = sessions, onClick = { selectedId = it.id })
        } else {
            RiwayatDetail(
                session = selected,
                onBack = { selectedId = null },
                onDownloadVideo = { onDownloadVideo(selected) },
                onDownloadReport = { round -> downloadReport(selected, round) }
            )
        }
    }
}

// ---------- Daftar riwayat ----------
@Composable
private fun RiwayatList(
    sessions: List<SessionRecord>,
    onClick: (SessionRecord) -> Unit
) {
    if (sessions.isEmpty()) {
        Text(
            "Belum ada riwayat latihan.",
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            textAlign = TextAlign.Center,
            color = GreyText
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(sessions, key = { it.id }) { session ->
            SessionCard(session = session, onClick = { onClick(session) })
        }
    }
}

@Composable
fun SessionCard(
    session: SessionRecord,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val color = statusColor(session.status)
    val border = if (session.isPB) BorderStroke(2.dp, GoldBorder) else BorderStroke(1.dp, BorderGrey)
    val cardModifier = if (onClick != null) modifier.clickable { onClick() } else modifier

    Card(
        modifier = cardModifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = border
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        session.date,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF111827)
                    )
                    if (session.isPB) {
                        Spacer(Modifier.width(8.dp))
                        PbBadge()
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${session.distance} · ${session.rounds.size} Ronde",
                    fontSize = 13.sp,
                    color = GreyText,
                    modifier = Modifier.padding(start = 24.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.LocationOn,
                        contentDescription = null,
                        tint = LightGreyText,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(session.location, fontSize = 12.sp, color = LightGreyText)
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${session.score}",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = BlueScore
                )
                Spacer(Modifier.height(6.dp))
                StatusBadge(session.status, color)
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = LightGreyText,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun PbBadge() {
    val orange = Color(0xFFE08A00)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFFF6DD))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.EmojiEvents,
            contentDescription = null,
            tint = orange,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text("PB", color = orange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- Detail riwayat ----------
@Composable
private fun RiwayatDetail(
    session: SessionRecord,
    onBack: () -> Unit,
    onDownloadVideo: () -> Unit,
    onDownloadReport: (RoundRecord) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Baris atas: tombol kembali + judul
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(1.dp, BorderGrey, RoundedCornerShape(12.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Kembali",
                    tint = Color(0xFF374151),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Kembali",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827)
                )
            }
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "${session.distance} · ${session.rounds.size} Ronde",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${session.date} · ${session.location}",
                    fontSize = 12.sp,
                    color = LightGreyText
                )
            }
        }

        // Tombol unduh video
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFEAF3FF))
                .border(1.dp, Color(0xFFBFD9FA), RoundedCornerShape(14.dp))
                .clickable(onClick = onDownloadVideo)
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.PlayCircle,
                contentDescription = null,
                tint = BlueScore,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Unduh Video Latihan Sesi Ini",
                color = BlueScore,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Kartu per ronde
        session.rounds.forEach { round ->
            RoundCard(round = round, onDownloadReport = { onDownloadReport(round) })
        }
    }
}

@Composable
private fun RoundCard(
    round: RoundRecord,
    onDownloadReport: () -> Unit
) {
    val status = statusFor(round.score, round.maxScore)
    val statusCol = statusColor(status)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, BorderGrey)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Ronde ${round.number}",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF111827),
                        modifier = Modifier.padding(start = 28.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = GreyText)) { append("Skor: ") }
                            withStyle(SpanStyle(color = statusCol, fontWeight = FontWeight.Bold)) {
                                append("${round.score}")
                            }
                            withStyle(SpanStyle(color = GreyText)) {
                                append(" / ${round.maxScore} · Rata-rata: ${"%.1f".format(round.average)}")
                            }
                        },
                        fontSize = 12.sp
                    )
                }
                StatusBadge(status, statusCol)
            }

            // Chip nilai panah
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                round.arrows.forEach { arrow -> ArrowChip(arrow) }
            }

            // Tombol laporan TXT
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(HeaderGradient)
                    .clickable(onClick = onDownloadReport)
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Laporan Tembakan Ronde ${round.number} (TXT)",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ArrowChip(arrow: String) {
    val value = arrowValue(arrow)
    val (bg, fg, stroke) = when {
        arrow == "X" || value == 10 ->
            Triple(Color(0xFFFFF3C4), Color(0xFFD98A00), Color(0xFFFCE08A))
        value in 7..9 ->
            Triple(Color(0xFFFFE1E1), Color(0xFFDC2626), Color(0xFFF9B4B4))
        value in 5..6 ->
            Triple(Color(0xFFE0EEFF), Color(0xFF1D5FBF), Color(0xFFB5D2F7))
        else ->
            Triple(Color(0xFFF1F3F7), Color(0xFF4B5563), Color(0xFFD1D5DB))
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, stroke, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(arrow, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- Header IRAS ----------
@Composable
private fun RiwayatHeader(imuOn: Boolean, windOn: Boolean) {
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
        HeaderChip("IMU", imuOn)
        Spacer(Modifier.width(8.dp))
        HeaderChip("WIND", windOn)
    }
}

@Composable
private fun HeaderChip(label: String, active: Boolean) {
    val dot = if (active) Color(0xFF22C55E) else BadColor
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(dot.copy(alpha = 0.18f))
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