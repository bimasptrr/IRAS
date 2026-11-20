package com.example.iras.ai

enum class WindSector(val labelId: String) {
    DEPAN("depan"),
    KANAN("kanan"),
    BELAKANG("belakang"),
    KIRI("kiri")
}

enum class WindLevel(val levelNumber: Int) {
    LEVEL_1_CALM(1),         // < 1.5 m/s
    LEVEL_2_MODERATE(2),     // 1.5 - 5.4 m/s
    LEVEL_3_STRONG(3),       // 5.5 - 7.9 m/s
    LEVEL_4_VERY_STRONG(4)   // >= 8.0 m/s
}

data class WindEvaluation(
    val level: WindLevel,
    val sector: WindSector,
    val normalizedDegrees: Float,
    val speedMps: Float,
    val shouldSpeak: Boolean,
    val isCritical: Boolean,
    val cueCode: String?,
    val ttsMessage: String?
)

class WindEvaluator(
    private val targetBearingDeg: Float = EAST_BEARING_DEG
) {

    companion object {
        const val EAST_BEARING_DEG = 90f // Timur = arah pemanah membidik (Depan)

        const val THRESHOLD_MODERATE_MPS = 1.5f
        const val THRESHOLD_STRONG_MPS = 5.5f
        const val THRESHOLD_VERY_STRONG_MPS = 8.0f

        const val CODE_WIND_MODERATE = "WIND_MODERATE"
        const val CODE_WIND_STRONG = "WIND_STRONG"
        const val CODE_WIND_CRITICAL = "WIND_CRITICAL"
    }

    /**
     * Normalisasi sudut ke rentang [0.0, 360.0).
     */
    fun normalizeDegrees(degrees: Float): Float {
        val mod = degrees % 360f
        return if (mod < 0f) mod + 360f else mod
    }

    /**
     * Mengonversi sudut kompas sensor (0° = Utara, 90° = Timur) menjadi
     * sudut relatif terhadap arah hadap pemanah (default: Timur 90° = 0° Depan).
     */
    fun toShooterRelativeDegrees(compassDegrees: Float): Float {
        return normalizeDegrees(compassDegrees - targetBearingDeg)
    }

    /**
     * Pemetaan sektor arah angin berdasarkan sudut kompas sensor
     * dengan Timur (90°) sebagai arah Depan pemanah:
     * - Depan (Timur): 45° – 134.9° kompas (relatif 315° – 360° & 0° – 44.9°)
     * - Kanan (Selatan): 135° – 224.9° kompas (relatif 45° – 134.9°)
     * - Belakang (Barat): 225° – 314.9° kompas (relatif 135° – 224.9°)
     * - Kiri (Utara): 315° – 360° & 0° – 44.9° kompas (relatif 225° – 314.9°)
     */
    fun mapSector(compassDegrees: Float): WindSector {
        val relative = toShooterRelativeDegrees(compassDegrees)
        return when {
            relative >= 315f || relative < 45f -> WindSector.DEPAN
            relative < 135f -> WindSector.KANAN
            relative < 225f -> WindSector.BELAKANG
            else -> WindSector.KIRI
        }
    }

    /**
     * Klasifikasi level kecepatan angin (m/s):
     * - Level 1 — Tenang: < 1.5 m/s
     * - Level 2 — Sedang: 1.5 – 5.4 m/s
     * - Level 3 — Kencang: 5.5 – 7.9 m/s
     * - Level 4 — Sangat Kencang: >= 8.0 m/s
     */
    fun classifyLevel(speedMps: Float): WindLevel {
        return when {
            speedMps < THRESHOLD_MODERATE_MPS -> WindLevel.LEVEL_1_CALM
            speedMps < THRESHOLD_STRONG_MPS -> WindLevel.LEVEL_2_MODERATE
            speedMps < THRESHOLD_VERY_STRONG_MPS -> WindLevel.LEVEL_3_STRONG
            else -> WindLevel.LEVEL_4_VERY_STRONG
        }
    }

    fun evaluate(wind: WindSnapshot?): WindEvaluation? {
        if (wind == null || !wind.isValid) return null
        if (!wind.speedMps.isFinite() || !wind.directionDeg.isFinite()) return null
        if (wind.speedMps < 0f || wind.directionDeg < 0f) return null

        val relativeDeg = toShooterRelativeDegrees(wind.directionDeg)
        // Level dan sektor dipakai dari nilai yang sudah distabilkan supaya
        // noise sensor tidak memicu perubahan instruksi tiap detik.
        val sector = wind.stableSector ?: mapSector(wind.directionDeg)
        val level = wind.stableLevel ?: classifyLevel(wind.speedMps)

        val message = buildTtsMessage(level, sector)
        val code = when (level) {
            WindLevel.LEVEL_1_CALM -> null
            WindLevel.LEVEL_2_MODERATE -> CODE_WIND_MODERATE
            WindLevel.LEVEL_3_STRONG -> CODE_WIND_STRONG
            WindLevel.LEVEL_4_VERY_STRONG -> CODE_WIND_CRITICAL
        }

        return WindEvaluation(
            level = level,
            sector = sector,
            normalizedDegrees = relativeDeg,
            speedMps = wind.speedMps,
            shouldSpeak = message != null,
            isCritical = level == WindLevel.LEVEL_4_VERY_STRONG,
            cueCode = code,
            ttsMessage = message
        )
    }

    /**
     * Kalimat instruksi dibuat pendek (maks ~3 detik bunyi) supaya tidak
     * tertimpa instruksi berikutnya saat suara langsung di lapangan.
     */
    fun buildTtsMessage(level: WindLevel, sector: WindSector): String? {
        return when (level) {
            WindLevel.LEVEL_1_CALM -> null

            WindLevel.LEVEL_2_MODERATE -> when (sector) {
                WindSector.DEPAN -> "Angin dari depan. Naikkan sedikit bidik."
                WindSector.KANAN -> "Angin ke kanan. Bidik sedikit ke kiri."
                WindSector.BELAKANG -> "Angin dari belakang. Turunkan sedikit bidik."
                WindSector.KIRI -> "Angin ke kiri. Bidik sedikit ke kanan."
            }

            WindLevel.LEVEL_3_STRONG -> when (sector) {
                WindSector.DEPAN -> "Angin kencang dari depan. Naikkan bidik."
                WindSector.KANAN -> "Angin kencang ke kanan. Bidik ke kiri."
                WindSector.BELAKANG -> "Angin kencang dari belakang. Turunkan bidik."
                WindSector.KIRI -> "Angin kencang ke kiri. Bidik ke kanan."
            }

            WindLevel.LEVEL_4_VERY_STRONG ->
                "Batalkan tembakan! Angin sangat kencang."
        }
    }
}