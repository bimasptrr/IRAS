package com.example.iras

import com.example.iras.ai.BiomechanicsCalculator
import com.example.iras.ai.Point3D
import com.example.iras.ai.PoseFrame
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
 * Metrik yang diuji: sudut siku lengan penarik, kemiringan bahu-pinggul,
 * dan kemiringan kaki-pinggul. Penilaian posisi siku dan tangan penarik
 * relatif wajah sudah dihapus karena tidak bisa dipertanggungjawabkan
 * secara biomekanik.
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

    /**
     * Pose menarik busur dengan lengan penarik ditekuk, sesuai jangkauan
     * anchor: lengan atas 30 cm dan lengan bawah 26 cm.
     */
    private fun drawPose(
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
    )

    private val calculator = BiomechanicsCalculator(isRightHanded = true)

    @Test
    fun `sudut siku penarik berada di rentang 20 sampai 60 derajat`() {
        val features = calculator.extractFeatures(frame(drawPose(), View.SIDE), 0L)

        val angle = features.drawArmElbowAngleDeg
        assertNotNull(angle)
        assertTrue(
            "Sudut siku penarik saat anchor sekitar 25-45 derajat, bukan $angle",
            angle!! in 20f..60f
        )
    }

    @Test
    fun `sudut tidak berubah untuk ukuran frame potret atau landscape`() {
        val potret = calculator.extractFeatures(frame(drawPose(), View.SIDE, 1080, 1920), 0L)
        val lanskap = calculator.extractFeatures(frame(drawPose(), View.SIDE, 1920, 1080), 0L)

        val a = potret.drawArmElbowAngleDeg!!
        val b = lanskap.drawArmElbowAngleDeg!!
        assertTrue(
            "Sudut harus sama di frame potret dan landscape ($a vs $b)",
            abs(a - b) < 0.5f
        )
    }

    @Test
    fun `kemiringan bahu hanya terbaca dari depan`() {
        val samping = calculator.extractFeatures(frame(drawPose(), View.SIDE), 0L)
        assertNull(
            "Dari samping kemiringan bahu lateral tidak bisa diukur",
            samping.bodyAlignmentDeviationDeg
        )

        val depan = calculator.extractFeatures(frame(drawPose(), View.FRONT), 0L)
        assertNotNull(
            "Dari depan kemiringan bahu harus terbaca",
            depan.bodyAlignmentDeviationDeg
        )
    }

    @Test
    fun `landmark yang tidak terlihat membuat metrik kosong bukan angka tebakan`() {
        val frame = frame(drawPose(), View.SIDE)
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
}
