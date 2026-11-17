package com.example.iras.ai
class VoiceCuePlanner(
    private val debounceMs: Long = 1_000L,
    private val repeatMs: Long = 8_000L,
    private val minGapMs: Long = 2_500L
) {
    private class Cue(val text: String)

    // Urutan = prioritas (paling atas dibahas lebih dulu)
    private val cues: LinkedHashMap<String, Cue> = linkedMapOf(
        "INVALID" to Cue("Postur tidak terlihat jelas. Arahkan HP ke samping pemanah."),
        "DRAW_ELBOW_CLOSED" to Cue("Sudut siku lengan penarik terlalu tertutup."),
        "DRAW_ELBOW_OPEN" to Cue("Sudut siku lengan penarik terlalu terbuka."),
        "ALIGNMENT_BAD" to Cue("Sejajarkan bahu dan pinggul."),
        "STANCE_BAD" to Cue("Ratakan posisi kaki."),
        "WIND_COMPENSATION" to Cue("Angin kencang. Sesuaikan arah bidikan.")
    )

    // Isu lingkungan bukan postur, jadi tidak menghalangi pujian "Posisi sudah ideal."
    private val environmentCodes = setOf("WIND_COMPENSATION")

    private val firstSeen = HashMap<String, Long>()
    private val lastSpoken = HashMap<String, Long>()
    private var lastAnySpokenAt: Long = Long.MIN_VALUE / 2
    private var idealSince: Long? = null
    private var lastPraiseAt: Long = Long.MIN_VALUE / 2

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

        firstSeen.keys.retainAll(active)
        for (code in active) firstSeen.putIfAbsent(code, nowMs)

        val postureOk = active.none { it !in environmentCodes }

        if (!postureOk) {
            idealSince = null
            if (nowMs - lastAnySpokenAt < minGapMs) return null
            return speakActive(active, nowMs)
        }

        // Postur sudah benar.
        if (nowMs - lastAnySpokenAt < minGapMs) return null

        // Angin kencang tetap dibacakan walau postur sudah benar.
        val environment = speakActive(active.filter { it in environmentCodes }.toSet(), nowMs)
        if (environment != null) return environment

        // Pujian "Posisi sudah ideal." diberikan sebagai konfirmasi kondisi
        // realtime, bukan hadiah setelah ada koreksi.
        val since = idealSince ?: nowMs.also { idealSince = it }
        if (nowMs - since < debounceMs) return null
        if (nowMs - lastPraiseAt < repeatMs) return null

        lastPraiseAt = nowMs
        lastAnySpokenAt = nowMs
        idealSince = null
        return "Posisi sudah ideal."
    }

    /** Mengambil kode paling prioritas yang sudah layak dibacakan. */
    private fun speakActive(active: Set<String>, nowMs: Long): String? {
        for ((code, cue) in cues) {
            if (code !in active) continue
            val seenFor = nowMs - (firstSeen[code] ?: nowMs)
            if (seenFor < debounceMs) continue
            val last = lastSpoken[code]
            if (last != null && nowMs - last < repeatMs) continue
            lastSpoken[code] = nowMs
            lastAnySpokenAt = nowMs
            return cue.text
        }
        return null
    }

    fun reset() {
        firstSeen.clear()
        lastSpoken.clear()
        lastAnySpokenAt = Long.MIN_VALUE / 2
        idealSince = null
        lastPraiseAt = Long.MIN_VALUE / 2
    }
}
