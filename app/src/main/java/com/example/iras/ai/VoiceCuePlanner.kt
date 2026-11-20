package com.example.iras.ai

/**
 * Menentukan instruksi suara berikutnya.
 *
 * Dua aturan utama supaya instruksi tidak bertumpuk:
 * 1. Instruksi baru hanya boleh keluar setelah suara sebelumnya selesai,
 *    dihitung dari estimasi durasi bunyi teks, bukan dari jeda tetap.
 * 2. Peringatan angin level 4 boleh memotong suara lain, tetapi paling
 *    sekali dalam [criticalWindRepeatMs].
 */
class VoiceCuePlanner(
    private val debounceMs: Long = 1_000L,
    private val repeatMs: Long = 8_000L,
    private val minGapMs: Long = 500L,
    private val windRepeatMs: Long = 11_000L,
    private val criticalWindRepeatMs: Long = 8_000L,
    private val windChangeGapMs: Long = 2_500L,
    private val msPerChar: Long = 60L
) {
    private class Cue(val defaultText: String)

    // Urutan = prioritas (Angin dibahas lebih dulu daripada koreksi postur)
    private val cues: LinkedHashMap<String, Cue> = linkedMapOf(
        WindEvaluator.CODE_WIND_CRITICAL to Cue("Batalkan tembakan! Angin sangat kencang."),
        WindEvaluator.CODE_WIND_STRONG to Cue("Angin kencang. Sesuaikan arah bidikan."),
        WindEvaluator.CODE_WIND_MODERATE to Cue("Angin sedang. Sesuaikan arah bidikan."),
        "WIND_COMPENSATION" to Cue("Angin kencang. Sesuaikan arah bidikan."),
        "INVALID" to Cue("Bidikan tak terbaca. Geser HP ke samping."),
        "DRAW_ELBOW_CLOSED" to Cue("Siku penarik terlalu tertutup."),
        "DRAW_ELBOW_OPEN" to Cue("Siku penarik terlalu terbuka."),
        "ALIGNMENT_BAD" to Cue("Sejajarkan bahu dan pinggul."),
        "STANCE_BAD" to Cue("Ratakan posisi kaki.")
    )

    // Isu lingkungan bukan postur, jadi tidak menghalangi pujian "Posisi sudah ideal."
    private val environmentCodes = setOf(
        WindEvaluator.CODE_WIND_CRITICAL,
        WindEvaluator.CODE_WIND_STRONG,
        WindEvaluator.CODE_WIND_MODERATE,
        "WIND_COMPENSATION"
    )

    private val firstSeen = HashMap<String, Long>()
    private val lastSpoken = HashMap<String, Long>()
    private var nextAllowedAt: Long = Long.MIN_VALUE / 2
    private var idealSince: Long? = null
    private var lastPraiseAt: Long = Long.MIN_VALUE / 2

    // Signature angin dipakai untuk membedakan "kondisi sama" dan "kondisi berubah".
    // Nilainya berasal dari level/sektor yang sudah distabilkan, jadi noise
    // sensor tidak dianggap sebagai perubahan.
    private var lastSpokenWindSignature: String? = null
    private var lastWindSpokenAt: Long = Long.MIN_VALUE / 2
    private var lastCriticalSpokenAt: Long = Long.MIN_VALUE / 2

    fun next(eval: TechniqueEvaluation?, nowMs: Long): VoiceCue? {
        if (eval == null) {
            firstSeen.clear()
            idealSince = null
            return null
        }

        val dynamicMessages: Map<String, String> = eval.issues.associate { it.code to it.message }

        // 1. PRIORITAS TERTINGGI: Angin Level 4 (Sangat Kencang -> Batalkan Tembakan).
        // Boleh menginterupsi suara lain, tetapi tidak lebih dari sekali per
        // criticalWindRepeatMs supaya peringatan tidak diulang-ulang.
        val criticalWindMsg = dynamicMessages[WindEvaluator.CODE_WIND_CRITICAL]
        if (criticalWindMsg != null) {
            val cue = evaluateWindMessage(
                code = WindEvaluator.CODE_WIND_CRITICAL,
                message = criticalWindMsg,
                isCritical = true,
                nowMs = nowMs
            )
            if (cue != null) return cue
        }

        val active: Set<String> = if (!eval.isValid) {
            setOf("INVALID")
        } else {
            eval.issues.map { it.code }.toSet()
        }

        firstSeen.keys.retainAll(active)
        for (code in active) firstSeen.putIfAbsent(code, nowMs)

        // 2. PRIORITAS KEDUA: Evaluasi angin (Level 2 & 3) lebih dulu sebelum koreksi postur
        val envActive = active.filter { it in environmentCodes }.toSet()
        if (envActive.isNotEmpty()) {
            val windCue = speakActive(envActive, dynamicMessages, nowMs)
            if (windCue != null) return windCue
        }

        // 3. PRIORITAS KETIGA: Koreksi postur tubuh
        val postureActive = active.filter { it !in environmentCodes }.toSet()
        if (postureActive.isNotEmpty()) {
            idealSince = null
            val postureCue = speakActive(postureActive, dynamicMessages, nowMs)
            if (postureCue != null) return postureCue
        }

        // 4. Postur ideal & tidak ada instruksi angin baru -> pujian berkala
        if (nowMs < nextAllowedAt) return null

        val since = idealSince ?: nowMs.also { idealSince = it }
        if (nowMs - since < debounceMs) return null
        if (nowMs - lastPraiseAt < repeatMs) return null

        lastPraiseAt = nowMs
        markSpoken(PRAISE_TEXT, nowMs)
        return VoiceCue(PRAISE_TEXT)
    }

    /**
     * Evaluasi suara khusus aliran data angin BLE (1 Hz) ketika kamera/pose tidak aktif
     * atau untuk pemrosesan langsung paket BLE angin.
     */
    fun nextWindOnly(windEval: WindEvaluation?, nowMs: Long): VoiceCue? {
        if (windEval == null || !windEval.shouldSpeak || windEval.cueCode == null || windEval.ttsMessage == null) {
            if (windEval?.level == WindLevel.LEVEL_1_CALM) {
                lastSpokenWindSignature = null
            }
            return null
        }

        return evaluateWindMessage(
            code = windEval.cueCode,
            message = windEval.ttsMessage,
            isCritical = windEval.isCritical,
            nowMs = nowMs
        )
    }

    private fun evaluateWindMessage(
        code: String,
        message: String,
        isCritical: Boolean,
        nowMs: Long
    ): VoiceCue? {
        val signature = "$code|$message"
        val isChanged = signature != lastSpokenWindSignature

        if (isCritical) {
            if (lastCriticalSpokenAt != Long.MIN_VALUE / 2 &&
                nowMs - lastCriticalSpokenAt < criticalWindRepeatMs
            ) {
                return null
            }
            lastSpokenWindSignature = signature
            lastWindSpokenAt = nowMs
            lastCriticalSpokenAt = nowMs
            lastSpoken[code] = nowMs
            markSpoken(message, nowMs)
            return VoiceCue(message, interrupt = true)
        }

        // Level 2 & 3: hormati windRepeatMs (11 detik) kecuali level/sektor berubah
        if (!isChanged) {
            if (nowMs - lastWindSpokenAt < windRepeatMs) return null
            if (nowMs < nextAllowedAt) return null
        } else {
            // Kondisi berubah: boleh diumumkan, tapi tetap tunggu suara
            // sebelumnya selesai dan jangan terlalu cepat mengulang.
            if (nowMs < nextAllowedAt) return null
            if (lastWindSpokenAt != Long.MIN_VALUE / 2 && nowMs - lastWindSpokenAt < windChangeGapMs) {
                return null
            }
        }

        lastSpokenWindSignature = signature
        lastWindSpokenAt = nowMs
        lastSpoken[code] = nowMs
        markSpoken(message, nowMs)
        return VoiceCue(message)
    }

    /** Mengambil kode paling prioritas yang sudah layak dibacakan. */
    private fun speakActive(
        active: Set<String>,
        dynamicMessages: Map<String, String>,
        nowMs: Long
    ): VoiceCue? {
        for ((code, cue) in cues) {
            if (code !in active) continue

            if (code in environmentCodes) {
                val msg = dynamicMessages[code] ?: cue.defaultText
                return evaluateWindMessage(
                    code = code,
                    message = msg,
                    isCritical = code == WindEvaluator.CODE_WIND_CRITICAL,
                    nowMs = nowMs
                )
            }

            val seenFor = nowMs - (firstSeen[code] ?: nowMs)
            if (seenFor < debounceMs) continue
            val last = lastSpoken[code]
            if (last != null && nowMs - last < repeatMs) continue
            if (nowMs < nextAllowedAt) return null

            val text = dynamicMessages[code] ?: cue.defaultText
            lastSpoken[code] = nowMs
            markSpoken(text, nowMs)
            return VoiceCue(text)
        }
        return null
    }

    /**
     * Menandai bahwa sebuah instruksi baru saja diucapkan, sehingga instruksi
     * berikutnya harus menunggu sampai suara ini selesai berbunyi.
     */
    private fun markSpoken(text: String, nowMs: Long) {
        nextAllowedAt = nowMs + estimateDurationMs(text) + minGapMs
    }

    private fun estimateDurationMs(text: String): Long = text.length * msPerChar

    fun reset() {
        firstSeen.clear()
        lastSpoken.clear()
        nextAllowedAt = Long.MIN_VALUE / 2
        idealSince = null
        lastPraiseAt = Long.MIN_VALUE / 2
        lastSpokenWindSignature = null
        lastWindSpokenAt = Long.MIN_VALUE / 2
        lastCriticalSpokenAt = Long.MIN_VALUE / 2
    }

    private companion object {
        const val PRAISE_TEXT = "Posisi sudah ideal."
    }
}
