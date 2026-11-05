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

    /** Jumlah paket yang diterima sejak tersambung. */
    private val _packetCount = MutableStateFlow(0)
    val packetCount: StateFlow<Int> = _packetCount.asStateFlow()

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
        _raw.value = null
        _packetCount.value = 0
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
            val data = ImuPacketParser.parse(payload, now)
            if (data != null) _imu.value = data
            _raw.value = RawPacket(
                characteristic = uuid.toString(),
                text = ImuPacketParser.describe(payload),
                size = payload.size,
                parsed = data != null,
                timestamp = now
            )
            _packetCount.value = _packetCount.value + 1
        }
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