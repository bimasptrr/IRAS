package com.example.iras.ble

/** Level baterai perangkat: [percent] 0-100, [volt] dalam Volt (null bila tidak dikirim). */
data class BatteryData(val percent: Int, val volt: Float?, val timestamp: Long)

/**
 * Mengurai baris baterai dari ESP32, mis. "BAT,87,4.05" (persen, tegangan) atau "BAT,87".
 * Awalan "BAT" wajib, jadi tidak tertukar dengan paket IMU yang juga berisi angka.
 * Murni Kotlin tanpa Android agar mudah diuji.
 */
object BatteryPacketParser {
    fun parse(bytes: ByteArray, now: Long): BatteryData? {
        if (!ImuPacketParser.isPrintable(bytes)) return null
        val parts = String(bytes, Charsets.UTF_8).trim().split(",", ":", ";").map { it.trim() }
        if (parts.size !in 2..3) return null
        if (!parts[0].equals("BAT", ignoreCase = true)) return null
        val percent = parts[1].toFloatOrNull() ?: return null
        if (!percent.isFinite() || percent < 0f || percent > 100f) return null
        val volt = parts.getOrNull(2)?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..10f }
        return BatteryData(percent.toInt(), volt, now)
    }
}