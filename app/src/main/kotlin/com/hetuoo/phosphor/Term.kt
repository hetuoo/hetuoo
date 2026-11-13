package com.hetuoo.phosphor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.util.TypedValue
import android.widget.TextView

/** Shared look of the terminal: one phosphor colour, one font, a few drawing helpers. */
object Term {
    val THEMES = arrayOf(
        "GREEN" to 0xFF3DFF7A.toInt(),
        "AMBER" to 0xFFFFB627.toInt(),
        "ICE" to 0xFF7FE9FF.toInt(),
        "PAPER" to 0xFFE4EEE4.toInt(),
    )

    var fg = THEMES[0].second
        private set
    var bg = Color.BLACK
        private set
    var face: Typeface = Typeface.MONOSPACE
        private set

    private var density = 1f
    private var fontScale = 1f

    fun init(ctx: Context, theme: Int) {
        val dm = ctx.resources.displayMetrics
        density = dm.density
        fontScale = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, dm)
        fg = THEMES[theme.coerceIn(0, THEMES.size - 1)].second
        bg = shade(0.045f)
        face = try {
            Typeface.createFromAsset(ctx.assets, "fonts/VT323-Regular.ttf")
        } catch (e: Exception) {
            Typeface.MONOSPACE
        }
    }

    fun dp(v: Float) = v * density
    fun sp(v: Float) = v * fontScale

    /** The phosphor colour at a fraction of full brightness (opaque). */
    fun shade(f: Float) = Color.rgb(
        (Color.red(fg) * f).toInt(), (Color.green(fg) * f).toInt(), (Color.blue(fg) * f).toInt()
    )

    fun glow() = Color.argb(150, Color.red(fg), Color.green(fg), Color.blue(fg))

    fun paint(sizeSp: Float, color: Int = fg, glow: Boolean = true) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = sp(sizeSp)
            this.color = color
            if (glow) setShadowLayer(dp(5f), 0f, 0f, glow())
        }

    fun text(ctx: Context, s: String, sizeSp: Float, color: Int = fg) = TextView(ctx).apply {
        typeface = face
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        setShadowLayer(dp(5f), 0f, 0f, glow())
        includeFontPadding = false
        text = s
    }

    fun border(strokeDp: Float = 1.5f, color: Int = shade(0.7f), fill: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(strokeDp).toInt().coerceAtLeast(1), color)
        }

    /** A segmented level meter, lit up to [frac]. */
    fun drawSegments(c: Canvas, p: Paint, x: Float, y: Float, w: Float, h: Float, frac: Float, n: Int) {
        val gap = dp(2f)
        val seg = (w - gap * (n - 1)) / n
        val lit = Math.round(frac.coerceIn(0f, 1f) * n)
        for (i in 0 until n) {
            p.color = if (i < lit) fg else shade(0.2f)
            val l = x + i * (seg + gap)
            c.drawRect(l, y, l + seg, y + h, p)
        }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("phosphor", Context.MODE_PRIVATE)

    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()
    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()
    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()
    var scanlines: Boolean
        get() = sp.getBoolean("scanlines", true)
        set(v) = sp.edit().putBoolean("scanlines", v).apply()
    var flicker: Boolean
        get() = sp.getBoolean("flicker", true)
        set(v) = sp.edit().putBoolean("flicker", v).apply()
    var icons: Boolean
        get() = sp.getBoolean("icons", true)
        set(v) = sp.edit().putBoolean("icons", v).apply()
    var favsSeeded: Boolean
        get() = sp.getBoolean("favs_seeded", false)
        set(v) = sp.edit().putBoolean("favs_seeded", v).apply()

    /** Pinned apps, in home-screen order, as flattened component names. */
    var favs: List<String>
        get() = sp.getString("favs", "")!!.split('\n').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("favs", v.joinToString("\n")).apply()
}
