package com.hetuoo.phosphor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import java.util.Random

/**
 * Sits on top of the whole UI and makes it look like a picture tube: scanlines, darkened
 * corners, a slow refresh band, faint flicker, and a power-on sweep. Never takes touches.
 */
class CrtOverlay(ctx: Context) : View(ctx) {
    var scanlines = true
    var flicker = true
    var running = false
        set(v) {
            field = v
            if (v) invalidate()
        }

    private val scanPaint = Paint()
    private val vignette = Paint()
    private val band = Paint()
    private val solid = Paint()
    private val rnd = Random()
    private var bandH = 1f
    private var powerStart = -10_000L
    private var glitchUntil = 0L

    fun powerOn() {
        powerStart = SystemClock.uptimeMillis()
        invalidate()
    }

    fun glitch() {
        glitchUntil = SystemClock.uptimeMillis() + 130
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        val period = Term.dp(3f).toInt().coerceAtLeast(2)
        val dark = (period / 3).coerceAtLeast(1)
        val tile = Bitmap.createBitmap(1, period, Bitmap.Config.ARGB_8888)
        for (y in period - dark until period) tile.setPixel(0, y, 0x60000000)
        scanPaint.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)

        vignette.shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0xB0000000.toInt()),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        ).apply {
            setLocalMatrix(Matrix().apply {
                setScale(w * 0.85f, h * 0.72f)
                postTranslate(w / 2f, h / 2f)
            })
        }

        bandH = h * 0.22f
        val c = Term.fg
        band.shader = LinearGradient(
            0f, 0f, 0f, bandH,
            intArrayOf(Color.TRANSPARENT, Color.argb(22, Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        val live = flicker && running

        if (live) {
            val y = (now % 7000L) / 7000f * (h + bandH) - bandH
            canvas.save()
            canvas.translate(0f, y)
            canvas.drawRect(0f, 0f, w, bandH, band)
            canvas.restore()
            canvas.drawColor(Color.argb(rnd.nextInt(9), 0, 0, 0))
        }

        val glitching = now < glitchUntil
        if (glitching) {
            solid.color = Term.fg
            repeat(4) {
                solid.alpha = 14 + rnd.nextInt(30)
                val y = rnd.nextFloat() * h
                canvas.drawRect(0f, y, w, y + Term.dp(2f + rnd.nextInt(16)), solid)
            }
        }

        if (scanlines) canvas.drawRect(0f, 0f, w, h, scanPaint)
        canvas.drawRect(0f, 0f, w, h, vignette)

        // Power-on: a bright line snaps across the middle, then the picture opens vertically.
        val t = (now - powerStart) / 420f
        val powering = t < 1f
        if (powering) {
            val cy = h / 2
            val line = Term.dp(1.5f)
            val halfW: Float
            val halfH: Float
            if (t < 0.3f) {
                halfW = w / 2 * (t / 0.3f)
                halfH = line
            } else {
                val e = (t - 0.3f) / 0.7f
                halfW = w / 2
                halfH = line + cy * e * e * (3 - 2 * e)
            }
            solid.color = Color.BLACK
            canvas.drawRect(0f, 0f, w, cy - halfH, solid)
            canvas.drawRect(0f, cy + halfH, w, h, solid)
            canvas.drawRect(0f, cy - halfH, w / 2 - halfW, cy + halfH, solid)
            canvas.drawRect(w / 2 + halfW, cy - halfH, w, cy + halfH, solid)
            solid.color = Color.WHITE
            solid.alpha = (200 * (1 - t)).toInt()
            canvas.drawRect(w / 2 - halfW, cy - line, w / 2 + halfW, cy + line, solid)
        }

        if (powering || glitching) postInvalidateOnAnimation()
        else if (live) postInvalidateDelayed(50)
    }
}
