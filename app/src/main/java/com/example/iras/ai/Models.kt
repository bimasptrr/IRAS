package com.example.iras.ai

data class Point3D(val x: Float, val y: Float, val z: Float, val visibility: Float)

data class PoseFrame(
    val landmarks: Map<Int, Point3D>,
    val frameWidth: Int,
    val frameHeight: Int,
    val mirrored: Boolean
)

data class BiomechanicsFeatures(
    val drawArmElbowAngleDeg: Float?,
    val bodyAlignmentDeviationDeg: Float?,
    val stanceDeviationDeg: Float?,
    val featureTimestampMs: Long,
    val isValid: Boolean,
    val invalidReasons: List<String> = emptyList()
)

data class WindSnapshot(
    val speedMps: Float,
    val directionDeg: Float,
    val timestampMs: Long,
    val isValid: Boolean,
    // Level dan sektor yang sudah disstabilkan (hysteresis). Null = pakai nilai mentah.
    val stableLevel: WindLevel? = null,
    val stableSector: WindSector? = null
)

/**
 * Instruksi suara siap diucapkan.
 * [interrupt] = true berarti instruksi ini boleh memotong suara yang sedang
 * berjalan - dipakai untuk peringatan "batalkan tembakan".
 */
data class VoiceCue(
    val text: String,
    val interrupt: Boolean = false
)

data class TechniqueEvaluation(
    val isValid: Boolean,
    val techniqueStatus: String, // "IDEAL", "NEEDS_CORRECTION", "INVALID_DATA"
    val metrics: BiomechanicsFeatures,
    val issues: List<FeedbackIssue>,
    val feedbackText: String,
    val timestampMs: Long
)

data class FeedbackIssue(
    val code: String,
    val severity: String,
    val message: String
)
