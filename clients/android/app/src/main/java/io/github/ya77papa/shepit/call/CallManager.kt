package io.github.ya77papa.shepit.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.github.ya77papa.shepit.data.Body
import io.github.ya77papa.shepit.data.Repo
import io.github.ya77papa.shepit.service.Notifications
import io.github.ya77papa.shepit.service.ShepitService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Голосові дзвінки 1:1 — сумісні з веб-клієнтом:
 * WebRTC (DTLS-SRTP), сигналізація E2EE-повідомленнями Шепоту, без trickle ICE,
 * за замовчуванням лише через ретранслятор (IP не видно співрозмовнику).
 */
class CallManager(private val ctx: Context, private val repo: Repo) {
    enum class State { IDLE, OUTGOING, INCOMING, CONNECTING, ACTIVE }

    val state = MutableStateFlow(State.IDLE)
    val peer = MutableStateFlow("")
    val muted = MutableStateFlow(false)
    val speaker = MutableStateFlow(false)
    val startedAt = MutableStateFlow(0L)
    val relayed = MutableStateFlow(true)
    val error = MutableStateFlow("")

    /** Журнал останнього дзвінка: що відбувалось і коли (для діагностики, без вмісту розмови). */
    val log = MutableStateFlow<List<String>>(emptyList())
    private var t0 = 0L
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)

    fun trace(msg: String) {
        val now = System.currentTimeMillis()
        if (t0 == 0L) t0 = now
        log.value = (log.value + "${fmt.format(java.util.Date(now))} +${now - t0}мс  $msg").takeLast(80)
        android.util.Log.i("ShepitCall", msg)
    }

    private fun newTrace(msg: String) {
        t0 = 0L
        log.value = emptyList()
        trace(msg)
    }

    private val scope = repo.scope
    private var id = ""
    private var outgoing = false
    private var pendingOffer: String? = null
    private var pc: PeerConnection? = null
    private var track: AudioTrack? = null
    private var ringJob: Job? = null
    private var connectJob: Job? = null
    private var gathered: CompletableDeferred<Unit>? = null
    private var localTypes = ""
    private var remoteTypes = ""

    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null
    private var proximity: PowerManager.WakeLock? = null

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(ctx)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
    }

    init {
        scope.launch {
            repo.callSignals.collect { (from, b) ->
                runCatching { onSignal(from, b) }.onFailure { trace("✗ обробка ${b.op}: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
    }

    private fun newId(): String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private suspend fun signal(to: String, op: String, sdp: String? = null) {
        trace("→ надсилаю $op")
        try {
            repo.send(to, Body.Call(op, id, repo.api.now(), sdp), if (op == "offer") "call" else null)
            trace("→ $op надіслано")
        } catch (e: Exception) {
            trace("✗ $op не надіслано: ${e.message}")
            throw e
        }
    }

    // ---------- WebRTC ----------

    private fun candidateTypes(sdp: String) =
        Regex(" typ (\\w+)").findAll(sdp).map { it.groupValues[1] }.toSet().joinToString(", ").ifEmpty { "жодного" }

    private fun makePc(): PeerConnection {
        val turn = runCatching { repo.turn() }.onFailure { trace("✗ TURN: ${it.message}") }.getOrNull()
        trace(if (turn != null) "TURN: ${turn.urls.joinToString()}" else "TURN немає — пряме з'єднання")
        relayed.value = turn != null
        val servers = turn?.let {
            listOf(PeerConnection.IceServer.builder(it.urls).setUsername(it.username).setPassword(it.credential).createIceServer())
        } ?: emptyList()
        val cfg = PeerConnection.RTCConfiguration(servers).apply {
            iceTransportsType = if (turn != null) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        gathered = CompletableDeferred()
        val p = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
                trace("збір кандидатів: $s")
                if (s == PeerConnection.IceGatheringState.COMPLETE) gathered?.complete(Unit)
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                trace("з'єднання: $s")
                when (s) {
                    PeerConnection.PeerConnectionState.CONNECTED -> if (state.value != State.ACTIVE) {
                        state.value = State.ACTIVE
                        startedAt.value = System.currentTimeMillis()
                        connectJob?.cancel()
                        stopRinging()
                        updateProximity()
                    }
                    PeerConnection.PeerConnectionState.FAILED -> scope.launch { hangupWithError(diagnose()) }
                    else -> {}
                }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) { trace("сигналізація: $s") }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { trace("ICE: $s") }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceCandidate(c: IceCandidate) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        }) ?: error("Не вдалося створити з'єднання")
        val source = factory.createAudioSource(MediaConstraints())
        track = factory.createAudioTrack("audio0", source).also { p.addTrack(it, listOf("stream0")) }
        pc = p
        startAudio()
        return p
    }

    private suspend fun PeerConnection.sdp(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) = cont.resume(d)
            override fun onCreateFailure(e: String) = cont.resumeWithException(IllegalStateException(e))
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String) {}
        }
        val c = MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")) }
        if (offer) createOffer(obs, c) else createAnswer(obs, c)
    }

    private suspend fun PeerConnection.set(local: Boolean, d: SessionDescription) = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) {}
            override fun onCreateFailure(e: String) {}
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(e: String) = cont.resumeWithException(IllegalStateException(e))
        }
        if (local) setLocalDescription(obs, d) else setRemoteDescription(obs, d)
    }

    /** Без trickle: дочекатися всіх кандидатів (до 5 с) і віддати повний SDP. */
    private suspend fun gatheredSdp(p: PeerConnection): String {
        if (withTimeoutOrNull(5000) { gathered?.await() } == null) trace("збір кандидатів: 5 с минуло, беру що є")
        val sdp = p.localDescription.description
        trace("мої кандидати: ${candidateTypes(sdp)}")
        return sdp
    }

    private fun watchConnect() {
        connectJob?.cancel()
        connectJob = scope.launch {
            delay(25_000)
            if (state.value != State.ACTIVE) { trace("25 с без з'єднання"); hangupWithError(diagnose()) }
        }
    }

    private fun diagnose(): String = when {
        relayed.value && "relay" !in localTypes -> "Не вдалося з'єднатися: ретранслятор не відповідає (мої кандидати: $localTypes)"
        relayed.value && "relay" !in remoteTypes -> "Не вдалося з'єднатися: у співрозмовника ретранслятор не відповідає"
        else -> "Не вдалося з'єднатися"
    }

    /** Перевірка ретранслятора: чи видає він кандидата relay для цього телефона. */
    suspend fun checkRelay(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val turn = runCatching { repo.turn() }.getOrElse { return@withContext "✗ Не вдалося отримати дані ретранслятора: ${it.message}" }
            ?: return@withContext "✗ Ретранслятор на сервері не налаштований"
        turn.urls.map { url ->
            val done = CompletableDeferred<Unit>()
            val cfg = PeerConnection.RTCConfiguration(listOf(PeerConnection.IceServer.builder(url).setUsername(turn.username).setPassword(turn.credential).createIceServer())).apply {
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            val p = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) { if (s == PeerConnection.IceGatheringState.COMPLETE) done.complete(Unit) }
                override fun onSignalingChange(s: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceCandidate(c: IceCandidate) {}
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
                override fun onAddStream(s: MediaStream) {}
                override fun onRemoveStream(s: MediaStream) {}
                override fun onDataChannel(d: org.webrtc.DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
            }) ?: return@map "✗ $url: не вдалося створити з'єднання"
            try {
                p.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, org.webrtc.RtpTransceiver.RtpTransceiverInit(org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
                val o = p.sdp(offer = true)
                p.set(local = true, d = o)
                withTimeoutOrNull(8000) { done.await() }
                val types = candidateTypes(p.localDescription.description)
                if ("relay" in types) "✓ $url" else "✗ $url — немає відповіді"
            } catch (e: Exception) {
                "✗ $url: ${e.message}"
            } finally {
                runCatching { p.close(); p.dispose() }
            }
        }.joinToString("\n")
    }

    // ---------- керування ----------

    fun start(to: String) {
        if (state.value != State.IDLE) return
        error.value = ""
        state.value = State.OUTGOING
        peer.value = to
        newTrace("вихідний дзвінок")
        ShepitService.mic(ctx, true)
        id = newId()
        outgoing = true
        scope.launch(Dispatchers.IO) {
            try {
                val p = makePc()
                val offer = p.sdp(offer = true)
                p.set(local = true, d = offer)
                val sdp = gatheredSdp(p)
                localTypes = candidateTypes(sdp)
                if (state.value != State.OUTGOING) return@launch
                signal(to, "offer", sdp)
                startRingback()
                ringJob = scope.launch { delay(45_000); if (state.value == State.OUTGOING) hangup("Без відповіді") }
            } catch (e: Exception) {
                trace("✗ ${e.javaClass.simpleName}: ${e.message}")
                finish("📞 ${e.message ?: "Помилка дзвінка"}", isError = true)
            }
        }
    }

    private suspend fun onSignal(from: String, b: Body.Call) {
        if (b.op == "offer" && state.value == State.IDLE) newTrace("вхідний offer")
        trace("← ${b.op} (вік ${repo.api.now() - b.at} мс)")
        if (b.op == "offer") {
            if (repo.api.now() - b.at > 60_000) {
                repo.log(from, "📞 Пропущений дзвінок")
                return
            }
            if (state.value != State.IDLE) {
                if (b.id != id) repo.send(from, Body.Call("busy", b.id, repo.api.now()))
                return
            }
            state.value = State.INCOMING
            peer.value = from
            id = b.id
            outgoing = false
            pendingOffer = b.sdp
            startRinging()
            runCatching { Notifications.showIncomingCall(ctx, repo.nameOf(from)) }.onFailure { trace("✗ сповіщення: ${it.message}") }
            // Застосунок відкритий — одразу показати екран дзвінка (повноекранне сповіщення
            // спрацьовує лише на заблокованому/вимкненому екрані).
            if (io.github.ya77papa.shepit.ui.MainActivity.visible) runCatching {
                ctx.startActivity(android.content.Intent(ctx, io.github.ya77papa.shepit.ui.CallActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            ringJob = scope.launch { delay(45_000); if (state.value == State.INCOMING) finish("📞 Пропущений дзвінок") }
            return
        }
        if (from != peer.value || b.id != id) { trace("пропускаю: чужий або старий дзвінок"); return }
        when (b.op) {
            "answer" -> if (state.value == State.OUTGOING && b.sdp != null) {
                ringJob?.cancel()
                stopRinging()
                state.value = State.CONNECTING
                remoteTypes = candidateTypes(b.sdp)
                watchConnect()
                try {
                    pc?.set(local = false, d = SessionDescription(SessionDescription.Type.ANSWER, b.sdp))
                    trace("відповідь прийнято, кандидати співрозмовника: $remoteTypes")
                } catch (e: Exception) {
                    trace("✗ відповідь не прийнято: ${e.message}")
                    hangupWithError("Помилка з'єднання: ${e.message}")
                }
            }
            "reject" -> finish("📞 Дзвінок відхилено")
            "busy" -> finish("📞 Співрозмовник зайнятий")
            "end" -> finish(summary(if (state.value == State.INCOMING) "📞 Пропущений дзвінок" else null))
        }
    }

    fun accept() {
        if (state.value != State.INCOMING) return
        val offer = pendingOffer ?: return
        ringJob?.cancel()
        stopRinging()
        Notifications.cancelIncomingCall(ctx)
        state.value = State.CONNECTING
        trace("приймаю дзвінок")
        ShepitService.mic(ctx, true)
        scope.launch(Dispatchers.IO) {
            try {
                val p = makePc()
                remoteTypes = candidateTypes(offer)
                trace("кандидати співрозмовника: $remoteTypes")
                p.set(local = false, d = SessionDescription(SessionDescription.Type.OFFER, offer))
                val answer = p.sdp(offer = false)
                p.set(local = true, d = answer)
                val sdp = gatheredSdp(p)
                localTypes = candidateTypes(sdp)
                watchConnect()
                signal(peer.value, "answer", sdp)
            } catch (e: Exception) {
                trace("✗ ${e.javaClass.simpleName}: ${e.message}")
                runCatching { signal(peer.value, "end") }
                finish("📞 ${e.message ?: "Помилка дзвінка"}", isError = true)
            }
        }
    }

    fun reject() {
        if (state.value != State.INCOMING) return
        val to = peer.value
        scope.launch {
            runCatching { signal(to, "reject") }
            finish("📞 Ти відхилив(-ла) дзвінок")
        }
    }

    fun hangup(reason: String? = null) {
        if (state.value == State.IDLE) return
        val to = peer.value
        scope.launch {
            runCatching { signal(to, "end") }
            finish(if (reason != null) "📞 $reason" else summary(null))
        }
    }

    private suspend fun hangupWithError(text: String) {
        val to = peer.value
        runCatching { signal(to, "end") }
        finish("📞 $text", isError = true)
    }

    fun toggleMute() {
        muted.value = !muted.value
        track?.setEnabled(!muted.value)
    }

    fun toggleSpeaker() {
        speaker.value = !speaker.value
        route()
        updateProximity()
    }

    private fun summary(fallback: String?): String {
        val s = startedAt.value
        if (s > 0) {
            val d = (System.currentTimeMillis() - s) / 1000
            return "📞 ${if (outgoing) "Вихідний" else "Вхідний"} дзвінок · %d:%02d".format(d / 60, d % 60)
        }
        return fallback ?: if (outgoing) "📞 Скасований дзвінок" else "📞 Пропущений дзвінок"
    }

    @Synchronized
    private fun finish(text: String, isError: Boolean = false) {
        if (state.value == State.IDLE) return
        trace("кінець: $text")
        val who = peer.value
        ringJob?.cancel()
        connectJob?.cancel()
        stopRinging()
        Notifications.cancelIncomingCall(ctx)
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        pc = null
        track = null
        pendingOffer = null
        stopAudio()
        state.value = State.IDLE
        ShepitService.mic(ctx, false)
        muted.value = false
        speaker.value = false
        startedAt.value = 0
        if (isError) error.value = text.removePrefix("📞 ")
        if (who.isNotEmpty()) repo.log(who, text)
        peer.value = ""
    }

    // ---------- звук ----------

    private fun startAudio() {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        route()
    }

    private fun route() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val want = if (speaker.value) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            // Гарнітура/Bluetooth мають пріоритет — їх не перебиваємо.
            val external = audio.availableCommunicationDevices.firstOrNull {
                it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            val dev = if (!speaker.value && external != null) external else audio.availableCommunicationDevices.firstOrNull { it.type == want }
            if (dev != null) audio.setCommunicationDevice(dev)
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = speaker.value
        }
    }

    private fun stopAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
        else @Suppress("DEPRECATION") { audio.isSpeakerphoneOn = false }
        audio.mode = AudioManager.MODE_NORMAL
        proximity?.let { if (it.isHeld) it.release() }
        proximity = null
    }

    /** Екран гасне біля вуха (як у звичайному дзвінку), але не на гучному зв'язку. */
    private fun updateProximity() {
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (proximity == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "shepit:proximity")
        }
        val p = proximity ?: return
        val want = state.value == State.ACTIVE && !speaker.value
        if (want && !p.isHeld) p.acquire(3 * 60 * 60 * 1000L)
        if (!want && p.isHeld) p.release()
    }

    private fun vibrator(): Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        ctx.getSystemService(VibratorManager::class.java).defaultVibrator
    else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    /** Вхідний: системний рингтон на гучності дзвінка + вібрація. */
    private fun startRinging() {
        runCatching {
            ringtone = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                play()
            }
        }
        runCatching { vibrator().vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)) }
    }

    /** Вихідний: гудки в динаміку розмови. */
    private fun startRingback() {
        runCatching { tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }
        ringJob = scope.launch {
            while (state.value == State.OUTGOING) {
                tone?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1200)
                delay(4000)
            }
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator().cancel() }
        runCatching { tone?.release() }
        tone = null
    }
}
