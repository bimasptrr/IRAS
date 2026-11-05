package com.example.iras.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Satu sampel data IMU.
 *
 * Aplikasi mengenali beberapa bentuk paket dari ESP32:
 *  - 2 angka : sudut X, Y dalam derajat (Euler; disimpan di roll, pitch)  -> hasAccel = false
 *  - 3 angka : ax, ay, az
 *  - 6 angka : ax, ay, az, gx, gy, gz        (hasGyro = true)
 *  - 8 angka : ... + roll, pitch             (hasOrientation = true)
 *  - 9 angka : ... + baterai (%)
 */
data class ImuData(
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float = 0f,
    val gy: Float = 0f,
    val gz: Float = 0f,
    val roll: Float = 0f,
    val pitch: Float = 0f,
    val battery: Int = -1,
    val timestamp: Long = 0L,
    val hasGyro: Boolean = false,
    val hasOrientation: Boolean = false,
    /** false = paket hanya berisi sudut (2 angka), ax..az tidak berarti. */
    val hasAccel: Boolean = true
) {
    /** Besar kecepatan sudut total (derajat/detik); hanya berarti jika hasGyro. */
    val gyroMagnitude: Float get() = sqrt(gx * gx + gy * gy + gz * gz)

    /** Besar percepatan dalam satuan g (membagi dengan 9,81). */
    val accelMagnitudeG: Float get() = sqrt(ax * ax + ay * ay + az * az) / 9.81f
}

/** Satu paket mentah yang diterima dari ESP32, untuk ditampilkan saat diagnosis. */
data class RawPacket(
    val characteristic: String,
    val text: String,
    val size: Int,
    val parsed: Boolean,
    val timestamp: Long
)

/**
 * Mengubah paket byte dari characteristic BLE menjadi [ImuData].
 * Bentuk yang dikenali:
 *  1. Teks: "x,y,z" (atau dipisah spasi / titik koma / ada label, mis. "x:1.2 y:3.4 z:5.6")
 *  2. Biner: 3 atau 6 float32 little-endian (12 atau 24 byte), format asli ESP32 (Arduino float).
 * Murni Kotlin tanpa Android agar mudah diuji.
 */
object ImuPacketParser {

    private val NUMBER = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
    private const val MAX_ABS = 1.0e6f
    private val NOT_A_NUMBER = Regex("(?i)\\b(nan|inf|infinity)\\b")

    fun parse(bytes: ByteArray?, now: Long): ImuData? {
        if (bytes == null || bytes.isEmpty()) return null
        return if (isPrintable(bytes)) parseText(String(bytes, Charsets.UTF_8), now)
        else parseBinary(bytes, now)
    }

    fun parseText(text: String, now: Long): ImuData? {
        // "nan"/"inf" dari sensor rusak tidak boleh dilewati begitu saja (angka sisanya bisa salah dibaca)
        if (NOT_A_NUMBER.containsMatchIn(text)) return null
        val values = NUMBER.findAll(text).mapNotNull { it.value.toFloatOrNull() }.toList()
        if (values.any { !it.isFinite() || abs(it) > MAX_ABS }) return null
        return build(values, now)
    }

    fun parseBinary(bytes: ByteArray, now: Long): ImuData? {
        if (bytes.size != 12 && bytes.size != 24) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val values = List(bytes.size / 4) { buf.getFloat() }
        if (values.any { !it.isFinite() || abs(it) > MAX_ABS }) return null
        return build(values, now)
    }

    private fun build(v: List<Float>, now: Long): ImuData? = when (v.size) {
        2 -> ImuData(
            ax = 0f, ay = 0f, az = 0f, roll = v[0], pitch = v[1],
            timestamp = now, hasOrientation = true, hasAccel = false
        )
        3 -> ImuData(v[0], v[1], v[2], timestamp = now)
        6 -> ImuData(v[0], v[1], v[2], v[3], v[4], v[5], timestamp = now, hasGyro = true)
        8 -> ImuData(
            v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7],
            timestamp = now, hasGyro = true, hasOrientation = true
        )
        9 -> ImuData(
            v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8].toInt(),
            timestamp = now, hasGyro = true, hasOrientation = true
        )
        else -> null
    }

    /** Teks yang bisa dibaca untuk ditampilkan: isi teks apa adanya, atau heksadesimal bila biner. */
    fun describe(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return "(kosong)"
        if (isPrintable(bytes)) return String(bytes, Charsets.UTF_8).trim().take(80)
        return bytes.take(24).joinToString(" ") { "%02X".format(it) } + if (bytes.size > 24) " …" else ""
    }

    fun isPrintable(bytes: ByteArray): Boolean = bytes.all {
        val c = it.toInt() and 0xFF
        c in 0x20..0x7E || c == 0x0A || c == 0x0D || c == 0x09
    }
}

/**
 * Menyusun potongan notifikasi BLE menjadi paket utuh.
 * Satu characteristic = satu instance.
 *  - Jika firmware mengakhiri tiap baris dengan newline (seperti "12.3,-4.5\n"), potongan
 *    ditampung dan baru diproses per baris, sehingga teks yang terpotong tidak salah baca.
 *  - Jika firmware tidak pernah mengirim newline, tiap notifikasi dianggap satu paket utuh.
 *  - Paket biner (bukan teks) langsung diteruskan apa adanya.
 */
class PacketAssembler {
    private val buffer = StringBuilder()
    private var lineMode = false

    fun feed(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        if (!ImuPacketParser.isPrintable(bytes)) {
            buffer.clear()
            return listOf(bytes)
        }
        val text = String(bytes, Charsets.UTF_8)
        if (!lineMode && '\n' !in text) return listOf(bytes)
        lineMode = true

        buffer.append(text)
        val out = mutableListOf<ByteArray>()
        while (true) {
            val end = buffer.indexOf("\n")
            if (end < 0) break
            val line = buffer.substring(0, end).trim()
            buffer.delete(0, end + 1)
            if (line.isNotEmpty()) out += line.toByteArray(Charsets.UTF_8)
        }
        if (buffer.length > MAX_PENDING) buffer.clear() // sisa tak wajar: buang
        return out
    }

    private companion object { const val MAX_PENDING = 256 }
}