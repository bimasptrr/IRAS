package com.example.iras

import com.example.iras.ai.BiomechanicsCalculator
import com.example.iras.ai.Point3D
import com.example.iras.ai.PoseFrame
import com.example.iras.ai.TechniqueEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Uji geometri metrik biomekanik dengan pose sintetis.
 *
 * Pose dibuat di koordinat dunia (meter): +X arah tembak, +Y ke atas,
 * +Z ke kanan pemanah. Pemanah tangan kanan memakai lengan kanan sebagai
 * lengan penarik.
 *
 * Acuan anchor mengikuti coach: siku lengan penarik saat anchor berada di
 * antara "bawah hidung sampai dagu", sehingga pose di bawah memakai tinggi
 * wajah dunia: ujung hidung +0.29 m, sudut mulut +0.235 m (dagu diperkirakan
 * +0.2075 m), dan siku penarik +0.225 m.
 */
class BiomechanicsCalculatorTest {

    private enum class View { SIDE, FRONT }

    /** Proyeksi pose dunia ke koordinat landmark ternormalisasi ala MediaPipe. */
    private fun frame(
        world: Map<Int, Triple<Float, Float, Float>>,
        view: View,
        width: Int = 1080,
        height: Int = 1920
    ): PoseFrame {
        val pxPerMeter = 1000f
        val landmarks = world.mapValues { (_, xyz) ->
            val (x, y, z) = xyz
            val imgX: Float
            val imgY = -y
            val depth: Float
            when (view) {
                View.SIDE -> {
                    imgX = x
                    depth = 5f - z
                }
                View.FRONT -> {
                    imgX = -z
                    depth = 5f - x
                }
            }
            Point3D(
                x = imgX * pxPerMeter / width,
                y = imgY * pxPerMeter / height,
                z = depth * pxPerMeter / width,
                visibility = 0.95f
            )
        }
        return PoseFrame(landmarks, width, height, mirrored = false)
    }

    /** Wajah pemanah: ujung hidung, sudut mulut kiri dan kanan. */
    private val head = mapOf(
        0 to Triple(0.05f, 0.29f, 0.10f),     // ujung hidung
        9 to Triple(0.035f, 0.235f, 0.10f),  // sudut mulut kiri
        10 to Triple(0.035f, 0.235f, 0.095f)  // sudut mulut kanan
    )

    /**
     * Pose anchor yang benar menurut coach: siku penarik setinggi antara
     * hidung dan dagu, tangan penarik di dekat rahang. Panjang lengan atas
     * 30 cm dan lengan bawah 26 cm.
     */
    private fun anchorPose(
        elbow: Triple<Float, Float, Float> = Triple(-0.197f, 0.225f, 0.173f),
        hand: Triple<Float, Float, Float> = Triple(0.05f, 0.19f, 0.10f)
    ): Map<Int, Triple<Float, Float, Float>> = mapOf(
        11 to Triple(0f, 0f, -0.15f),          // bahu kiri
        12 to Triple(0f, 0f, 0.15f),           // bahu kanan
        13 to Triple(0.28f, -0.02f, -0.18f),   // siku busur
        14 to elbow,                           // siku penarik
        15 to Triple(0.55f, 0f, -0.20f),       // tangan busur
        16 to hand,                            // tangan penarik
        23 to Triple(0f, -0.50f, -0.12f),
        24 to Triple(0f, -0.50f, 0.12f),
        27 to Triple(0f, -0.95f, -0.15f),
        28 to Triple(0f, -0.95f, 0.15f)
    ) + head

    private val calculator = BiomechanicsCalculator(isRightHanded = true)

    @Test
    fun `anchor yang benar tidak memicu koreksi posisi siku`() {
        val features = calculator.extractFeatures(frame(anchorPose(), View.SIDE), 0L)

        assertTrue("Data harus valid", features.isValid)
        val ratio = features.drawElbowFaceRatio
        assertNotNull("Posisi siku relatif wajah harus terbaca", ratio)
        assertTrue(
            "Siku pada anchor coach berada antara hidung dan dagu, bukan $ratio",
            ratio!! in 0f..1f
        )

        val issues = TechniqueEvaluator().evaluate(features, null).issues
        assertTrue(
            "Anchor benar tidak boleh memicu-too-high atau kurang-naik, tapi dapat: $issues",
            issues.none { it.code == "DRAW_ELBOW_TOO_HIGH" || it.code == "DRAW_ELBOW_TOO_LOW" }
        )
    }

    @Test
    fun `siku yang turun dilaporkan kurang naik`() {
        // Siku turun ke bawah bahu dan tangan penarik ikut turun dari rahang.
        val pose = anchorPose(
            elbow = Triple(-0.25f, 0.05f, 0.20f),
            hand = Triple(0.03f, 0.13f, 0.10f)
        )
        val features = calculator.extractFeatures(frame(pose, View.SIDE), 0L)

        val ratio = features.drawElbowFaceRatio
        assertNotNull(ratio)
        assertTrue("Siku turun harus jauh di bawah dagu, bukan $ratio", ratio!! > 1f)

        val issues = TechniqueEvaluator().evaluate(features, null).issues
        assertTrue(
            "Siku turun harus memicu DRAW_ELBOW_TOO_LOW",
            issues.any { it.code == "DRAW_ELBOW_TOO_LOW" }
        )
    }

    @Test
    fun `siku di atas ujung hidung dilaporkan terlalu tinggi`() {
        val pose = anchorPose(elbow = Triple(-0.12f, 0.32f, 0.16f))
        val features = calculator.extractFeatures(frame(pose, View.SIDE), 0L)

        val ratio = features.drawElbowFaceRatio
        assertNotNull(ratio)
        assertTrue("Siku di atas hidung harus bernilai negatif, bukan $ratio", ratio!! < 0f)

        val issues = TechniqueEvaluator().evaluate(features, null).issues
        assertTrue(
            "Siku di atas hidung harus memicu DRAW_ELBOW_TOO_HIGH",
            issues.any { it.code == "DRAW_ELBOW_TOO_HIGH" }
        )
    }

    @Test
    fun `sudut siku penarik berada di rentang anchor bukan 155 derajat`() {
        val features = calculator.extractFeatures(frame(anchorPose(), View.SIDE), 0L)
        val angle = features.drawArmElbowAngleDeg
        assertNotNull(angle)
        assertTrue(
            "Sudut siku penarik di anchor sekitar 25-45 derajat, bukan $angle",
            angle!! in 20f..60f
        )
    }

    @Test
    fun `anchor tangan penarik terbaca di area rahang`() {
        val features = calculator.extractFeatures(frame(anchorPose(), View.SIDE), 0L)
        val ratio = features.drawHandFaceRatio
        assertNotNull(ratio)
        assertTrue("Tangan penarik harus di area rahang, bukan $ratio", ratio!! in 0.3f..1.6f)

        val issues = TechniqueEvaluator().evaluate(features, null).issues
        assertTrue(
            "Anchor benar tidak boleh memicu ANCHOR_OFF, tapi dapat: $issues",
            issues.none { it.code == "ANCHOR_OFF" }
        )
    }

    @Test
    fun `lengan busur terbaca hampir lurus`() {
        val features = calculator.extractFeatures(frame(anchorPose(), View.SIDE), 0L)
        val bow = features.bowArmElbowAngleDeg
        assertNotNull(bow)
        assertTrue("Lengan busur sekitar 165-175 derajat, bukan $bow", bow!! in 160f..178f)
    }

    @Test
    fun `sudut tidak berubah though ukuran frame potret atau landscape`() {
        val potret = calculator.extractFeatures(frame(anchorPose(), View.SIDE, 1080, 1920), 0L)
        val lanskap = calculator.extractFeatures(frame(anchorPose(), View.SIDE, 1920, 1080), 0L)

        val a = potret.drawArmElbowAngleDeg!!
        val b = lanskap.drawArmElbowAngleDeg!!
        assertTrue(
            "Sudut harus sama di frame potret dan landscape ($a vs $b)",
            abs(a - b) < 0.5f
        )
    }

    @Test
    fun `kemiringan bahu hanya terbaca dari depan`() {
        val samping = calculator.extractFeatures(frame(anchorPose(), View.SIDE), 0L)
        assertNull(
            "Dari samping kemiringan bahu lateral tidak bisa diukur",
            samping.bodyAlignmentDeviationDeg
        )

        val depan = calculator.extractFeatures(frame(anchorPose(), View.FRONT), 0L)
        assertNotNull(
            "Dari depan kemiringan bahu harus terbaca",
            depan.bodyAlignmentDeviationDeg
        )
    }

    @Test
    fun `landmark yang tidak terlihat membuat metrik kosong bukan angka tebakan`() {
        val frame = frame(anchorPose(), View.SIDE)
        // Turunkan visibilitas tangan penarik supaya tidak terbaca.
        val landmarks = frame.landmarks.mapValues { (key, p) ->
            Point3D(p.x, p.y, p.z, if (key == 16) 0.1f else p.visibility)
        }
        val lowVisibility = PoseFrame(landmarks, frame.frameWidth, frame.frameHeight, false)

        val features = calculator.extractFeatures(lowVisibility, 0L)
        assertTrue("Tanpa lengan penarik data tidak valid", !features.isValid)
        assertNull(features.drawArmElbowAngleDeg)
        assertEquals(
            "Tidak boleh ada metrik berisi angka tebakan",
            null,
            features.bodyAlignmentDeviationDeg
        )
    }

    @Test
    fun `wajah yang tidak terbaca membuat posisi siku kosong`() {
        val pose = anchorPose()
        val landmarks = pose.mapValues { (key, xyz) ->
            Point3D(
                x = xyz.first,
                y = -xyz.second,
                z = xyz.third,
                visibility = if (key == 0 || key == 9 || key == 10) 0.1f else 0.95f
            )
        }.toMutableMap()
        val poseFrame = PoseFrame(landmarks, 1080, 1920, false)

        val features = calculator.extractFeatures(poseFrame, 0L)
        assertNull("Tanpa landmark wajah posisi siku tidak boleh ditebak", features.drawElbowFaceRatio)
        assertNull(features.drawHandFaceRatio)
        assertNotNull("Sudut siku tetap terbaca", features.drawArmElbowAngleDeg)
    }
}
