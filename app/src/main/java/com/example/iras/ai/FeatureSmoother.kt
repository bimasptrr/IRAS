package com.example.iras.ai

class FeatureSmoother(private val alpha: Float = 0.5f) {
    private var smoothedFeatures: BiomechanicsFeatures? = null

    fun smooth(current: BiomechanicsFeatures): BiomechanicsFeatures {
        if (!current.isValid) {
            reset()
            return current
        }

        val prev = smoothedFeatures
        if (prev == null || !prev.isValid) {
            smoothedFeatures = current
            return current
        }

        val smoothed = BiomechanicsFeatures(
            drawArmElbowAngleDeg = alpha * current.drawArmElbowAngleDeg + (1 - alpha) * prev.drawArmElbowAngleDeg,
            bowArmElbowAngleDeg = alpha * current.bowArmElbowAngleDeg + (1 - alpha) * prev.bowArmElbowAngleDeg,
            bodyAlignmentDeviationDeg = alpha * current.bodyAlignmentDeviationDeg + (1 - alpha) * prev.bodyAlignmentDeviationDeg,
            stanceDeviationDeg = alpha * current.stanceDeviationDeg + (1 - alpha) * prev.stanceDeviationDeg,
            featureTimestampMs = current.featureTimestampMs,
            isValid = true
        )

        smoothedFeatures = smoothed
        return smoothed
    }

    fun reset() {
        smoothedFeatures = null
    }
}