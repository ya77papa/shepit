package io.github.ya77papa.shepit.mesh

import android.content.Context
import io.github.ya77papa.shepit.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Робота без інтернету: шукає телефони з Шепотом поруч (локальна Wi-Fi мережа, Bluetooth)
 * і обмінюється з ними зашифрованими конвертами через [MeshLink].
 * Етапи 1–4 (docs/mesh-concept.md): свої конверти, естафета чужих і шлюз через телефон з інтернетом.
 */
class MeshRunner(private val ctx: Context, private val repo: Repo) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val lan = LanTransport(ctx, this)
    private val ble = BleTransport(ctx, this)
    /** Один обмін за раз: стан сесій Signal змінюється при кожному повідомленні. */
    private val exchangeLock = Mutex()
    /** Хто з телефонів був поруч і коли (для «поруч N телефонів»). */
    private val seen = ConcurrentHashMap<String, Long>()

    val running get() = job?.isActive == true

    /** Журнал для діагностики (без вмісту повідомлень): що знайдено, що не вдалося. */
    val log = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
    fun log(line: String) {
        val l = fmt.format(java.util.Date()) + " " + line
        android.util.Log.i("ShepitMesh", line)
        log.value = (log.value + l).takeLast(80)
    }

    private val node = object : MeshNode {
        override fun offers() = repo.meshOffers()
        override fun isForMe(tag: ByteArray) = Base64.getEncoder().encodeToString(tag) in repo.meshTagsForMe()
        override fun receive(env: MeshEnvelope) = runBlocking { repo.meshReceive(env) }
        override fun delivered(ids: List<String>) = runBlocking { repo.meshDelivered(ids) }
        override fun helloTokens(peerNonce: ByteArray) = repo.meshHelloTokens(peerNonce)
        override fun isContact(myNonce: ByteArray, tokens: List<ByteArray>) = repo.meshIsContact(myNonce, tokens)
        override fun carries(fromContact: Boolean) = repo.meshCarries(fromContact)
        override fun has(id: String) = repo.meshHas(id)
        override fun room(size: Int) = repo.meshRoom(size)
        override fun carry(env: MeshEnvelope) = repo.meshCarry(env)
    }

    /** Коли востаннє хтось сам підключився до нас (для черговості підключень, див. [shouldInitiate]). */
    @Volatile var lastIncoming = 0L
        private set

    /**
     * Підключається лише один з двох (менший nonce) — інакше обидва одночасно відкривають
     * з'єднання й заважають одне одному. Якщо інший довго не підключається (його пошук не бачить нас) —
     * підключаємось самі.
     */
    fun shouldInitiate(mine: ByteArray, theirs: ByteArray, firstSeen: Long): Boolean {
        for (i in 0 until minOf(mine.size, theirs.size)) {
            val a = mine[i].toInt() and 0xff; val b = theirs[i].toInt() and 0xff
            if (a != b) return a < b || fallback(firstSeen)
        }
        return true
    }

    private fun fallback(firstSeen: Long): Boolean {
        val now = System.currentTimeMillis()
        return now - firstSeen > 45_000 && now - lastIncoming > 60_000
    }

    @Synchronized
    fun start() {
        if (running) return
        log("старт")
        job = scope.launch {
            lan.start(this)
            ble.start(this)
            // Скільки телефонів поруч: бачили за останні 2 хв.
            while (isActive) {
                val now = System.currentTimeMillis()
                seen.entries.removeIf { now - it.value > 120_000 }
                repo.meshNearby.value = seen.size
                delay(10_000)
            }
        }
    }

    @Synchronized
    fun stop() {
        if (running) log("стоп")
        lan.stop()
        ble.stop()
        job?.cancel()
        job = null
        seen.clear()
        repo.meshNearby.value = 0
    }

    /** Транспорт помітив телефон з Шепотом (ще до обміну). */
    fun noticed(who: String) {
        seen[who] = System.currentTimeMillis()
        repo.meshNearby.value = seen.size
    }

    /**
     * Обмін через готовий потік. [who] — ідентифікатор телефона для журналу й лічильника.
     * [close] закриває з'єднання: у Bluetooth-сокетів немає тайм-ауту, тож завислий обмін
     * (телефон відійшов) ми обриваємо самі — інакше він тримав би чергу обмінів назавжди.
     */
    suspend fun exchange(input: InputStream, output: OutputStream, initiator: Boolean, who: String, close: () -> Unit) {
        noticed(who)
        val watchdog = scope.launch {
            delay(EXCHANGE_TIMEOUT_MS)
            log("обмін з $who: завис, обриваю")
            runCatching { close() }
        }
        try {
            // Інший обмін триває надто довго — не чекати, спробуємо наступного разу.
            if (kotlinx.coroutines.withTimeoutOrNull(15_000) { exchangeLock.lock() } == null) {
                log("обмін з $who: зайнято, пізніше")
                runCatching { close() }
                return
            }
            try {
                if (!initiator) lastIncoming = System.currentTimeMillis()
                runCatching { MeshLink(node).run(input, output, initiator) }
                    .onSuccess {
                        log("обмін з $who: надіслано ${it.sent}, отримано ${it.received}, підтверджено ${it.delivered}" + if (it.carried > 0) ", взято нести ${it.carried}" else "")
                        // Узяв чужі — якщо інтернет є, одразу на сервер (шлюз).
                        if (it.carried > 0) scope.launch { runCatching { repo.uploadCarried() }.onSuccess { n -> if (n > 0) log("шлюз: передано на сервер $n") } }
                    }
                    .onFailure { log("обмін з $who не вдався: ${it.javaClass.simpleName} ${it.message ?: ""}") }
            } finally {
                exchangeLock.unlock()
            }
        } finally {
            watchdog.cancel()
        }
    }

    companion object {
        /** Найдовший обмін: фото по Bluetooth із запасом. */
        const val EXCHANGE_TIMEOUT_MS = 45_000L
    }

    /** Нові конверти в черзі — транспортам варто пройтись по відомих телефонах. */
    val pokes get() = repo.meshPoke
}
