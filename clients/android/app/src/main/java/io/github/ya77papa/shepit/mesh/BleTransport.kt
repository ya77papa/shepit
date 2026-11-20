package io.github.ya77papa.shepit.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Етап 2: телефон-телефон без роутера. Пошук — Bluetooth LE (реклама зі службою Шепоту
 * і номером L2CAP-каналу), обмін — L2CAP-канал поверх BLE (Android 10+).
 * Канал не шифрується Bluetooth-ом: конверти вже зашифровані наскрізно.
 */
@SuppressLint("MissingPermission") // дозволи перевіряє [allowed]
class BleTransport(private val ctx: Context, private val runner: MeshRunner) {
    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var server: BluetoothServerSocket? = null
    private var advertising: AdvertiseCallback? = null
    private var scanning: ScanCallback? = null
    private var scanningAll: ScanCallback? = null
    private var scanningData: ScanCallback? = null
    /** Для журналу: скільки різних Bluetooth-пристроїв і скільки з Шепотом бачили за 30 с. */
    private val seenAny = ConcurrentHashMap.newKeySet<String>()
    private val seenOurs = ConcurrentHashMap.newKeySet<String>()
    /** Випадковий ідентифікатор цього запуску — щоб рахувати телефони поруч (адреси BLE змінюються). */
    private val nonce = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
    /** Коли востаннє обмінювались із телефоном (за nonce): не частіше ніж раз на 30 с. */
    private val lastTry = ConcurrentHashMap<String, Long>()
    /** Коли вперше побачили телефон (для запасного правила черговості). */
    private val firstSeen = ConcurrentHashMap<String, Long>()
    private var scope: CoroutineScope? = null

    fun allowed(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false // L2CAP-канали — з Android 10
        val perms = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } && adapter?.isEnabled == true
    }

    fun start(scope: CoroutineScope) {
        if (Build.VERSION.SDK_INT < 29) { runner.log("Bluetooth: потрібен Android 10+"); return }
        if (!allowed()) { runner.log("Bluetooth: немає дозволу або вимкнено"); return }
        this.scope = scope
        val a = adapter ?: return
        val ss = runCatching { a.listenUsingInsecureL2capChannel() }.getOrElse { runner.log("Bluetooth: L2CAP-канал не відкрився: ${it.message}"); return }
        server = ss
        runner.log("Bluetooth: канал ${ss.psm}")
        scope.launch {
            while (isActive) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                launch { s.use { runner.exchange(it.inputStream, it.outputStream, initiator = false, who = "ble:" + it.remoteDevice.address) { runCatching { s.close() } } } }
            }
        }
        advertise(ss.psm)
        scan()
        // Android притишує тривалий пошук (через 30 хв, при вимкненому екрані) — перезапускаємо
        // раз на 2 хв (ліміт системи — 5 запусків за 30 с, тут 3 за 2 хв).
        scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(120_000)
                stopScans()
                scan()
            }
        }
        // Нове повідомлення в черзі — одразу пробувати всіх, кого видно, не чекаючи 30 с.
        scope.launch { runner.pokes.collect { lastTry.entries.removeIf { !it.key.startsWith("raw:") } } }
    }

    private fun stopScans() {
        val sc = adapter?.bluetoothLeScanner
        runCatching { scanning?.let { sc?.stopScan(it) } }
        runCatching { scanningAll?.let { sc?.stopScan(it) } }
        runCatching { scanningData?.let { sc?.stopScan(it) } }
        scanning = null; scanningAll = null; scanningData = null
    }

    private fun advertise(psm: Int) {
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        // Усе в основній рекламі (17 байт із 31): 16-бітна служба + номер каналу й nonce.
        // «Відповідь на сканування» частина телефонів застосункам не віддає.
        val info = ByteBuffer.allocate(6).putShort(psm.toShort()).put(nonce).array()
        val data = AdvertiseData.Builder().addServiceUuid(SERVICE).addServiceData(SERVICE, info).setIncludeDeviceName(false).setIncludeTxPowerLevel(false).build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(s: AdvertiseSettings?) { runner.log("Bluetooth: реклама увімкнена") }
            override fun onStartFailure(code: Int) { runner.log("Bluetooth: реклама не запустилась (код $code)") }
        }
        advertising = cb
        runCatching { adv.startAdvertising(settings, data, cb) }.onFailure { runner.log("Bluetooth: реклама: ${it.message}") }
    }

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        fun callback(name: String) = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) = found(r)
            override fun onBatchScanResults(rs: MutableList<ScanResult>) = rs.forEach { found(it) }
            override fun onScanFailed(code: Int) { runner.log("Bluetooth: пошук ($name) не запустився (код $code)") }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // 1) З фільтром служби Шепоту — працює й з вимкненим екраном.
        val cb = callback("фільтр")
        scanning = cb
        runCatching { scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(SERVICE).build()), settings, cb) }
            .onFailure { runner.log("Bluetooth: пошук з фільтром: ${it.message}") }
        // Те саме, але фільтр за даними служби — деякі чипи вміють лише такий.
        val byData = callback("дані")
        scanningData = byData
        runCatching { scanner.startScan(listOf(ScanFilter.Builder().setServiceData(SERVICE, ByteArray(0), ByteArray(0)).build()), settings, byData) }
            .onFailure { runner.log("Bluetooth: пошук за даними: ${it.message}") }
        // 2) Без фільтра, перевірка в коді: частина чипів не вміє апаратно фільтрувати чужі служби.
        //    Android віддає такі результати лише при увімкненому екрані — для того й перший.
        val all = callback("усі")
        scanningAll = all
        runCatching { scanner.startScan(null, settings, all) }
            .onFailure { runner.log("Bluetooth: пошук усіх: ${it.message}") }
        runner.log("Bluetooth: шукаю")
        if (reporter?.isActive != true) reporter = scope?.launch {
            while (isActive) {
                kotlinx.coroutines.delay(60_000)
                runner.log("Bluetooth: за хвилину бачу пристроїв ${seenAny.size}, із Шепотом ${seenOurs.size}")
                seenAny.clear(); seenOurs.clear()
            }
        }
    }
    private var reporter: kotlinx.coroutines.Job? = null

    private fun found(r: ScanResult) {
        seenAny += r.device.address
        val uuids = r.scanRecord?.serviceUuids.orEmpty()
        val info = r.scanRecord?.getServiceData(SERVICE)
        if (info == null && SERVICE !in uuids) return // чужий пристрій
        seenOurs += r.device.address
        if (info == null || info.size < 6) {
            val k = "raw:" + r.device.address
            if (System.currentTimeMillis() - (lastTry[k] ?: 0) > 30_000) { lastTry[k] = System.currentTimeMillis(); runner.log("Bluetooth: телефон без даних каналу (стара версія?)") }
            return
        }
        val psm = ByteBuffer.wrap(info, 0, 2).short.toInt() and 0xffff
        val who = "ble:" + info.copyOfRange(2, 6).joinToString("") { "%02x".format(it) }
        val theirs = info.copyOfRange(2, 6)
        if (theirs.contentEquals(nonce)) return
        val now = System.currentTimeMillis()
        val first = firstSeen.getOrPut(who) { now }
        runner.noticed(who)
        // Підключається лише один із двох — інший чекає вхідного з'єднання.
        if (!runner.shouldInitiate(nonce, theirs, first)) return
        if (now - (lastTry[who] ?: 0) < 30_000) return
        lastTry[who] = now
        runner.log("Bluetooth: знайдено $who, канал $psm")
        val s = scope ?: return
        s.launch {
            if (Build.VERSION.SDK_INT < 29) return@launch
            val sock = runCatching { r.device.createInsecureL2capChannel(psm).apply { connect() } }
                .getOrElse { runner.log("Bluetooth: не вдалося з'єднатися з $who: ${it.message}"); return@launch }
            sock.use { runner.exchange(it.inputStream, it.outputStream, initiator = true, who = who) { runCatching { sock.close() } } }
        }
    }

    fun stop() {
        runCatching { advertising?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        stopScans()
        reporter?.cancel(); reporter = null
        advertising = null
        seenAny.clear(); seenOurs.clear()
        runCatching { server?.close() }
        server = null
        lastTry.clear()
        firstSeen.clear()
        scope = null
    }

    companion object {
        /** Служба Шепоту в рекламі BLE: 16-бітний ідентифікатор 0x5E7B (у базовому UUID Bluetooth). */
        val SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("00005e7b-0000-1000-8000-00805f9b34fb"))
    }
}
