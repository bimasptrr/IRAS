package com.example.iras.ai

/**
 * Memutuskan KAPAN dan APA yang diucapkan berdasarkan hasil TechniqueEvaluation.
 * Kelas ini murni Kotlin (tanpa Android) sehingga mudah diuji.
 *
 * Aturan:
 *  - Sebuah koreksi baru diucapkan setelah bertahan [debounceMs] (pose MediaPipe sering bergetar).
 *  - Koreksi yang sama diulang paling cepat setiap [repeatMs] selama masih salah.
 *  - Jarak antar-ucapan minimal [minGapMs] supaya suara tidak bertumpuk.
 *  - Bila beberapa bagian salah sekaligus, bagian berprioritas tertinggi dibicarakan lebih dulu.
 *  - Setelah semua koreksi beres dan stabil, diucapkan "Posisi sudah ideal" satu kali.
 */
class VoiceCuePlanner(
    private val debounceMs: Long = 1_000L,
    private val repeatMs: Long = 8_000L,
    private val minGapMs: Long = 2_500L
) {
    private class Cue(val text: String)

    // Urutan = prioritas (paling atas dibahas lebih dulu)
    private val cues: LinkedHashMap<String, Cue> = linkedMapOf(
        "INVALID" to Cue("Postur tidak terlihat jelas. Sesuaikan posisi kamera."),
        "DRAW_ARM_LOW" to Cue("Siku lengan penarik kurang naik."),
        "DRAW_ARM_HIGH" to Cue("Siku lengan penarik terlalu lurus."),
        "ALIGNMENT_BAD" to Cue("Sejajarkan bahu dan pinggul."),
        "WIND_COMPENSATION" to Cue("Angin kencang. Sesuaikan arah bidikan.")
    )

    private val firstSeen = HashMap<String, Long>()
    private val lastSpoken = HashMap<String, Long>()
    private var lastAnySpokenAt: Long = Long.MIN_VALUE / 2
    private var idealSince: Long? = null
    private var needIdealPraise = false

    /** Dipanggil setiap ada hasil evaluasi baru. Mengembalikan kalimat untuk diucapkan, atau null. */
    fun next(eval: TechniqueEvaluation?, nowMs: Long): String? {
        if (eval == null) {
            firstSeen.clear()
            idealSince = null
            return null
        }

        val active: Set<String> = if (!eval.isValid) {
            setOf("INVALID")
        } else {
            eval.issues.map { it.code }.toSet()
        }

        // Koreksi yang sudah hilang: hitung ulang debounce-nya bila muncul lagi
        firstSeen.keys.retainAll(active)
        for (code in active) firstSeen.putIfAbsent(code, nowMs)

        if (nowMs - lastAnySpokenAt < minGapMs) return null

        if (active.isEmpty()) {
            val since = idealSince ?: nowMs.also { idealSince = it }
            if (needIdealPraise && nowMs - since >= debounceMs) {
                needIdealPraise = false
                lastAnySpokenAt = nowMs
                return "Posisi sudah ideal."
            }
            return null
        }
        idealSince = null

        for ((code, cue) in cues) {
            if (code !in active) continue
            val seenFor = nowMs - (firstSeen[code] ?: nowMs)
            if (seenFor < debounceMs) continue
            val last = lastSpoken[code]
            if (last != null && nowMs - last < repeatMs) continue
            lastSpoken[code] = nowMs
            lastAnySpokenAt = nowMs
            if (code != "INVALID") needIdealPraise = true
            return cue.text
        }
        return null
    }

    fun reset() {
        firstSeen.clear()
        lastSpoken.clear()
        lastAnySpokenAt = Long.MIN_VALUE / 2
        idealSince = null
        needIdealPraise = false
    }
}