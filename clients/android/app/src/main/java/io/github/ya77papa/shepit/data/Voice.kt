package io.github.ya77papa.shepit.data

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Запис голосу: Opus/OGG (Android 10+) або AAC/MP4 — обидва грає і браузер, і інші телефони. */
class VoiceRecorder(private val context: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var started = 0L
    var mime = ""
        private set

    val active get() = rec != null

    fun start() {
        val ogg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val f = File(context.cacheDir, if (ogg) "rec.ogg" else "rec.m4a").also { it.delete() }
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        if (ogg) {
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            mime = "audio/ogg;codecs=opus"
        } else {
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mime = "audio/mp4"
        }
        r.setAudioEncodingBitRate(16_000)
        r.setAudioSamplingRate(if (ogg) 48_000 else 16_000)
        r.setMaxDuration(MAX_MS.toInt())
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        rec = r
        file = f
        started = System.currentTimeMillis()
    }

    fun elapsed() = if (rec != null) System.currentTimeMillis() - started else 0L

    /** Зупинити: (дані, тривалість) або null — надто коротко чи скасовано. */
    fun stop(cancel: Boolean = false): Pair<ByteArray, Long>? {
        val r = rec ?: return null
        val dur = System.currentTimeMillis() - started
        rec = null
        runCatching { r.stop() }
        r.release()
        val f = file ?: return null
        if (cancel || dur < 400) { f.delete(); return null }
        return f.readBytes().also { f.delete() } to dur.coerceAtMost(MAX_MS)
    }

    companion object {
        const val MAX_MS = 30_000L
    }
}

/** Програвання голосових: один плеєр на застосунок. */
object VoicePlayer {
    private var player: MediaPlayer? = null
    var playingId: Long = -1
        private set

    fun toggle(context: Context, id: Long, data: ByteArray, mime: String?, onDone: () -> Unit) {
        val was = playingId
        stop()
        if (was == id) { onDone(); return }
        val ext = when {
            mime == null -> "bin"
            "ogg" in mime -> "ogg"
            "webm" in mime -> "webm"
            "mp4" in mime || "aac" in mime -> "m4a"
            else -> "bin"
        }
        val f = File(context.cacheDir, "play.$ext").apply { writeBytes(data) }
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnCompletionListener { stop(); onDone() }
            setOnErrorListener { _, _, _ -> stop(); onDone(); true }
            prepare()
            start()
        }
        playingId = id
    }

    fun stop() {
        player?.runCatching { stop(); release() }
        player = null
        playingId = -1
    }
}
