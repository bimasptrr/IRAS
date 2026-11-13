package com.example.iras.ai

/**
 * Menghaluskan nilai metrik antar frame.
 *
 * Metrik yang sempat tidak terbaca ditahan sebentar supaya tampilan tidak
 * berkedip, tetapi setelah terlalu lama tidak terbaca nilainya dikosongkan
 * agar app tidak menampilkan data basi seolah-olah masih live.
 */
class FeatureSmoother(private val alpha: Float = 0.5f) {

    companion object {
        /** Jumlah frame berturut-turut tanpa data sebelum metrik dikosongkan. */
        private const val MAX_MISSES = 15
    }

    private var previous: BiomechanicsFeatures? = null
    private var misses = 0

    fun smooth(current: BiomechanicsFeatures): BiomechanicsFeatures {
        if (!current.isValid) {
            reset()
            return current
        }

        val prev = previous
        if (prev == null) {
            previous = current
            misses = 0
            return current
        }

        val held = current.copy(
            drawArmElbowAngleDeg = current.drawArmElbowAngleDeg ?: prev.drawArmElbowAngleDeg,
            drawElbowFaceRatio = current.drawElbowFaceRatio ?: prev.drawElbowFaceRatio,
            drawHandFaceRatio = current.drawHandFaceRatio ?: prev.drawHandFaceRatio,
            bowArmElbowAngleDeg = current.bowArmElbowAngleDeg ?: prev.bowArmElbowAngleDeg,
            bodyAlignmentDeviationDeg = current.bodyAlignmentDeviationDeg ?: prev.bodyAlignmentDeviationDeg,
            stanceDeviationDeg = current.stanceDeviationDeg ?: prev.stanceDeviationDeg
        )

        val blank = held.drawArmElbowAngleDeg == null && held.drawElbowFaceRatio == null
        if (blank) {
            misses++
            if (misses > MAX_MISSES) {
                previous = null
                return held
            }
        } else {
            misses = 0
        }

        fun blend(a: Float?, b: Float?): Float? =
            if (a == null || b == null) a ?: b else alpha * a + (1 - alpha) * b

        val smoothed = held.copy(
            drawArmElbowAngleDeg = blend(held.drawArmElbowAngleDeg, prev.drawArmElbowAngleDeg),
            drawElbowFaceRatio = blend(held.drawElbowFaceRatio, prev.drawElbowFaceRatio),
            drawHandFaceRatio = blend(held.drawHandFaceRatio, prev.drawHandFaceRatio),
            bowArmElbowAngleDeg = blend(held.bowArmElbowAngleDeg, prev.bowArmElbowAngleDeg),
            bodyAlignmentDeviationDeg = blend(held.bodyAlignmentDeviationDeg, prev.bodyAlignmentDeviationDeg),
            stanceDeviationDeg = blend(held.stanceDeviationDeg, prev.stanceDeviationDeg)
        )
        previous = smoothed
        return smoothed
    }

    fun reset() {
        previous = null
        misses = 0
    }
}
