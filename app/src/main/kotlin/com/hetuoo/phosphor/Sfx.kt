package com.hetuoo.phosphor

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** Low-latency playback of the synthesized button and switch sounds. */
class Sfx(ctx: Context) {
    var enabled = true

    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val tab = load(ctx, "tab")
    private val tick = load(ctx, "tick")
    private val select = load(ctx, "select")
    private val back = load(ctx, "back")
    private val boot = load(ctx, "boot")

    private fun load(ctx: Context, name: String) = try {
        ctx.assets.openFd("sfx/$name.wav").use { pool.load(it, 1) }
    } catch (e: Exception) {
        0
    }

    private fun play(id: Int, vol: Float, rate: Float = 1f) {
        if (enabled && id != 0) pool.play(id, vol, vol, 1, 0, rate)
    }

    fun tab() = play(tab, 1f)
    fun select() = play(select, 0.9f)
    fun back() = play(back, 0.9f)

    /** Slight random pitch so repeated key ticks don't sound machine-gunned. */
    fun tick() = play(tick, 0.7f, 0.94f + Math.random().toFloat() * 0.12f)

    /** The pool loads asynchronously, so the power-on sound waits for it. */
    fun bootWhenReady() {
        if (boot == 0) return
        pool.setOnLoadCompleteListener { _, id, status ->
            if (id == boot && status == 0) play(boot, 0.8f)
        }
    }

    fun release() = pool.release()
}
