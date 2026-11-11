package com.example.iras.ai

import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.math.atan

class BiomechanicsCalculator(private val isRightHanded: Boolean = true) {

    private fun calculateAngle(a: Point3D, b: Point3D, c: Point3D): Float {
        val v1 = floatArrayOf(a.x - b.x, a.y - b.y, a.z - b.z)
        val v2 = floatArrayOf(c.x - b.x, c.y - b.y, c.z - b.z)

        val dotProduct = v1[0]*v2[0] + v1[1]*v2[1] + v1[2]*v2[2]
        val mag1 = sqrt(v1[0]*v1[0] + v1[1]*v1[1] + v1[2]*v1[2])
        val mag2 = sqrt(v2[0]*v2[0] + v2[1]*v2[1] + v2[2]*v2[2])

        if (mag1 < 1e-5 || mag2 < 1e-5) return 0f

        val angleRad = acos(dotProduct / (mag1 * mag2))
        return (angleRad * 180 / PI).toFloat()
    }

    private fun calculateBodyAlignment(
        shoulderLeft: Point3D, shoulderRight: Point3D,
        hipLeft: Point3D, hipRight: Point3D
    ): Float {
        val shoulderSlope = abs((shoulderRight.y - shoulderLeft.y) / (shoulderRight.x - shoulderLeft.x))
        val hipSlope = abs((hipRight.y - hipLeft.y) / (hipRight.x - hipLeft.x))
        val shoulderAngle = atan(shoulderSlope) * 180 / PI
        val hipAngle = atan(hipSlope) * 180 / PI

        return abs((shoulderAngle - hipAngle).toFloat())
    }

    fun extractFeatures(landmarks: Map<Int, Point3D>, timestamp: Long): BiomechanicsFeatures {
        val invalidReasons = mutableListOf<String>()

        val requiredIds = listOf(11, 12, 13, 14, 15, 16, 23, 24, 27, 28)
        if (!landmarks.keys.containsAll(requiredIds)) {
            invalidReasons.add("Landmark kritis tidak terdeteksi sepenuhnya.")
        }

        if (invalidReasons.isNotEmpty()) {
            return BiomechanicsFeatures(0f, 0f, 0f, 0f, timestamp, false, invalidReasons)
        }

        val drawArmShoulder = if (isRightHanded) landmarks[12]!! else landmarks[11]!!
        val drawArmElbow = if (isRightHanded) landmarks[14]!! else landmarks[13]!!
        val drawArmWrist = if (isRightHanded) landmarks[16]!! else landmarks[15]!!

        val drawArmAngle = calculateAngle(drawArmShoulder, drawArmElbow, drawArmWrist)

        val bodyAlignment = calculateBodyAlignment(
            landmarks[11]!!, landmarks[12]!!, landmarks[23]!!, landmarks[24]!!
        )

        val stanceDeviation = 35f // Placeholder, divalidasi dari referensi 27 & 28 nantinya
        val bowArmAngle = 175f

        return BiomechanicsFeatures(
            drawArmElbowAngleDeg = drawArmAngle,
            bowArmElbowAngleDeg = bowArmAngle,
            bodyAlignmentDeviationDeg = bodyAlignment,
            stanceDeviationDeg = stanceDeviation,
            featureTimestampMs = timestamp,
            isValid = true
        )
    }
}