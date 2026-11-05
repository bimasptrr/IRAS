package com.example.iras.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.sqrt

// ---------- Warna ----------
private val NavyDark = Color(0xFF031A3D)
private val NavyLight = Color(0xFF0A2E66)
private val ScreenBg = Color(0xFFF7F9FC)
private val BlueScore = Color(0xFF0B4F9E)
private val GoodColor = Color(0xFF22A15A)
private val BadColor = Color(0xFFEF4444)
private val GreyText = Color(0xFF6B7280)
private val LightGreyText = Color(0xFF9CA3AF)
private val BorderGrey = Color(0xFFE5E7EB)
private val SubtitleBlue = Color(0xFF8FA6D0)
private val HeaderGradient = Brush.horizontalGradient(listOf(NavyDark, NavyLight))

// Radius target (cincin terluar) sebagai pecahan dari lebar kanvas
private const val TargetRadiusFraction = 0.40f

/**
 * Halaman input skor: pengguna mengetuk target, nilai panah dihitung dari jarak ke pusat.
 * Jumlah ronde dan kapasitas panah per ronde mengikuti sesi yang dibuat di Dashboard.
 * Data disimpan lewat [onUpdateSession] (sumber data ada di MainActivity).
 */
@Composable
fun ScoreScreen(
    session: SessionRecord,
    onBack: () -> Unit,
    onUpdateSession: (SessionRecord) -> Unit,
    modifier: Modifier = Modifier,
    roundNumber: Int = 1,
    imuOn: Boolean = false,
    windOn: Boolean = false
) {
    // Ronde yang sedang dibuka (diingat per sesi)
    var selectedRound by rememberSaveable(session.id) { mutableIntStateOf(roundNumber) }

    val round = session.rounds.firstOrNull { it.number == selectedRound } ?: session.rounds.firstOrNull()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ScreenBg)
    ) {
        ScoreHeader(imuOn = imuOn, windOn = windOn)

        if (round == null) {
            Text(
                "Sesi ini belum punya ronde.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                textAlign = TextAlign.Center,
                color = GreyText
            )
            return@Column
        }

        val currentSession by rememberUpdatedState(session)
        val currentRound by rememberUpdatedState(round)
        val currentOnUpdate by rememberUpdatedState(onUpdateSession)

        fun commit(newArrows: List<String>, newPoints: List<Offset?>) {
            val s = currentSession
            val r = currentRound
            currentOnUpdate(
                s.copy(
                    rounds = s.rounds.map {
                        if (it.number == r.number) it.copy(arrows = newArrows, points = newPoints) else it
                    }
                )
            )
        }

        val handleTap: (Offset) -> Unit = tap@{ norm ->
            val r = currentRound
            if (r.arrows.size >= r.capacity) return@tap
            val distance = sqrt(norm.x * norm.x + norm.y * norm.y)
            val padding: List<Offset?> = List((r.arrows.size - r.points.size).coerceAtLeast(0)) { null }
            val basePoints: List<Offset?> = r.points + padding
            commit(r.arrows + scoreLabelFor(distance), basePoints + norm)
        }

        val handleUndo: () -> Unit = {
            val r = currentRound
            if (r.arrows.isNotEmpty()) {
                val newArrows = r.arrows.dropLast(1)
                commit(newArrows, r.points.take(newArrows.size))
            }
        }

        val percent = if (round.maxScore > 0) (round.score * 100f / round.maxScore).toInt() else 0
        val recorded = round.arrows.size
        val complete = recorded >= round.capacity && round.capacity > 0
        val best = round.arrows.maxOfOrNull { arrowValue(it) } ?: 0
        val nextRound = session.rounds.firstOrNull { it.number > round.number }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ----- Baris atas: Back, judul, total sesi -----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.width(88.dp)) {
                    Row(
                        modifier = Modifier
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
                            "Back",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF111827)
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "${session.distance} · Ronde ${round.number}/${session.rounds.size}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF111827),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${session.date} · ${session.location}",
                        fontSize = 12.sp,
                        color = LightGreyText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier.width(88.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Total Sesi", fontSize = 10.sp, color = LightGreyText)
                        Text(
                            "${session.score}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = BlueScore
                        )
                    }
                }
            }

            // ----- Pemilih ronde -----
            if (session.rounds.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    session.rounds.forEach { r ->
                        RoundTab(
                            number = r.number,
                            recorded = r.arrows.size,
                            capacity = r.capacity,
                            selected = r.number == round.number,
                            onClick = { selectedRound = r.number }
                        )
                    }
                }
            }

            // ----- Kartu skor besar -----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(NavyDark, Color(0xFF0B3A8C))))
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 40.dp, y = (-40).dp)
                        .size(140.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "SKOR RONDE ${round.number}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = SubtitleBlue
                    )
                    Text(
                        "${round.score}",
                        fontSize = 72.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Text(
                        "dari ${round.maxScore} · $percent%",
                        fontSize = 13.sp,
                        color = SubtitleBlue
                    )
                }
            }

            // ----- Progres panah -----
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, BorderGrey)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Panah dicatat (Ronde ${round.number})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF111827),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "$recorded / ${round.capacity}${if (complete) " ✓" else ""}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = GoodColor
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (round.capacity > 0) recorded.toFloat() / round.capacity else 0f
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = GoodColor,
                        trackColor = Color(0xFFE5E7EB)
                    )
                }
            }

            // ----- Statistik -----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox("${round.score}", "Score", Modifier.weight(1f))
                StatBox(String.format(Locale.US, "%.1f", round.average), "Avg", Modifier.weight(1f))
                StatBox("$best", "Best", Modifier.weight(1f))
                StatBox("$recorded", "Arrows", Modifier.weight(1f))
            }

            // ----- Target -----
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, Color(0xFFBBF7D0))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.TrackChanges,
                            contentDescription = null,
                            tint = BadColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "TARGET (RONDE ${round.number})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = Color(0xFF111827),
                            modifier = Modifier.weight(1f)
                        )
                        Row(
                            modifier = Modifier
                                .alpha(if (recorded > 0) 1f else 0.4f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFF1F3F7))
                                .clickable(enabled = recorded > 0, onClick = handleUndo)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Replay,
                                contentDescription = null,
                                tint = GreyText,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Undo",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = GreyText
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    TargetBoard(
                        arrows = round.arrows,
                        points = round.points,
                        onTap = handleTap
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (complete) {
                            if (nextRound != null) {
                                "Ronde ${round.number} selesai. Lanjut ke ronde berikutnya atau tekan Undo untuk mengoreksi."
                            } else {
                                "Semua ronde selesai. Tekan Undo untuk mengoreksi."
                            }
                        } else {
                            "Ketuk target untuk mencatat panah ke-${recorded + 1} dari ${round.capacity}"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        color = LightGreyText
                    )

                    // Tombol lanjut ke ronde berikutnya setelah ronde ini penuh
                    if (complete && nextRound != null) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(HeaderGradient)
                                .clickable { selectedRound = nextRound.number }
                                .padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Lanjut ke Ronde ${nextRound.number}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // ----- Rekap panah -----
            if (round.arrows.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    border = BorderStroke(1.dp, BorderGrey)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "REKAP PANAH",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = GreyText
                        )
                        round.arrows.chunked(6).forEach { rowArrows ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowArrows.forEach { ScoreChip(it) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------- Nilai dari jarak ----------
/** distance = jarak ketukan ke pusat dibagi radius target (0 = tepat di pusat, 1 = tepi luar). */
private fun scoreLabelFor(distance: Float): String = when {
    distance < 0.05f -> "X"
    distance >= 1f -> "M"
    else -> (10 - (distance * 10).toInt()).toString()
}

// ---------- Tab ronde ----------
@Composable
private fun RoundTab(
    number: Int,
    recorded: Int,
    capacity: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val done = capacity > 0 && recorded >= capacity
    val shape = RoundedCornerShape(14.dp)
    val bg = if (selected) NavyLight else Color.White
    val titleColor = if (selected) Color.White else Color(0xFF111827)
    val subColor = when {
        done -> if (selected) Color(0xFF86EFAC) else GoodColor
        selected -> SubtitleBlue
        else -> LightGreyText
    }

    Column(
        modifier = Modifier
            .width(76.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) NavyLight else BorderGrey, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Ronde $number",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = titleColor
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (done) "$recorded/$capacity ✓" else "$recorded/$capacity",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = subColor
        )
    }
}

// ---------- Target interaktif ----------
@Composable
private fun TargetBoard(
    arrows: List<String>,
    points: List<Offset?>,
    onTap: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnTap by rememberUpdatedState(onTap)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val radius = size.width * TargetRadiusFraction
                    currentOnTap(Offset((tap.x - cx) / radius, (tap.y - cy) / radius))
                }
            }
    ) {
        val c = center
        val r = size.width * TargetRadiusFraction

        // Lingkaran luar (halo)
        drawCircle(Color(0xFFEFF1F5), radius = r * 1.18f, center = c)

        // 10 cincin dari luar ke dalam: putih, hitam, biru, merah, kuning
        val ringColors = listOf(
            Color(0xFFFAFAFA), Color(0xFFFAFAFA),
            Color(0xFF4B5056), Color(0xFF4B5056),
            Color(0xFF6BA6F5), Color(0xFF6BA6F5),
            Color(0xFFF27B7B), Color(0xFFF27B7B),
            Color(0xFFFFE066), Color(0xFFFFE066)
        )
        val lineColor = Color.Black.copy(alpha = 0.18f)
        for (i in 0 until 10) {
            val ringRadius = r * (10 - i) / 10f
            drawCircle(ringColors[i], radius = ringRadius, center = c)
            drawCircle(lineColor, radius = ringRadius, center = c, style = Stroke(1.dp.toPx()))
        }
        // Cincin X
        drawCircle(lineColor, radius = r * 0.05f, center = c, style = Stroke(1.dp.toPx()))

        // Tanda + di pusat
        val cross = r * 0.04f
        drawLine(lineColor, Offset(c.x - cross, c.y), Offset(c.x + cross, c.y), 1.dp.toPx())
        drawLine(lineColor, Offset(c.x, c.y - cross), Offset(c.x, c.y + cross), 1.dp.toPx())

        // Panah yang sudah dicatat
        points.forEachIndexed { index, p ->
            if (p != null) {
                val pos = Offset(c.x + p.x * r, c.y + p.y * r)
                val isLast = index == arrows.lastIndex
                val dot = 7.dp.toPx()
                drawCircle(Color.White, radius = dot, center = pos)
                drawCircle(
                    if (isLast) Color(0xFFF97316) else Color(0xFF1D4ED8),
                    radius = dot,
                    center = pos,
                    style = Stroke(2.dp.toPx())
                )
                if (isLast) {
                    drawCircle(Color(0xFFF97316), radius = dot * 0.4f, center = pos)
                }
            }
        }
    }
}

// ---------- Komponen kecil ----------
@Composable
private fun StatBox(value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, BorderGrey)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                value,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111827)
            )
            Text(label, fontSize = 11.sp, color = LightGreyText)
        }
    }
}

@Composable
private fun ScoreChip(arrow: String) {
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
private fun ScoreHeader(imuOn: Boolean, windOn: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HeaderGradient)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.GpsFixed,
            contentDescription = null,
            tint = Color(0xFFF59E0B),
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "IRAS",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 3.sp,
            modifier = Modifier.weight(1f)
        )
        ScoreHeaderChip("IMU", imuOn)
        Spacer(Modifier.width(8.dp))
        ScoreHeaderChip("WIND", windOn)
    }
}

@Composable
private fun ScoreHeaderChip(label: String, active: Boolean) {
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