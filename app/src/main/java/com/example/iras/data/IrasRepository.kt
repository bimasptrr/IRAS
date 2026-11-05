package com.example.iras.data

import androidx.compose.ui.geometry.Offset
import com.example.iras.ui.screens.RoundRecord
import com.example.iras.ui.screens.SessionRecord

/**
 * Jembatan antara tabel Room dan model yang dipakai UI (SessionRecord, RoundRecord).
 */
class IrasRepository(private val dao: IrasDao) {

    /** Membaca semua sesi (terbaru di atas). Penanda PB dihitung di ViewModel. */
    suspend fun loadAll(): List<SessionRecord> = dao.getAll().map { it.toRecord() }

    /** Menyimpan sesi baru beserta rondenya, mengembalikan id sesi. */
    suspend fun create(
        date: String,
        distance: String,
        location: String,
        roundCount: Int,
        capacity: Int,
        createdAt: Long
    ): Int = dao.createSession(
        session = SessionEntity(
            date = date,
            distance = distance,
            location = location,
            createdAt = createdAt
        ),
        roundCount = roundCount,
        capacity = capacity
    )

    /** Menyimpan panah-panah milik sebuah sesi (dipanggil setiap skor berubah). */
    suspend fun save(session: SessionRecord) {
        val arrowsByRound = session.rounds.associate { round ->
            round.number to round.arrows.mapIndexed { index, label ->
                val point = round.points.getOrNull(index)
                ArrowEntity(
                    roundId = 0, // diisi di DAO
                    orderIndex = index,
                    label = label,
                    x = point?.x,
                    y = point?.y
                )
            }
        }
        dao.replaceArrows(session.id, arrowsByRound)
    }

    suspend fun delete(sessionId: Int) = dao.deleteSession(sessionId)
}

// ---------- Konversi tabel -> model UI ----------

private fun SessionWithRounds.toRecord(): SessionRecord = SessionRecord(
    id = session.id,
    date = session.date,
    distance = session.distance,
    location = session.location,
    rounds = rounds
        .sortedBy { it.round.number }
        .map { it.toRecord() },
    isPB = false
)

private fun RoundWithArrows.toRecord(): RoundRecord {
    val sorted = arrows.sortedBy { it.orderIndex }
    return RoundRecord(
        number = round.number,
        arrows = sorted.map { it.label },
        points = sorted.map { arrow ->
            val x = arrow.x
            val y = arrow.y
            if (x != null && y != null) Offset(x, y) else null
        },
        capacity = round.capacity
    )
}