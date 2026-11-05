package com.example.iras.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.iras.ui.screens.RoundRecord
import com.example.iras.ui.screens.SessionConfig
import com.example.iras.ui.screens.SessionRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Menyimpan daftar sesi di memori (agar UI langsung responsif) dan menuliskannya
 * ke database Room di latar belakang.
 */
class IrasViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = IrasRepository(IrasDatabase.get(application).dao())

    // Penulisan ke database dilakukan satu per satu sesuai urutan
    private val writeLock = Mutex()

    private val _sessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessions: StateFlow<List<SessionRecord>> = _sessions.asStateFlow()

    init {
        // Muat data yang tersimpan saat aplikasi dibuka
        viewModelScope.launch {
            _sessions.value = markPersonalBest(repository.loadAll())
        }
    }

    /** Membuat sesi baru: roundCount ronde, masing-masing config.shots panah. */
    fun createSession(config: SessionConfig, roundCount: Int) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val date = SimpleDateFormat("dd MMM yyyy", Locale("id", "ID")).format(Date(now))
            val id = writeLock.withLock {
                repository.create(
                    date = date,
                    distance = config.distance,
                    location = config.location,
                    roundCount = roundCount,
                    capacity = config.shots,
                    createdAt = now
                )
            }
            val newSession = SessionRecord(
                id = id,
                date = date,
                distance = config.distance,
                location = config.location,
                rounds = (1..roundCount).map { number ->
                    RoundRecord(number = number, arrows = emptyList(), capacity = config.shots)
                },
                isPB = false
            )
            _sessions.update { markPersonalBest(listOf(newSession) + it) }
        }
    }

    /** Dipanggil setiap skor berubah: UI diperbarui seketika, database menyusul. */
    fun updateSession(updated: SessionRecord) {
        _sessions.update { list ->
            markPersonalBest(list.map { if (it.id == updated.id) updated else it })
        }
        viewModelScope.launch {
            writeLock.withLock { repository.save(updated) }
        }
    }

    fun deleteSession(id: Int) {
        _sessions.update { list -> markPersonalBest(list.filterNot { it.id == id }) }
        viewModelScope.launch {
            writeLock.withLock { repository.delete(id) }
        }
    }

    /**
     * Personal Best = sesi dengan rasio skor tertinggi (jika seri, sesi yang paling lama).
     * Daftar diurutkan dari yang terbaru, jadi indeks yang lebih besar berarti lebih lama.
     */
    private fun markPersonalBest(list: List<SessionRecord>): List<SessionRecord> {
        val bestIndex = list.indices
            .filter { list[it].maxScore > 0 && list[it].score > 0 }
            .maxWithOrNull(
                compareBy<Int> { list[it].score.toFloat() / list[it].maxScore }
                    .thenBy { it }
            )
        return list.mapIndexed { index, session -> session.copy(isPB = index == bestIndex) }
    }
}