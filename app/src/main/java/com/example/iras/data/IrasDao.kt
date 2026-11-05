package com.example.iras.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class IrasDao {

    @Insert
    abstract suspend fun insertSession(session: SessionEntity): Long

    @Insert
    abstract suspend fun insertRounds(rounds: List<RoundEntity>)

    @Insert
    abstract suspend fun insertArrows(arrows: List<ArrowEntity>)

    @Query("SELECT * FROM rounds WHERE sessionId = :sessionId")
    abstract suspend fun getRounds(sessionId: Int): List<RoundEntity>

    @Query("DELETE FROM arrows WHERE roundId IN (SELECT id FROM rounds WHERE sessionId = :sessionId)")
    abstract suspend fun deleteArrowsOfSession(sessionId: Int)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    abstract suspend fun deleteSession(sessionId: Int)

    /** Semua sesi beserta ronde dan panahnya, yang terbaru di atas. */
    @Transaction
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC, id DESC")
    abstract suspend fun getAll(): List<SessionWithRounds>

    /** Membuat sesi baru beserta rondenya (kosong). Mengembalikan id sesi. */
    @Transaction
    open suspend fun createSession(
        session: SessionEntity,
        roundCount: Int,
        capacity: Int
    ): Int {
        val id = insertSession(session).toInt()
        insertRounds(
            (1..roundCount).map { number ->
                RoundEntity(sessionId = id, number = number, capacity = capacity)
            }
        )
        return id
    }

    /**
     * Mengganti seluruh panah milik sebuah sesi.
     * arrowsByRound: nomor ronde -> daftar panah di ronde itu.
     */
    @Transaction
    open suspend fun replaceArrows(
        sessionId: Int,
        arrowsByRound: Map<Int, List<ArrowEntity>>
    ) {
        val idByNumber = getRounds(sessionId).associate { it.number to it.id }
        deleteArrowsOfSession(sessionId)
        val toInsert = arrowsByRound.flatMap { (number, arrows) ->
            val roundId = idByNumber[number] ?: return@flatMap emptyList()
            arrows.map { it.copy(roundId = roundId) }
        }
        if (toInsert.isNotEmpty()) insertArrows(toInsert)
    }
}