package com.example.iras.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Satu kejadian gerakan yang dikirim wearable (mis. "Diam", atau teks gerakan lain). */
data class MovementEvent(val text: String, val timestamp: Long) {
    /** true bila teks berarti sensor sedang diam. */
    val isStill: Boolean get() = text.trim().equals("diam", ignoreCase = true)
}

/** Satu bacaan dari node sensor angin: "kecepatan,kategori,arah_teks,derajat". */
data class WindData(
    val speed: Float,        // m/s
    val category: String,    // Tenang, Sepoi-sepoi, Agak Kencang, Kencang, Sangat Kencang
    val direction: String,   // 16 arah mata angin, atau "Error"
    val degrees: Float,      // 0..360, atau -1 bila sensor arah error
    val timestamp: Long
) {
    val directionOk: Boolean get() = degrees >= 0f && !direction.equals("Error", ignoreCase = true)
}

/** Perubahan kondisi angin (kategori atau arah berubah) untuk riwayat. */
data class WindEvent(
    val category: String,
    val direction: String,
    val speed: Float,
    val timestamp: Long
)

object WindPacketParser {
    /** Mengurai satu baris teks; null bila bukan format angin. */
    fun parse(bytes: ByteArray, now: Long): WindData? {
        if (!ImuPacketParser.isPrintable(bytes)) return null
        val parts = String(bytes, Charsets.UTF_8).trim().split(",").map { it.trim() }
        if (parts.size != 4) return null
        val speed = parts[0].toFloatOrNull() ?: return null
        val degrees = parts[3].toFloatOrNull() ?: return null
        if (!speed.isFinite() || !degrees.isFinite() || speed < 0f || speed > 200f) return null
        if (degrees != -1f && (degrees < 0f || degrees > 360f)) return null
        // Kategori dan arah harus teks (tanpa angka); ini membedakan dari paket IMU
        if (parts[1].isEmpty() || parts[2].isEmpty()) return null
        if (parts[1].any { it.isDigit() } || parts[2].any { it.isDigit() }) return null
        return WindData(speed, parts[1], parts[2], degrees, now)
    }
}

/** Status koneksi Bluetooth (BLE). */
sealed interface BleState {
    data object Idle : BleState
    data object Scanning : BleState
    data class Connecting(val name: String) : BleState

    /**
     * Terhubung ke sebuah perangkat.
     * isIras      = true jika perangkat memakai UUID layanan IRAS (sketch bawaan proyek ini).
     * services    = UUID layanan GATT milik perangkat.
     * subscribed  = UUID characteristic yang notifikasinya sudah diaktifkan (sumber data).
     */
    data class Connected(
        val name: String,
        val address: String,
        val isIras: Boolean,
        val services: List<String>,
        val subscribed: List<String> = emptyList()
    ) : BleState

    data class Error(val message: String) : BleState
}

/** Perangkat BLE yang ditemukan saat memindai. */
data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val isIras: Boolean
)

/**
 * Mengelola Bluetooth Low Energy untuk ESP32 APA SAJA:
 *  1. memindai semua perangkat BLE (tanpa filter),
 *  2. menyambung ke perangkat yang dipilih,
 *  3. mengaktifkan notifikasi pada SEMUA characteristic yang bisa notify/indicate
 *     (UUID tidak harus sama dengan sketch IRAS),
 *  4. membaca setiap paket: jika berisi angka x,y,z (teks atau float32) -> [imu].
 * Paket mentah selalu disimpan di [raw] agar bisa diperiksa bila data tidak terbaca.
 *
 * Izin Bluetooth harus sudah diberikan sebelum startScan() dipanggil (lihat IoTScreen).
 */
@SuppressLint("MissingPermission")
class BleManager(context: Context) {

    companion object {
        private const val MAX_HISTORY = 50
        // UUID sketch IRAS lama (opsional; hanya dipakai untuk memberi label "IRAS")
        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")

        // Nordic UART Service (dipakai firmware ESP32 teman): TX = data ke HP, RX = perintah dari HP
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Layanan standar yang bukan sumber data sensor: GAP 0x1800 dan GATT 0x1801
        private val SKIPPED_SERVICES = setOf(
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
        )

        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val REQUESTED_MTU = 185
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var userDisconnect = false
    private var deviceName = ""
    private var deviceAddress = ""
    private var serviceList: List<String> = emptyList()

    // Antrean characteristic yang notifikasinya akan diaktifkan (satu per satu, GATT tidak boleh paralel)
    private val subscribeQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val subscribedUuids = mutableListOf<String>()

    // Penyusun paket per characteristic (menyambung teks yang terpotong per newline)
    private val assemblers = HashMap<UUID, PacketAssembler>()

    private val found = LinkedHashMap<String, ScanResult>()

    private val _state = MutableStateFlow<BleState>(BleState.Idle)
    val state: StateFlow<BleState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()

    /** Sampel IMU terbaru (null = belum ada paket yang bisa dibaca). */
    private val _imu = MutableStateFlow<ImuData?>(null)
    val imu: StateFlow<ImuData?> = _imu.asStateFlow()

    /** Paket mentah terbaru dari ESP32, apa pun isinya. */
    private val _raw = MutableStateFlow<RawPacket?>(null)
    val raw: StateFlow<RawPacket?> = _raw.asStateFlow()

    /** Level baterai terbaru dari perangkat (null = belum ada). Format baris: "BAT,87,4.05". */
    private val _battery = MutableStateFlow<BatteryData?>(null)
    val battery: StateFlow<BatteryData?> = _battery.asStateFlow()

    /** Detak jantung terbaru (null = belum ada). Format baris: "BPM,76"; "BPM,0" = tidak terdeteksi. */
    private val _heartRate = MutableStateFlow<HeartRateData?>(null)
    val heartRate: StateFlow<HeartRateData?> = _heartRate.asStateFlow()

    /** Jumlah paket yang diterima sejak tersambung. */
    private val _packetCount = MutableStateFlow(0)
    val packetCount: StateFlow<Int> = _packetCount.asStateFlow()

    /** Teks gerakan terakhir dari wearable (null = belum ada). */
    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    /** Riwayat perubahan status gerakan, terbaru di depan (maks. 50). Hanya perubahan yang dicatat. */
    private val _movementHistory = MutableStateFlow<List<MovementEvent>>(emptyList())
    val movementHistory: StateFlow<List<MovementEvent>> = _movementHistory.asStateFlow()

    /** Jumlah kejadian gerakan (status selain "Diam") sejak tersambung. */
    private val _movementCount = MutableStateFlow(0)
    val movementCount: StateFlow<Int> = _movementCount.asStateFlow()

    /** Bacaan angin terbaru (null = belum ada). Hanya terisi bila perangkat mengirim format angin. */
    private val _wind = MutableStateFlow<WindData?>(null)
    val wind: StateFlow<WindData?> = _wind.asStateFlow()

    private val _windHistory = MutableStateFlow<List<WindEvent>>(emptyList())
    val windHistory: StateFlow<List<WindEvent>> = _windHistory.asStateFlow()

    /** Kecepatan angin tertinggi sejak tersambung (m/s). */
    private val _windMax = MutableStateFlow(0f)
    val windMax: StateFlow<Float> = _windMax.asStateFlow()

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    // ---------- Pemindaian (semua perangkat BLE) ----------

    fun startScan() {
        val bt = adapter
        if (bt == null) {
            _state.value = BleState.Error("HP ini tidak mendukung Bluetooth")
            return
        }
        if (!bt.isEnabled) {
            _state.value = BleState.Error("Bluetooth belum menyala")
            return
        }
        if (_state.value is BleState.Connecting || _state.value is BleState.Connected) return

        val scanner = bt.bluetoothLeScanner
        if (scanner == null) {
            _state.value = BleState.Error("Pemindai Bluetooth tidak tersedia")
            return
        }

        stopScanInternal()
        found.clear()
        _devices.value = emptyList()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        _state.value = BleState.Scanning
        scanner.startScan(null, settings, scanCallback) // tanpa filter: semua perangkat
        handler.removeCallbacks(scanTimeout)
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        stopScanInternal()
        if (_state.value is BleState.Scanning) _state.value = BleState.Idle
    }

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScanInternal()
            if (_state.value is BleState.Scanning) _state.value = BleState.Idle
        }
    }

    private fun stopScanInternal() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            found[result.device.address] = result
            _devices.value = found.values
                .map { r ->
                    BleDevice(
                        address = r.device.address,
                        name = r.scanRecord?.deviceName ?: r.device.name,
                        rssi = r.rssi,
                        isIras = r.scanRecord?.serviceUuids?.let {
                            ParcelUuid(SERVICE_UUID) in it || ParcelUuid(NUS_SERVICE_UUID) in it
                        } == true
                    )
                }
                .sortedWith(compareByDescending<BleDevice> { it.isIras }.thenByDescending { it.rssi })
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            handler.removeCallbacks(scanTimeout)
            _state.value = BleState.Error("Pemindaian gagal (kode $errorCode)")
        }
    }

    // ---------- Koneksi ----------

    /** Menyambung ke perangkat hasil pemindaian berdasarkan alamatnya. */
    fun connect(address: String) {
        val bt = adapter ?: return
        if (_state.value is BleState.Connecting || _state.value is BleState.Connected) return

        handler.removeCallbacks(scanTimeout)
        stopScanInternal()

        val scanned = found[address]
        val device = scanned?.device
            ?: runCatching { bt.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            _state.value = BleState.Error("Perangkat tidak ditemukan")
            return
        }

        deviceAddress = address
        deviceName = scanned?.scanRecord?.deviceName ?: device.name ?: "Tanpa nama"
        serviceList = emptyList()
        synchronized(subscribeQueue) { subscribeQueue.clear() }
        subscribedUuids.clear()
        assemblers.clear()
        _imu.value = null
        _battery.value = null
        _heartRate.value = null
        _raw.value = null
        _packetCount.value = 0
        _statusText.value = null
        _movementHistory.value = emptyList()
        _movementCount.value = 0
        _wind.value = null
        _windHistory.value = emptyList()
        _windMax.value = 0f
        userDisconnect = false
        _state.value = BleState.Connecting(deviceName)
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        // Batas waktu: bila perangkat tidak menjawab, jangan menggantung selamanya
        handler.removeCallbacks(connectTimeout)
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private val connectTimeout = Runnable {
        if (_state.value is BleState.Connecting) {
            gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
            gatt = null
            _state.value = BleState.Error("Perangkat tidak menjawab. Pastikan ESP32 menyala dan dekat.")
        }
    }

    fun disconnect() {
        userDisconnect = true
        handler.removeCallbacksAndMessages(null)
        stopScanInternal()
        val g = gatt
        if (g != null) {
            g.disconnect() // pembersihan dilakukan di onConnectionStateChange
        } else {
            _imu.value = null
            _state.value = BleState.Idle
        }
    }

    // ---------- GATT ----------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Naikkan MTU dulu supaya paket teks tidak terpotong
                    if (!g.requestMtu(REQUESTED_MTU)) g.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    handler.removeCallbacks(connectTimeout)
                    g.close()
                    if (gatt === g) gatt = null
                    synchronized(subscribeQueue) { subscribeQueue.clear() }
                    _imu.value = null
                    _battery.value = null
                    _heartRate.value = null
                    _statusText.value = null
                    _wind.value = null
                    _state.value = if (userDisconnect || status == BluetoothGatt.GATT_SUCCESS) {
                        BleState.Idle
                    } else {
                        BleState.Error("Koneksi gagal atau terputus (kode $status)")
                    }
                    userDisconnect = false
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.removeCallbacks(connectTimeout)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = BleState.Error("Gagal membaca layanan perangkat")
                g.disconnect()
                return
            }
            serviceList = g.services.map { it.uuid.toString() }

            // Semua characteristic yang bisa memberi notifikasi, kecuali layanan standar GAP/GATT
            val targets = g.services
                .filter { it.uuid !in SKIPPED_SERVICES }
                .flatMap { it.characteristics }
                .filter {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                }

            synchronized(subscribeQueue) {
                subscribeQueue.clear()
                subscribeQueue.addAll(targets)
            }
            publishConnected(g)
            subscribeNext(g)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                subscribedUuids += descriptor.characteristic.uuid.toString()
                publishConnected(g)
            }
            // Gagal pada satu characteristic tidak menghentikan yang lain
            subscribeNext(g)
        }

        // Android 13 ke atas
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handlePacket(characteristic.uuid, value)
        }

        // Android 12 ke bawah
        @Deprecated("Dipakai untuk Android 12 ke bawah")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handlePacket(characteristic.uuid, characteristic.value)
        }
    }

    private fun publishConnected(g: BluetoothGatt) {
        if (gatt !== g) return
        _state.value = BleState.Connected(
            name = deviceName,
            address = deviceAddress,
            isIras = SERVICE_UUID.toString() in serviceList ||
                    NUS_SERVICE_UUID.toString().lowercase() in serviceList,
            services = serviceList,
            subscribed = subscribedUuids.toList()
        )
    }

    /** Aktifkan notifikasi characteristic berikutnya di antrean; berhenti bila antrean kosong. */
    private fun subscribeNext(g: BluetoothGatt) {
        while (true) {
            val next = synchronized(subscribeQueue) { subscribeQueue.removeFirstOrNull() } ?: return
            if (writeCccd(g, next)) return // tunggu onDescriptorWrite untuk lanjut
            // gagal memulai tulis (tanpa CCCD, dll): coba characteristic berikutnya
        }
    }

    /** Mengembalikan true jika penulisan CCCD berhasil dimulai (callback akan menyusul). */
    private fun writeCccd(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        val descriptor = c.getDescriptor(CCCD_UUID) ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(descriptor, value) == 0 // BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
    }

    // ---------- Paket data ----------

    private fun handlePacket(uuid: UUID, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        val assembler = assemblers.getOrPut(uuid) { PacketAssembler() }
        for (payload in assembler.feed(bytes)) {
            val now = System.currentTimeMillis()

            // Baris baterai ("BAT,87,4.05") dicek paling awal: angkanya mirip paket sudut IMU 2 nilai
            val batteryData = BatteryPacketParser.parse(payload, now)
            if (batteryData != null) {
                _battery.value = batteryData
                _raw.value = RawPacket(
                    characteristic = uuid.toString(),
                    text = ImuPacketParser.describe(payload),
                    size = payload.size,
                    parsed = true,
                    timestamp = now
                )
                _packetCount.value = _packetCount.value + 1
                continue
            }

            // Baris detak jantung ("BPM,76"), sama alasannya dengan baris baterai
            val heartData = HeartRatePacketParser.parse(payload, now)
            if (heartData != null) {
                _heartRate.value = heartData
                _raw.value = RawPacket(
                    characteristic = uuid.toString(),
                    text = ImuPacketParser.describe(payload),
                    size = payload.size,
                    parsed = true,
                    timestamp = now
                )
                _packetCount.value = _packetCount.value + 1
                continue
            }

            // Paket angin dicek lebih dulu: angkanya mirip paket IMU 2 nilai
            val windData = WindPacketParser.parse(payload, now)
            if (windData != null) {
                recordWind(windData)
                _raw.value = RawPacket(
                    characteristic = uuid.toString(),
                    text = ImuPacketParser.describe(payload),
                    size = payload.size,
                    parsed = true,
                    timestamp = now
                )
                _packetCount.value = _packetCount.value + 1
                continue
            }

            val data = ImuPacketParser.parse(payload, now)
            if (data != null) _imu.value = data

            // Teks tanpa angka (mis. "Diam") = status gerakan dari wearable, bukan error
            val text = ImuPacketParser.describe(payload)
            val isStatus = data == null && ImuPacketParser.isPrintable(payload) &&
                    text.isNotBlank() && text.none { it.isDigit() }
            if (isStatus) recordMovement(text, now)

            _raw.value = RawPacket(
                characteristic = uuid.toString(),
                text = text,
                size = payload.size,
                parsed = data != null || isStatus,
                timestamp = now
            )
            _packetCount.value = _packetCount.value + 1
        }
    }

    /** Simpan bacaan angin; riwayat hanya dicatat saat kategori atau arah berubah. */
    private fun recordWind(w: WindData) {
        _wind.value = w
        if (w.speed > _windMax.value) _windMax.value = w.speed
        val last = _windHistory.value.firstOrNull()
        if (last != null && last.category == w.category && last.direction == w.direction) return
        _windHistory.value = (listOf(WindEvent(w.category, w.direction, w.speed, w.timestamp)) +
                _windHistory.value).take(MAX_HISTORY)
    }

    /** Catat status gerakan; paket yang sama berulang-ulang hanya dicatat sekali. */
    private fun recordMovement(text: String, now: Long) {
        _statusText.value = text
        val last = _movementHistory.value.firstOrNull()
        if (last != null && last.text == text) return
        val event = MovementEvent(text, now)
        _movementHistory.value = (listOf(event) + _movementHistory.value).take(MAX_HISTORY)
        if (!event.isStill) _movementCount.value = _movementCount.value + 1
    }

    // ---------- Perintah ke ESP32 ----------

    /**
     * Mengirim perintah reset titik nol: karakter "r" ke characteristic RX (6E400002).
     * Jika perangkat tidak memakai UUID itu, dipakai characteristic lain yang bisa ditulis.
     * Mengembalikan false bila belum tersambung, characteristic tidak ada, atau penulisan ditolak.
     */
    fun sendResetCommand(): Boolean = sendCommand("r")

    fun sendCommand(text: String): Boolean {
        val g = gatt ?: return false
        if (_state.value !is BleState.Connected) return false

        val writable = BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        val target = g.getService(NUS_SERVICE_UUID)?.getCharacteristic(NUS_RX_UUID)
            ?: g.services
                .filter { it.uuid !in SKIPPED_SERVICES }
                .flatMap { it.characteristics }
                .firstOrNull { it.properties and writable != 0 }
            ?: return false

        val writeType = if (target.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val payload = text.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(target, payload, writeType) == 0 // BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            target.writeType = writeType
            @Suppress("DEPRECATION")
            target.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(target)
        }
    }
}