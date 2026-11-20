package com.example.iras.data

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.iras.ai.*
import com.example.iras.ble.WindData
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

data class WindVoiceCue(
    val text: String,
    val isCritical: Boolean,
    val timestampMs: Long
)

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
    // Kalkulator dibuat ulang kalau tangan penarik diganti.
    private var calculator = BiomechanicsCalculator(isRightHanded = true)
    private val smoother = FeatureSmoother(alpha = 0.5f)
    private val windEvaluator = WindEvaluator()
    private val windStabilizer = WindStabilizer()
    private val evaluator = TechniqueEvaluator(windEvaluator)

    /** Tangan yang menarik tali busur. */
    var isRightHanded by mutableStateOf(true)
        private set

    fun updateHandedness(right: Boolean) {
        if (right == isRightHanded) return
        isRightHanded = right
        calculator = BiomechanicsCalculator(isRightHanded = right)
        smoother.reset()
        _evaluationState.value = null
    }

    private val _evaluationState = MutableStateFlow<TechniqueEvaluation?>(null)
    val evaluationState: StateFlow<TechniqueEvaluation?> = _evaluationState.asStateFlow()

    // Frame terakhir beserta geometrinya, dipakai overlay skeleton agar sejajar
    // dengan preview kamera (crop FILL_CENTER, rotasi, dan mirror).
    private val _currentFrame = MutableStateFlow<PoseFrame?>(null)
    val currentFrame: StateFlow<PoseFrame?> = _currentFrame.asStateFlow()

    // Penanda apakah mode "Mulai Analisis" sedang aktif
    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private var currentWind: WindSnapshot? = null

    private val _windEvaluation = MutableStateFlow<WindEvaluation?>(null)
    val windEvaluation: StateFlow<WindEvaluation?> = _windEvaluation.asStateFlow()

    fun updateWindData(wind: WindSnapshot) {
        // Level dan sektor distabilkan dulu supaya fluktuasi sensor tidak
        // memicu instruksi suara baru tiap detik.
        val stable = windStabilizer.stabilize(wind, SystemClock.elapsedRealtime())
        currentWind = stable
        _windEvaluation.value = windEvaluator.evaluate(stable)
    }

    /** Menerima paket WindData langsung dari BleManager.wind. */
    fun onBleWindReceived(windData: WindData?) {
        if (windData == null) {
            currentWind = null
            _windEvaluation.value = null
            return
        }
        val snapshot = WindSnapshot(
            speedMps = windData.speed,
            directionDeg = windData.degrees,
            timestampMs = windData.timestamp,
            isValid = windData.directionOk
        )
        updateWindData(snapshot)
    }

    override fun onPoseDetected(frame: PoseFrame, timestampMs: Long) {
        // Callback MediaPipe hanya berjalan setelah tombol "Mulai Analisis" ditekan.
        if (!_isAnalyzing.value) {
            _isAnalyzing.value = true
        }
        _currentFrame.value = frame

        val rawFeatures = calculator.extractFeatures(frame, timestampMs)
        val smoothedFeatures = smoother.smooth(rawFeatures)
        _evaluationState.value = evaluator.evaluate(smoothedFeatures, currentWind)
    }

    override fun onError(error: String) {
        _currentFrame.value = null
        smoother.reset()
    }

    fun resetSession() {
        _isAnalyzing.value = false
        _currentFrame.value = null
        smoother.reset()
        _evaluationState.value = null
        currentWind = null
        windStabilizer.reset()
        _windEvaluation.value = null
    }
}