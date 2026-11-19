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
    /** Випадковий ідентифікатор цього запуску — щоб рахувати телефони поруч (адреси BLE змінюються). */
    private val nonce = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
    /** Коли востаннє обмінювались із телефоном (за nonce): не частіше ніж раз на 30 с. */
    private val lastTry = ConcurrentHashMap<String, Long>()
    private var scope: CoroutineScope? = null

    fun allowed(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false // L2CAP-канали — з Android 10
        val perms = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } && adapter?.isEnabled == true
    }

    fun start(scope: CoroutineScope) {
        if (Build.VERSION.SDK_INT < 29 || !allowed()) return
        this.scope = scope
        val a = adapter ?: return
        val ss = runCatching { a.listenUsingInsecureL2capChannel() }.getOrNull() ?: return
        server = ss
        scope.launch {
            while (isActive) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                launch { s.use { runner.exchange(it.inputStream, it.outputStream, initiator = false, who = "ble:" + it.remoteDevice.address) } }
            }
        }
        advertise(ss.psm)
        scan()
    }

    private fun advertise(psm: Int) {
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(SERVICE).setIncludeDeviceName(false).build()
        // Номер каналу й nonce — у відповіді на сканування (в основну рекламу 128-бітний UUID двічі не влазить).
        val info = ByteBuffer.allocate(6).putShort(psm.toShort()).put(nonce).array()
        val resp = AdvertiseData.Builder().addServiceData(SERVICE, info).setIncludeDeviceName(false).build()
        val cb = object : AdvertiseCallback() {}
        advertising = cb
        runCatching { adv.startAdvertising(settings, data, resp, cb) }
    }

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) = found(r)
            override fun onBatchScanResults(rs: MutableList<ScanResult>) = rs.forEach { found(it) }
        }
        scanning = cb
        val filters = listOf(ScanFilter.Builder().setServiceUuid(SERVICE).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        runCatching { scanner.startScan(filters, settings, cb) }
    }

    private fun found(r: ScanResult) {
        val info = r.scanRecord?.getServiceData(SERVICE) ?: return
        if (info.size < 6) return
        val psm = ByteBuffer.wrap(info, 0, 2).short.toInt() and 0xffff
        val who = "ble:" + info.copyOfRange(2, 6).joinToString("") { "%02x".format(it) }
        if (info.copyOfRange(2, 6).contentEquals(nonce)) return
        runner.noticed(who)
        val now = System.currentTimeMillis()
        if (now - (lastTry[who] ?: 0) < 30_000) return
        lastTry[who] = now
        val s = scope ?: return
        s.launch {
            if (Build.VERSION.SDK_INT < 29) return@launch
            val sock = runCatching { r.device.createInsecureL2capChannel(psm).apply { connect() } }.getOrNull() ?: return@launch
            sock.use { runner.exchange(it.inputStream, it.outputStream, initiator = true, who = who) }
        }
    }

    fun stop() {
        runCatching { advertising?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        runCatching { scanning?.let { adapter?.bluetoothLeScanner?.stopScan(it) } }
        advertising = null; scanning = null
        runCatching { server?.close() }
        server = null
        lastTry.clear()
        scope = null
    }

    companion object {
        /** Служба Шепоту в рекламі BLE (випадковий 128-бітний UUID). */
        val SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("5e7b1f3a-4c2d-4b8e-9a61-0d3c5e9f2b17"))
    }
}
