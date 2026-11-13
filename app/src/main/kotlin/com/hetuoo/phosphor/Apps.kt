package com.hetuoo.phosphor

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build

class AppEntry(val component: ComponentName, label: String) {
    val key: String = component.flattenToString()
    val label = label.uppercase()
    val lower = label.lowercase()
    @Volatile
    var icon: Bitmap? = null
}

object Icons {
    /** Rendered icons survive app-list reloads; cleared when the colour theme changes. */
    val cache = HashMap<String, Bitmap>()
    var cacheColor = 0

    /** Redraws an app icon as single-colour phosphor so it belongs on the terminal. */
    fun render(d: Drawable, size: Int, fg: Int): Bitmap {
        val raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val rc = Canvas(raw)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        val mono = if (Build.VERSION.SDK_INT >= 33 && d is AdaptiveIconDrawable) d.monochrome else null
        if (mono != null) {
            // Adaptive layers are 108dp with the middle 72dp visible, so draw oversized.
            val bleed = size / 4
            mono.setBounds(-bleed, -bleed, size + bleed, size + bleed)
            mono.draw(rc)
            paint.colorFilter = PorterDuffColorFilter(fg, PorterDuff.Mode.SRC_IN)
        } else {
            d.setBounds(0, 0, size, size)
            d.draw(rc)
            // Brightness becomes phosphor intensity; hue is thrown away.
            val g = 1.15f
            val r = Color.red(fg) / 255f * g
            val gr = Color.green(fg) / 255f * g
            val b = Color.blue(fg) / 255f * g
            paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                r * 0.299f, r * 0.587f, r * 0.114f, 0f, 0f,
                gr * 0.299f, gr * 0.587f, gr * 0.114f, 0f, 0f,
                b * 0.299f, b * 0.587f, b * 0.114f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(raw, 0f, 0f, paint)
        raw.recycle()
        return out
    }
}
