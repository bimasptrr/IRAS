package com.example.iras.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** Satu sesi latihan. */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val date: String,        // contoh: "21 Apr 2026" (ditampilkan di UI)
    val distance: String,    // contoh: "50m"
    val location: String,
    val createdAt: Long      // waktu pembuatan (ms), dipakai untuk mengurutkan
)

/** Satu ronde di dalam sesi. Terhapus otomatis jika sesinya dihapus. */
@Entity(
    tableName = "rounds",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class RoundEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Int,
    val number: Int,         // nomor ronde (1, 2, 3, ...)
    val capacity: Int        // jumlah panah per ronde (6 atau 12)
)

/** Satu panah di dalam ronde. Terhapus otomatis jika rondenya dihapus. */
@Entity(
    tableName = "arrows",
    foreignKeys = [
        ForeignKey(
            entity = RoundEntity::class,
            parentColumns = ["id"],
            childColumns = ["roundId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("roundId")]
)
data class ArrowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roundId: Long,
    val orderIndex: Int,     // urutan panah di dalam ronde (0, 1, 2, ...)
    val label: String,       // "X", "10", "9", ..., "M"
    val x: Float?,           // posisi ketukan di target (relatif terhadap pusat), bisa kosong
    val y: Float?
)

// ---------- Hubungan antar tabel (untuk membaca sekaligus) ----------

data class RoundWithArrows(
    @Embedded val round: RoundEntity,
    @Relation(parentColumn = "id", entityColumn = "roundId")
    val arrows: List<ArrowEntity>
)

data class SessionWithRounds(
    @Embedded val session: SessionEntity,
    @Relation(
        entity = RoundEntity::class,
        parentColumn = "id",
        entityColumn = "sessionId"
    )
    val rounds: List<RoundWithArrows>
)