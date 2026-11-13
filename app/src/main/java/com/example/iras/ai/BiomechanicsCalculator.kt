package com.example.iras.ai

import kotlin.math.acos
import kotlin.math.hypot

class BiomechanicsCalculator(private val isRightHanded: Boolean = true) {

    companion object {
        const val NOSE = 0
        const val MOUTH_LEFT = 9
        const val MOUTH_RIGHT = 10
        const val SHOULDER_LEFT = 11
        const val ELBOW_LEFT = 13
        const val WRIST_LEFT = 15
        const val SHOULDER_RIGHT = 12
        const val ELBOW_RIGHT = 14
        const val WRIST_RIGHT = 16
        const val HIP_LEFT = 23
        const val HIP_RIGHT = 24
        const val ANKLE_LEFT = 27
        const val ANKLE_RIGHT = 28
        const val MIN_VISIBILITY = 0.5f

        private const val MIN_FACE_SPAN_PX = 12f
    }

    private class FaceReference(private val noseY: Float, chinY: Float) {
        private val span = chinY - noseY
        fun ratioOf(y: Float): Float = (y - noseY) / span
    }

    private class Vec(val x: Float, val y: Float) {
        operator fun minus(o: Vec) = Vec(x - o.x, y - o.y)
        fun len() = hypot(x, y)
        fun dot(o: Vec) = x * o.x + y * o.y
    }

    private fun pixelPoints(frame: PoseFrame): Map<Int, Vec> {
        val w = frame.frameWidth.toFloat()
        val h = frame.frameHeight.toFloat()
        val out = HashMap<Int, Vec>()
        for ((id, lm) in frame.landmarks) {
            if (lm.visibility < MIN_VISIBILITY) continue
            out[id] = Vec(lm.x * w, lm.y * h)
        }
        return out
    }

    private fun angleAt(a: Vec, b: Vec, c: Vec): Float {
        val v1 = Vec(a.x - b.x, a.y - b.y)
        val v2 = Vec(c.x - b.x, c.y - b.y)
        val n = v1.len() * v2.len()
        if (n < 1e-3f) return 0f
        val cos = (v1.dot(v2) / n).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos.toDouble())).toFloat()
    }

    private fun tiltDifference(a: Vec, b: Vec): Float? {
        val la = a.len()
        val lb = b.len()
        if (la < 1e-3f || lb < 1e-3f) return null
        val cos = (a.dot(b) / (la * lb)).coerceIn(-1f, 1f)
        var deg = Math.toDegrees(acos(cos.toDouble())).toFloat()
        if (deg > 90f) deg = 180f - deg
        return deg
    }

    private fun faceReference(points: Map<Int, Vec>): FaceReference? {
        val nose = points[NOSE] ?: return null
        val mouthYs = listOfNotNull(points[MOUTH_LEFT]?.y, points[MOUTH_RIGHT]?.y)
        if (mouthYs.isEmpty()) return null
        val mouthY = mouthYs.average().toFloat()
        val chinY = mouthY + 0.5f * (mouthY - nose.y)
        if (chinY - nose.y < MIN_FACE_SPAN_PX) return null
        return FaceReference(nose.y, chinY)
    }

    fun extractFeatures(frame: PoseFrame, timestampMs: Long): BiomechanicsFeatures {
        if (frame.frameWidth <= 0 || frame.frameHeight <= 0) {
            return invalid(timestampMs, "Ukuran frame belum siap.")
        }
        val p = pixelPoints(frame)

        val drawShoulderId = if (isRightHanded) SHOULDER_RIGHT else SHOULDER_LEFT
        val drawElbowId = if (isRightHanded) ELBOW_RIGHT else ELBOW_LEFT
        val drawWristId = if (isRightHanded) WRIST_RIGHT else WRIST_LEFT
        val bowShoulderId = if (isRightHanded) SHOULDER_LEFT else SHOULDER_RIGHT
        val bowElbowId = if (isRightHanded) ELBOW_LEFT else ELBOW_RIGHT
        val bowWristId = if (isRightHanded) WRIST_LEFT else WRIST_RIGHT

        val drawShoulder = p[drawShoulderId]
        val drawElbow = p[drawElbowId]
        val drawWrist = p[drawWristId]
        if (drawShoulder == null || drawElbow == null || drawWrist == null) {
            return invalid(timestampMs, "Lengan penarik tidak terlihat jelas.")
        }

        val drawAngle = angleAt(drawShoulder, drawElbow, drawWrist)

        val face = faceReference(p)
        val drawElbowFaceRatio = face?.ratioOf(drawElbow.y)
        val drawHandFaceRatio = face?.ratioOf(drawWrist.y)

        val bowShoulder = p[bowShoulderId]
        val bowElbow = p[bowElbowId]
        val bowWrist = p[bowWristId]
        val bowAngle = if (bowShoulder != null && bowElbow != null && bowWrist != null) {
            angleAt(bowShoulder, bowElbow, bowWrist)
        } else null

        val shoulderL = p[SHOULDER_LEFT]
        val shoulderR = p[SHOULDER_RIGHT]
        val hipL = p[HIP_LEFT]
        val hipR = p[HIP_RIGHT]

        var alignment: Float? = null
        var stance: Float? = null
        if (shoulderL != null && shoulderR != null && hipL != null && hipR != null) {
            val torsoLen = Vec(hipL.x - shoulderL.x, hipL.y - shoulderL.y).len()
            val shoulderVec = Vec(shoulderR.x - shoulderL.x, shoulderR.y - shoulderL.y)
            val hipVec = Vec(hipR.x - hipL.x, hipR.y - hipL.y)

            if (torsoLen > 1e-3f && shoulderVec.len() >= 0.4f * torsoLen) {
                alignment = tiltDifference(shoulderVec, hipVec)
            }

            val ankleL = p[ANKLE_LEFT]
            val ankleR = p[ANKLE_RIGHT]
            if (ankleL != null && ankleR != null) {
                val ankleVec = Vec(ankleR.x - ankleL.x, ankleR.y - ankleL.y)
                if (torsoLen > 1e-3f && ankleVec.len() >= 0.25f * torsoLen) {
                    stance = tiltDifference(ankleVec, hipVec)
                }
            }
        }

        return BiomechanicsFeatures(
            drawArmElbowAngleDeg = drawAngle,
            drawElbowFaceRatio = drawElbowFaceRatio,
            drawHandFaceRatio = drawHandFaceRatio,
            bowArmElbowAngleDeg = bowAngle,
            bodyAlignmentDeviationDeg = alignment,
            stanceDeviationDeg = stance,
            featureTimestampMs = timestampMs,
            isValid = true
        )
    }

    private fun invalid(timestampMs: Long, reason: String) = BiomechanicsFeatures(
        drawArmElbowAngleDeg = null,
        drawElbowFaceRatio = null,
        drawHandFaceRatio = null,
        bowArmElbowAngleDeg = null,
        bodyAlignmentDeviationDeg = null,
        stanceDeviationDeg = null,
        featureTimestampMs = timestampMs,
        isValid = false,
        invalidReasons = listOf(reason)
    )
}
