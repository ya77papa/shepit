package io.github.ya77papa.shepit.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import io.github.ya77papa.shepit.R

/** Тихі звуки у відкритій бесіді: «надіслано» і «отримано». Мовчить у беззвучному/вібро-режимі. */
class ChatSounds(private val ctx: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val out = pool.load(ctx, R.raw.msg_out, 1)
    private val inc = pool.load(ctx, R.raw.msg_in, 1)

    private fun play(id: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        pool.play(id, 0.45f, 0.45f, 1, 0, 1f)
    }

    fun sent() = play(out)
    fun received() = play(inc)
    fun release() = pool.release()
}
