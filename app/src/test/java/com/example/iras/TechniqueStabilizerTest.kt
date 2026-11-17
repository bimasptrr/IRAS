package com.example.iras

import com.example.iras.ai.BiomechanicsFeatures
import com.example.iras.ai.FeedbackIssue
import com.example.iras.ai.TechniqueEvaluation
import com.example.iras.ai.TechniqueEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji penstabilan vonis:_landmark yang bergerakan harus tidak langsung
 * dianggap salah, dan postur anchor hanya dinilai saat pemanah sedang
 * menarik busur.
 */
class TechniqueStabilizerTest {

    private val evaluator = TechniqueEvaluator()

    private fun features(
        angle: Float? = 30f,
        elbowRatio: Float? = 0.3f,
        handRatio: Float? = 0.5f,
        bow: Float? = 170f,
        ts: Long = 0L
    ) = BiomechanicsFeatures(
        drawArmElbowAngleDeg = angle,
        drawElbowFaceRatio = elbowRatio,
        drawHandFaceRatio = handRatio,
        bowArmElbowAngleDeg = bow,
        bodyAlignmentDeviationDeg = null,
        stanceDeviationDeg = null,
        featureTimestampMs = ts,
        isValid = true
    )

    /** Bangun fase anchor yang sudah terkonfirmasi. */
    private fun warmedUp(stabilizer: TechniqueStabilizer, startTs: Long = 0L) {
        for (ts in startTs until startTs + 600L step 100L) {
            stabilizer.process(evaluator.evaluate(features(ts = ts), null))
        }
    }

    @Test
    fun `fase anchor terkonfirmasi setelah bertahan beberapa frame`() {
        val stabilizer = TechniqueStabilizer()
        var out = stabilizer.process(evaluator.evaluate(features(ts = 0L), null))
        assertFalse("Fase anchor belum boleh aktif di frame pertama", out.isAnchorPhase)

        for (ts in 100L..600L step 100L) {
            out = stabilizer.process(evaluator.evaluate(features(ts = ts), null))
        }
        assertTrue("Fase anchor harus aktif setelah drewat", out.isAnchorPhase)
        assertTrue("Postur benar tidak boleh memicu isu: ${out.issues}", out.issues.isEmpty())
    }

    @Test
    fun `koreksi sesaat tidak langsung dilaporkan`() {
        val stabilizer = TechniqueStabilizer()
        warmedUp(stabilizer)

        val spike = evaluator.evaluate(features(elbowRatio = 1.5f, ts = 700L), null)
        assertTrue(
            "Evaluator mentah memang menandai posisi siku",
            spike.issues.any { it.code == "DRAW_ELBOW_TOO_LOW" }
        )

        val after = stabilizer.process(spike)
        assertTrue(
            "Gangguan satu frame tidak boleh dilaporkan: ${after.issues}",
            after.issues.none { it.code == "DRAW_ELBOW_TOO_LOW" }
        )
        assertEquals(
            "Metrik harus tetap hijau saat gangguan sesaat",
            true,
            after.metricStatus[TechniqueEvaluator.METRIC_DRAW_ELBOW_FACE]
        )
    }

    @Test
    fun `pelanggaran yang bertahan dilaporkan setelah jeda konfirmasi`() {
        val stabilizer = TechniqueStabilizer()
        warmedUp(stabilizer)

        var out = stabilizer.process(evaluator.evaluate(features(elbowRatio = 1.5f, ts = 700L), null))
        for (ts in 800L..1400L step 100L) {
            out = stabilizer.process(evaluator.evaluate(features(elbowRatio = 1.5f, ts = ts), null))
        }

        assertTrue(
            "Pelanggaran yang bertahan harus dilaporkan: ${out.issues}",
            out.issues.any { it.code == "DRAW_ELBOW_TOO_LOW" }
        )
        assertEquals(
            "Metrik harus merah setelah pelanggaran terkonfirmasi",
            false,
            out.metricStatus[TechniqueEvaluator.METRIC_DRAW_ELBOW_FACE]
        )
    }

    @Test
    fun `koreksi hilang setelah jeda hapus`() {
        val stabilizer = TechniqueStabilizer()
        warmedUp(stabilizer)

        var out = evaluator.evaluate(features(elbowRatio = 1.5f, ts = 700L), null)
        for (ts in 800L..1400L step 100L) {
            out = stabilizer.process(evaluator.evaluate(features(elbowRatio = 1.5f, ts = ts), null))
        }
        assertTrue("Pelanggaran harus aktif", out.issues.any { it.code == "DRAW_ELBOW_TOO_LOW" })

        for (ts in 1500L..2400L step 100L) {
            out = stabilizer.process(evaluator.evaluate(features(ts = ts), null))
        }
        assertTrue(
            "Koreksi harus hilang setelah postur benar: ${out.issues}",
            out.issues.none { it.code == "DRAW_ELBOW_TOO_LOW" }
        )
        assertEquals(
            "Metrik harus hijau lagi",
            true,
            out.metricStatus[TechniqueEvaluator.METRIC_DRAW_ELBOW_FACE]
        )
    }

    @Test
    fun `postur anchor tidak dinilai di luar fase menarik busur`() {
        // Siku menggantung saat prepares: sudut hampir lurus, bukan anchor.
        val raw = evaluator.evaluate(features(angle = 170f, elbowRatio = 1.5f, ts = 0L), null)
        assertTrue(
            "Evaluator mentah memang menandai postur",
            raw.issues.any { it.code == "DRAW_ELBOW_TOO_LOW" }
        )

        val out = TechniqueStabilizer().process(raw)
        assertFalse("Fase anchor belum aktif", out.isAnchorPhase)
        assertTrue(
            "Isu postur harus ditahan di luar fase anchor: ${out.issues}",
            out.issues.none { it.code == "DRAW_ELBOW_TOO_LOW" }
        )
        assertEquals(
            "Metrik anchor harus netral di luar fase",
            null,
            out.metricStatus[TechniqueEvaluator.METRIC_DRAW_ELBOW_FACE]
        )
    }

    @Test
    fun `postur anchor belum dinilai saat tangan masih di bawah wajah`() {
        // Siku sudah ditekuk, tapi tangan penarik belum sampai ke wajah:
        // ini masih tengah menarik busur, belum anchor.
        val raw = evaluator.evaluate(features(angle = 45f, handRatio = 1.6f, ts = 0L), null)
        assertFalse("Fase anchor belum boleh aktif", raw.isAnchorPhase)

        val out = TechniqueStabilizer().process(raw)
        assertFalse("Fase anchor belum aktif", out.isAnchorPhase)
        assertEquals(
            "Metrik anchor harus netral",
            null,
            out.metricStatus[TechniqueEvaluator.METRIC_ANCHOR_FACE]
        )
    }

    @Test
    fun `lengan busur terkunci lurus bukan berarti salah`() {
        val eval = evaluator.evaluate(features(bow = 179.8f), null)

        assertTrue(
            "Isu terkunci boleh muncul sebagai informasi",
            eval.issues.any { it.code == "BOW_ELBOW_LOCKED" }
        )
        assertEquals("Status tetap IDEAL", "IDEAL", eval.techniqueStatus)
        assertEquals(
            "Metrik lengan busur tetap benar",
            true,
            eval.metricStatus[TechniqueEvaluator.METRIC_BOW_ELBOW_ANGLE]
        )
    }

    @Test
    fun `lengan busur lurus dalam batas toleransi tidak terkunci`() {
        val eval = evaluator.evaluate(features(bow = 179f), null)

        assertTrue(
            "Lengan busur lurus tidak boleh ditandai terkunci: ${eval.issues}",
            eval.issues.none { it.code == "BOW_ELBOW_LOCKED" }
        )
        assertEquals("Status tetap IDEAL", "IDEAL", eval.techniqueStatus)
    }

    @Test
    fun `bidangan hilang sesaat tidak menghapus vonis yang sudah jadi`() {
        val stabilizer = TechniqueStabilizer()
        warmedUp(stabilizer)

        var out = evaluator.evaluate(features(elbowRatio = 1.5f, ts = 700L), null)
        for (ts in 800L..1400L step 100L) {
            out = stabilizer.process(evaluator.evaluate(features(elbowRatio = 1.5f, ts = ts), null))
        }
        assertTrue("Pelanggaran harus aktif", out.issues.any { it.code == "DRAW_ELBOW_TOO_LOW" })

        val invalid = TechniqueEvaluation(
            isValid = false,
            techniqueStatus = "INVALID_DATA",
            metrics = features(ts = 1500L),
            issues = listOf(
                FeedbackIssue("INVALID", "CRITICAL", "Lengan penarik tidak terlihat jelas.")
            ),
            feedbackText = "Postur tidak terlihat jelas.",
            timestampMs = 1500L
        )
        val after = stabilizer.process(invalid)
        assertTrue(
            "Vonis yang sudah jadi tidak boleh hilang karena bidikan sesaat",
            after.issues.any { it.code == "DRAW_ELBOW_TOO_LOW" }
        )
    }
}
