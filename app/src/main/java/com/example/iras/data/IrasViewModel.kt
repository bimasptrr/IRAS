package com.example.iras.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.iras.ai.*
import com.example.iras.ui.screens.SessionConfig
import com.example.iras.ui.screens.SessionRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Typealias untuk menjamin kompatibilitas jika ada bagian kode yang memanggil TrainingSession
typealias TrainingSession = SessionRecord

class IrasViewModel(application: Application) : AndroidViewModel(application), PoseEstimator.PoseListener {

    private val repository: IrasRepository

    private val _sessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessions: StateFlow<List<SessionRecord>> = _sessions.asStateFlow()

    // Aliased for compatibility if referenced as allSessions
    val allSessions: StateFlow<List<SessionRecord>> = sessions

    init {
        val db = IrasDatabase.get(application)
        repository = IrasRepository(db.dao())
        loadSessions()
    }

    fun loadSessions() {
        viewModelScope.launch {
            _sessions.value = repository.loadAll()
        }
    }

    // --- Fungsi Manajemen Sesi Database Room ---
    fun createSession(config: SessionConfig, roundCount: Int) {
        viewModelScope.launch {
            val formatter = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
            val dateStr = formatter.format(Date())
            repository.create(
                date = dateStr,
                distance = config.distance,
                location = config.location,
                roundCount = roundCount,
                capacity = config.shots,
                createdAt = System.currentTimeMillis()
            )
            loadSessions()
        }
    }

    fun updateSession(session: SessionRecord) {
        viewModelScope.launch {
            repository.save(session)
            loadSessions()
        }
    }

    fun deleteSession(sessionId: Int) {
        viewModelScope.launch {
            repository.delete(sessionId)
            loadSessions()
        }
    }

    fun deleteSession(session: SessionRecord) {
        deleteSession(session.id)
    }

    // --- Logika AI & Biomekanika ---
    private val calculator = BiomechanicsCalculator(isRightHanded = true)
    private val smoother = FeatureSmoother(alpha = 0.5f)
    private val evaluator = TechniqueEvaluator()

    private val _evaluationState = MutableStateFlow<TechniqueEvaluation?>(null)
    val evaluationState: StateFlow<TechniqueEvaluation?> = _evaluationState.asStateFlow()

    // Frame terakhir beserta geometrinya, dipakai overlay skeleton agar sejajar
    // dengan preview kamera (crop FILL_CENTER, rotasi, dan mirror).
    private val _currentFrame = MutableStateFlow<PoseFrame?>(null)
    val currentFrame: StateFlow<PoseFrame?> = _currentFrame.asStateFlow()

    private var currentWind: WindSnapshot? = null

    fun updateWindData(wind: WindSnapshot) {
        currentWind = wind
    }

    override fun onPoseDetected(frame: PoseFrame, timestampMs: Long) {
        // Callback MediaPipe sudah berjalan berurutan di thread result listener,
        // jadi update StateFlow dilakukan langsung agar frame tidak tertukar urutan.
        _currentFrame.value = frame

        val rawFeatures = calculator.extractFeatures(frame.landmarks, timestampMs)
        val smoothedFeatures = smoother.smooth(rawFeatures)
        _evaluationState.value = evaluator.evaluate(smoothedFeatures, currentWind)
    }

    override fun onError(error: String) {
        _currentFrame.value = null
        smoother.reset()
    }

    fun resetSession() {
        _currentFrame.value = null
        smoother.reset()
        _evaluationState.value = null
        currentWind = null
    }
}
