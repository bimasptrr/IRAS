package com.example.iras.ble

/** Detak jantung: [bpm] > 0 = valid, 0 = sensor tidak menempel / belum ada detak. */
data class HeartRateData(val bpm: Int, val timestamp: Long) {
    val isValid: Boolean get() = bpm > 0
}

/**
 * Mengurai baris detak jantung dari ESP32, mis. "BPM,76". "BPM,0" = tidak terdeteksi.
 * Awalan "BPM" wajib supaya tidak tertukar dengan paket IMU yang juga berisi angka.
 * Murni Kotlin tanpa Android agar mudah diuji.
 */
object HeartRatePacketParser {
    private const val MIN_BPM = 30
    private const val MAX_BPM = 220

    fun parse(bytes: ByteArray, now: Long): HeartRateData? {
        if (!ImuPacketParser.isPrintable(bytes)) return null
        val parts = String(bytes, Charsets.UTF_8).trim().split(",", ":", ";").map { it.trim() }
        if (parts.size != 2) return null
        if (!parts[0].equals("BPM", ignoreCase = true)) return null
        val value = parts[1].toFloatOrNull() ?: return null
        if (!value.isFinite()) return null
        val bpm = value.toInt()
        // 0 = tidak valid (resmi); selain itu harus masuk akal untuk manusia
        if (bpm != 0 && bpm !in MIN_BPM..MAX_BPM) return null
        return HeartRateData(bpm, now)
    }
}