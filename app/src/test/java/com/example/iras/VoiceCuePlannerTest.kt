package com.example.iras

import com.example.iras.ai.BiomechanicsFeatures
import com.example.iras.ai.TechniqueEvaluation
import com.example.iras.ai.TechniqueEvaluator
import com.example.iras.ai.VoiceCuePlanner
import com.example.iras.ai.WindSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji suara koreksi: pujian "Posisi sudah ideal." harus mengikuti kondisi
 * realtime yang sama dengan metrik yang tampil di layar, bukan diberikan
 * sebagai hadiah setelah ada koreksi.
 */
class VoiceCuePlannerTest {

    private val evaluator = TechniqueEvaluator()

    private fun features(
        angle: Float? = 40f,
        align: Float? = 2f,
        stance: Float? = 3f,
        valid: Boolean = true
    ) = BiomechanicsFeatures(
        drawArmElbowAngleDeg = angle,
        bodyAlignmentDeviationDeg = align,
        stanceDeviationDeg = stance,
        featureTimestampMs = 0L,
        isValid = valid,
        invalidReasons = if (valid) emptyList() else listOf("Lengan penarik tidak terlihat jelas.")
    )

    /** Mensimulasikan pemanggilan per frame seperti yang terjadi di Monitor. */
    private fun spoken(planner: VoiceCuePlanner, eval: TechniqueEvaluation): List<String> {
        val out = mutableListOf<String>()
        var t = 0L
        while (t <= 20_000L) {
            val text = planner.next(eval, t)
            if (text != null) out.add(text)
            t += 500L
        }
        return out
    }

    @Test
    fun `postur benar dibacakan sebagai posisi ideal`() {
        val planner = VoiceCuePlanner()
        val hasil = spoken(planner, evaluator.evaluate(features(), null))

        assertEquals("Posisi sudah ideal.", hasil.first())
        assertTrue("Posisi ideal harus diulang berkala: $hasil", hasil.size >= 2)
    }

    @Test
    fun `postur salah membatalkan pujian dan membacakan koreksi`() {
        val planner = VoiceCuePlanner()
        val hasil = spoken(planner, evaluator.evaluate(features(angle = 10f), null))

        assertTrue(
            "Koreksi sudut siku harus dibacakan: $hasil",
            hasil.any { it == "Sudut siku lengan penarik terlalu tertutup." }
        )
        assertTrue(
            "Posisi tidak boleh dipuji saat postur salah: $hasil",
            hasil.none { it == "Posisi sudah ideal." }
        )
    }

    @Test
    fun `data tidak terbaca tidak pernah memberi pujian posisi ideal`() {
        val planner = VoiceCuePlanner()
        val hasil = spoken(planner, evaluator.evaluate(features(valid = false), null))

        assertTrue(
            "Peringatan bidikan tak terbaca harus dibacakan: $hasil",
            hasil.any { it == "Postur tidak terlihat jelas. Arahkan HP ke samping pemanah." }
        )
        assertTrue(
            "Posisi tidak boleh dipuji saat bidikan tak terbaca: $hasil",
            hasil.none { it == "Posisi sudah ideal." }
        )
    }

    @Test
    fun `angin kencang dibacakan tanpa memblokir pujian posisi ideal`() {
        val planner = VoiceCuePlanner()
        val hasil = spoken(planner, evaluator.evaluate(features(), WindSnapshot(8f, 0f, 0L, true)))

        assertTrue(
            "Angin kencang harus dibacakan: $hasil",
            hasil.any { it == "Angin kencang. Sesuaikan arah bidikan." }
        )
        assertTrue(
            "Pujian posisi ideal tetap keluar walau angin kencang: $hasil",
            hasil.any { it == "Posisi sudah ideal." }
        )
    }
}
