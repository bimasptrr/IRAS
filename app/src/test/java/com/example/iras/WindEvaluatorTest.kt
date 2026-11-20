package com.example.iras

import com.example.iras.ai.BiomechanicsFeatures
import com.example.iras.ai.TechniqueEvaluator
import com.example.iras.ai.VoiceCuePlanner
import com.example.iras.ai.WindEvaluator
import com.example.iras.ai.WindLevel
import com.example.iras.ai.WindSector
import com.example.iras.ai.WindSnapshot
import com.example.iras.ai.WindStabilizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindEvaluatorTest {

    private val evaluator = WindEvaluator()

    @Test
    fun `normalizeDegrees handles wrap around and negative values`() {
        assertEquals(0f, evaluator.normalizeDegrees(0f), 0.001f)
        assertEquals(0f, evaluator.normalizeDegrees(360f), 0.001f)
        assertEquals(315f, evaluator.normalizeDegrees(-45f), 0.001f)
        assertEquals(45f, evaluator.normalizeDegrees(405f), 0.001f)
    }

    @Test
    fun `mapSector maps compass angles with East 90 deg as Depan`() {
        // Depan (Timur): 45° – 134.9° kompas
        assertEquals(WindSector.DEPAN, evaluator.mapSector(45f))
        assertEquals(WindSector.DEPAN, evaluator.mapSector(90f))
        assertEquals(WindSector.DEPAN, evaluator.mapSector(134.9f))

        // Kanan (Selatan): 135° – 224.9° kompas
        assertEquals(WindSector.KANAN, evaluator.mapSector(135f))
        assertEquals(WindSector.KANAN, evaluator.mapSector(180f))
        assertEquals(WindSector.KANAN, evaluator.mapSector(224.9f))

        // Belakang (Barat): 225° – 314.9° kompas
        assertEquals(WindSector.BELAKANG, evaluator.mapSector(225f))
        assertEquals(WindSector.BELAKANG, evaluator.mapSector(270f))
        assertEquals(WindSector.BELAKANG, evaluator.mapSector(314.9f))

        // Kiri (Utara): 315° – 360° dan 0° – 44.9° kompas
        assertEquals(WindSector.KIRI, evaluator.mapSector(315f))
        assertEquals(WindSector.KIRI, evaluator.mapSector(0f))
        assertEquals(WindSector.KIRI, evaluator.mapSector(44.9f))
    }

    @Test
    fun `level 1 calm wind stays silent`() {
        val result = evaluator.evaluate(
            WindSnapshot(speedMps = 1.4f, directionDeg = 90f, timestampMs = 1000L, isValid = true)
        )
        assertNotNull(result)
        assertEquals(WindLevel.LEVEL_1_CALM, result!!.level)
        assertFalse(result.shouldSpeak)
        assertFalse(result.isCritical)
        assertNull(result.ttsMessage)
    }

    @Test
    fun `level 2 moderate wind produces expected Indonesian TTS across all 4 sectors`() {
        // 90° = Timur (Depan)
        val depan = evaluator.evaluate(WindSnapshot(1.5f, 90f, 1000L, true))!!
        assertEquals(WindLevel.LEVEL_2_MODERATE, depan.level)
        assertEquals("Angin dari depan. Naikkan sedikit bidik.", depan.ttsMessage)

        // 180° = Selatan (Kanan)
        val kanan = evaluator.evaluate(WindSnapshot(3.2f, 180f, 1000L, true))!!
        assertEquals("Angin ke kanan. Bidik sedikit ke kiri.", kanan.ttsMessage)

        // 270° = Barat (Belakang)
        val belakang = evaluator.evaluate(WindSnapshot(5.4f, 270f, 1000L, true))!!
        assertEquals("Angin dari belakang. Turunkan sedikit bidik.", belakang.ttsMessage)

        // 0° = Utara (Kiri)
        val kiri = evaluator.evaluate(WindSnapshot(2.5f, 0f, 1000L, true))!!
        assertEquals("Angin ke kiri. Bidik sedikit ke kanan.", kiri.ttsMessage)
    }

    @Test
    fun `level 3 strong wind produces expected Indonesian TTS across all 4 sectors`() {
        // 80° = Timur (Depan)
        val depan = evaluator.evaluate(WindSnapshot(5.5f, 80f, 1000L, true))!!
        assertEquals(WindLevel.LEVEL_3_STRONG, depan.level)
        assertEquals("Angin kencang dari depan. Naikkan bidik.", depan.ttsMessage)

        // 190° = Selatan (Kanan)
        val kanan = evaluator.evaluate(WindSnapshot(6.8f, 190f, 1000L, true))!!
        assertEquals("Angin kencang ke kanan. Bidik ke kiri.", kanan.ttsMessage)

        // 280° = Barat (Belakang)
        val belakang = evaluator.evaluate(WindSnapshot(7.9f, 280f, 1000L, true))!!
        assertEquals("Angin kencang dari belakang. Turunkan bidik.", belakang.ttsMessage)

        // 10° = Utara (Kiri)
        val kiri = evaluator.evaluate(WindSnapshot(6.0f, 10f, 1000L, true))!!
        assertEquals("Angin kencang ke kiri. Bidik ke kanan.", kiri.ttsMessage)
    }

    @Test
    fun `level 4 very strong wind produces critical let-down warning`() {
        // 185° = Selatan (Kanan)
        val result = evaluator.evaluate(WindSnapshot(8.0f, 185f, 1000L, true))!!
        assertEquals(WindLevel.LEVEL_4_VERY_STRONG, result.level)
        assertTrue(result.isCritical)
        assertTrue(result.shouldSpeak)
        assertEquals(
            "Batalkan tembakan! Angin sangat kencang.",
            result.ttsMessage
        )
    }

    @Test
    fun `invalid sensor data returns null`() {
        assertNull(evaluator.evaluate(WindSnapshot(4.0f, -1f, 1000L, false)))
        assertNull(evaluator.evaluate(WindSnapshot(4.0f, -1f, 1000L, true)))
    }

    @Test
    fun `voiceCuePlanner applies cooldown for same wind but speaks immediately on sector change or level 4`() {
        val planner = VoiceCuePlanner(windRepeatMs = 11_000L)

        // Detik 1: Angin sedang dari Selatan (180° = kanan) -> berbunyi
        val w1 = evaluator.evaluate(WindSnapshot(3.0f, 180f, 1_000L, true))
        assertEquals(
            "Angin ke kanan. Bidik sedikit ke kiri.",
            planner.nextWindOnly(w1, 1_000L)?.text
        )

        // Detik 2 (1 Hz paket sama): ditahan oleh cooldown 11 detik
        val w2 = evaluator.evaluate(WindSnapshot(3.1f, 182f, 2_000L, true))
        assertNull(planner.nextWindOnly(w2, 2_000L))

        // Detik 4: Sektor arah angin berubah ke Utara (0° = kiri) -> langsung berbunyi
        val w3 = evaluator.evaluate(WindSnapshot(3.1f, 0f, 4_000L, true))
        assertEquals(
            "Angin ke kiri. Bidik sedikit ke kanan.",
            planner.nextWindOnly(w3, 4_000L)?.text
        )

        // Detik 4.5 (500ms kemudian): Tiba-tiba angin Level 4 -> interupsi segera tanpa menunggu minGap
        val w4 = evaluator.evaluate(WindSnapshot(8.5f, 0f, 4_500L, true))
        assertEquals(
            "Batalkan tembakan! Angin sangat kencang.",
            planner.nextWindOnly(w4, 4_500L)?.text
        )
    }

    @Test
    fun `level 4 wind overrides posture issues in VoiceCuePlanner`() {
        val techEvaluator = TechniqueEvaluator(evaluator)
        val planner = VoiceCuePlanner()

        val badPostureFeatures = BiomechanicsFeatures(
            drawArmElbowAngleDeg = 10f, // tertutup (< 20f)
            bodyAlignmentDeviationDeg = 2f,
            stanceDeviationDeg = 2f,
            featureTimestampMs = 1_000L,
            isValid = true
        )
        // 270° = Barat (Belakang)
        val criticalWind = WindSnapshot(speedMps = 9.0f, directionDeg = 270f, timestampMs = 1_000L, isValid = true)

        val eval = techEvaluator.evaluate(badPostureFeatures, criticalWind)
        val spoken = planner.next(eval, 1_000L)

        assertEquals(
            "Batalkan tembakan! Angin sangat kencang.",
            spoken?.text
        )
    }

    @Test
    fun `stabilizer hanya mengubah level dan sektor setelah bertahan cukup lama`() {
        val stabilizer = WindStabilizer()

        // Sampel pertama langsung dipakai sebagai patokan.
        val awal = stabilizer.stabilize(WindSnapshot(3.0f, 180f, 1_000L, true), 1_000L)
        assertEquals(WindLevel.LEVEL_2_MODERATE, awal.stableLevel)
        assertEquals(WindSector.KANAN, awal.stableSector)

        // Sektor berbalik ke kiri, tapi belum cukup lama untuk diakui.
        val flip = stabilizer.stabilize(WindSnapshot(3.0f, 0f, 2_000L, true), 2_000L)
        assertEquals(WindSector.KANAN, flip.stableSector)

        val tahan = stabilizer.stabilize(WindSnapshot(3.0f, 0f, 3_000L, true), 3_000L)
        assertEquals(WindSector.KANAN, tahan.stableSector)

        // Setelah 1,5 detik konsisten, sektor baru diakui.
        val tetap = stabilizer.stabilize(WindSnapshot(3.0f, 0f, 4_000L, true), 4_000L)
        assertEquals(WindSector.KIRI, tetap.stableSector)

        // Level 4 butuh 2 detik bertahan sebelum jadi kritis.
        val naik = stabilizer.stabilize(WindSnapshot(9.0f, 0f, 5_000L, true), 5_000L)
        assertEquals(WindLevel.LEVEL_2_MODERATE, naik.stableLevel)

        val tahan2 = stabilizer.stabilize(WindSnapshot(9.0f, 0f, 6_000L, true), 6_000L)
        assertEquals(WindLevel.LEVEL_2_MODERATE, tahan2.stableLevel)

        val kritis = stabilizer.stabilize(WindSnapshot(9.0f, 0f, 7_000L, true), 7_000L)
        assertEquals(WindLevel.LEVEL_4_VERY_STRONG, kritis.stableLevel)
    }
}