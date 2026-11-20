package com.example.iras.ai

/**
 * Menstabilkan level dan sektor angin sebelum dipakai sebagai instruksi suara.
 *
 * Sensor angin mengirim sampel ~1 Hz dan arahnya berfluk di batas sektor,
 * sehingga tanpa stabilisasi setiap fluktuasi dianggap "perubahan kondisi"
 * dan memunculkan instruksi baru tiap detik. Nilai yang di sini harus
 * bertahan [holdMs] sebelum benar dianggap berubah.
 */
class WindStabilizer(
    private val evaluator: WindEvaluator = WindEvaluator(),
    private val levelHoldMs: Long = 2_000L,
    private val sectorHoldMs: Long = 1_500L
) {

    private class Axis<T>(private val holdMs: Long) {
        var value: T? = null
            private set

        private var candidate: T? = null
        private var candidateSince: Long = 0L

        fun update(raw: T, nowMs: Long): T? {
            val current = value
            if (current == null) {
                value = raw
                return value
            }
            if (raw == current) {
                candidate = null
                return current
            }
            if (candidate != raw) {
                candidate = raw
                candidateSince = nowMs
                return current
            }
            if (nowMs - candidateSince >= holdMs) {
                value = raw
                candidate = null
            }
            return value
        }

        fun reset() {
            value = null
            candidate = null
        }
    }

    private val levelAxis = Axis<WindLevel>(levelHoldMs)
    private val sectorAxis = Axis<WindSector>(sectorHoldMs)

    fun stabilize(wind: WindSnapshot, nowMs: Long): WindSnapshot {
        if (!wind.isValid || !wind.speedMps.isFinite() || !wind.directionDeg.isFinite() ||
            wind.speedMps < 0f || wind.directionDeg < 0f
        ) {
            reset()
            return wind
        }

        val level = levelAxis.update(evaluator.classifyLevel(wind.speedMps), nowMs)
        val sector = sectorAxis.update(evaluator.mapSector(wind.directionDeg), nowMs)

        return wind.copy(stableLevel = level, stableSector = sector)
    }

    fun reset() {
        levelAxis.reset()
        sectorAxis.reset()
    }
}
