package io.github.ya77papa.shepit.mesh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * Етап 1: телефони в одній Wi-Fi мережі (навіть без інтернету) знаходять одне одного
 * через mDNS (NSD) і обмінюються по TCP.
 */
class LanTransport(private val ctx: Context, private val runner: MeshRunner) {
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val myName = "shepit-" + java.util.UUID.randomUUID().toString().take(8)
    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    /** Знайдені телефони: ім'я служби → адреса. */
    private val peers = ConcurrentHashMap<String, InetSocketAddress>()
    /** До Android 14 NSD розв'язує лише одну службу за раз — черга. */
    private val toResolve = LinkedBlockingQueue<NsdServiceInfo>()

    fun start(scope: CoroutineScope) {
        val ss = runCatching { ServerSocket(0) }.getOrElse { runner.log("Wi-Fi: не вдалося відкрити порт: ${it.message}"); return }
        runner.log("Wi-Fi: слухаю порт ${ss.localPort}")
        server = ss
        multicast = runCatching {
            ctx.applicationContext.getSystemService(WifiManager::class.java).createMulticastLock("shepit-mesh").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        // Приймати вхідні.
        scope.launch {
            while (isActive && !ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                launch { handle(s, initiator = false, who = "lan:" + s.inetAddress.hostAddress) }
            }
        }
        register(ss.localPort)
        discover()
        scope.launch {
            while (isActive) {
                val info = runCatching { toResolve.take() }.getOrNull() ?: break
                resolve(info)
            }
        }
        // Раз на 30 с (або одразу, коли є що надіслати) — обмін з усіма відомими.
        scope.launch {
            while (isActive) {
                for ((name, addr) in peers) {
                    val s = runCatching { Socket().apply { connect(addr, 5_000) } }.getOrNull()
                    if (s == null) { runner.log("Wi-Fi: не вдалося з'єднатися з $name"); peers.remove(name); continue }
                    handle(s, initiator = true, who = "lan:" + addr.address.hostAddress)
                }
                withTimeoutOrNull(30_000) { runner.pokes.first() }
            }
        }
    }

    private suspend fun handle(s: Socket, initiator: Boolean, who: String) {
        s.use {
            it.soTimeout = 20_000
            runner.exchange(it.getInputStream(), it.getOutputStream(), initiator, who)
        }
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply { serviceName = myName; serviceType = TYPE; this.port = port }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { runner.log("Wi-Fi: оголошено себе в мережі") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { runner.log("Wi-Fi: не вдалося оголосити себе (код $code)") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        registration = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun discover() {
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceName != myName && i.serviceName.startsWith("shepit-")) { runner.log("Wi-Fi: знайдено ${i.serviceName}"); toResolve.offer(i) }
            }
            override fun onServiceLost(i: NsdServiceInfo) { peers.remove(i.serviceName) }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { runner.log("Wi-Fi: пошук не запустився (код $code)") }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        discovery = l
        runCatching { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    @Suppress("DEPRECATION") // resolveService: новий API лише з Android 14
    private suspend fun resolve(info: NsdServiceInfo) {
        val done = kotlinx.coroutines.CompletableDeferred<NsdServiceInfo?>()
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(r: NsdServiceInfo) { done.complete(r) }
                override fun onResolveFailed(r: NsdServiceInfo, code: Int) { done.complete(null) }
            })
        }.onFailure { done.complete(null) }
        val r = withTimeoutOrNull(10_000) { done.await() } ?: return
        val host = r.host ?: return
        peers[r.serviceName] = InetSocketAddress(host, r.port)
        runner.noticed("lan:" + host.hostAddress)
        runner.pokes.tryEmit(Unit) // одразу обмінятись
        delay(100)
    }

    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null; discovery = null
        runCatching { server?.close() }
        server = null
        runCatching { multicast?.release() }
        multicast = null
        peers.clear()
        toResolve.clear()
    }

    companion object {
        const val TYPE = "_shepit._tcp."
    }
}
