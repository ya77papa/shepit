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
 * Етапи 1–2 (docs/mesh-concept.md): лише власні конверти, без естафети.
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

    /** Обмін через готовий потік. [who] — стабільний для цього телефона ідентифікатор транспорту. */
    suspend fun exchange(input: InputStream, output: OutputStream, initiator: Boolean, who: String) {
        noticed(who)
        exchangeLock.withLock {
            runCatching { MeshLink(node).run(input, output, initiator) }
                .onSuccess { log("обмін з $who: надіслано ${it.sent}, отримано ${it.received}, підтверджено ${it.delivered}") }
                .onFailure { log("обмін з $who не вдався: ${it.javaClass.simpleName} ${it.message ?: ""}") }
        }
    }

    /** Нові конверти в черзі — транспортам варто пройтись по відомих телефонах. */
    val pokes get() = repo.meshPoke
}
