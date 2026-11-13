package com.example.iras.ai

data class Point3D(val x: Float, val y: Float, val z: Float, val visibility: Float)

data class PoseFrame(
    val landmarks: Map<Int, Point3D>,
    val frameWidth: Int,
    val frameHeight: Int,
    val mirrored: Boolean
)

/**
 * Hasil ukur biomekanik untuk satu frame.
 *
 * Semua nilai nullable: null berarti metrik itu tidak bisa diukur pada frame ini
 * (landmark tertutup, di luar frame, atau sudut kamera tidak memungkinkannya).
 * Metrik yang null tidak boleh diisi dengan angka tebakan.
 */
data class BiomechanicsFeatures(
    /** Sudut internal siku lengan penarik (bahu-siku-tangan), derajat. */
    val drawArmElbowAngleDeg: Float?,
    /**
     * Posisi siku lengan penarik relatif wajah: 0 = setinggi ujung hidung,
     * 1 = setinggi dagu (acuan coach untuk anchor).
     */
    val drawElbowFaceRatio: Float?,
    /** Posisi tangan penarik (anchor) relatif wajah, skala sama. */
    val drawHandFaceRatio: Float?,
    /** Sudut internal siku lengan busur, derajat. */
    val bowArmElbowAngleDeg: Float?,
    /** Selisih kemiringan garis bahu dan garis pinggul, derajat. */
    val bodyAlignmentDeviationDeg: Float?,
    /** Selisih kemiringan garis kaki dan garis pinggul, derajat. */
    val stanceDeviationDeg: Float?,
    val featureTimestampMs: Long,
    val isValid: Boolean,
    val invalidReasons: List<String> = emptyList()
)

data class WindSnapshot(
    val speedMps: Float,
    val directionDeg: Float,
    val timestampMs: Long,
    val isValid: Boolean
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
