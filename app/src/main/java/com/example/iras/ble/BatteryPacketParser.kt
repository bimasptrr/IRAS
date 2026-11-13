package com.example.iras.ble

data class BatteryData(val percent: Int, val volt: Float?, val timestamp: Long)

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