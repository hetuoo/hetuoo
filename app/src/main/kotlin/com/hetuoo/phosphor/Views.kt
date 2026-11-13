package com.hetuoo.phosphor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.sin

/** One line of the terminal: optional icon, label, optional right-hand note. Inverts when pressed. */
class RowView(ctx: Context) : View(ctx) {
    private var icon: Bitmap? = null
    private var iconSlot = false
    private var label = ""
    private var note = ""

    private val labelPaint = Term.paint(27f)
    private val notePaint = Term.paint(22f, Term.shade(0.6f), glow = false)
    private val fill = Paint().apply { color = Term.fg }
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val iconRect = RectF()

    fun bind(label: String, note: String = "", icon: Bitmap? = null, iconSlot: Boolean = false) {
        this.label = label
        this.note = note
        this.icon = icon
        this.iconSlot = iconSlot
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthSpec), Term.dp(54f).toInt())
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pressed = isPressed
        val pad = Term.dp(14f)
        val cy = height / 2f
        if (pressed) canvas.drawRect(0f, Term.dp(3f), width.toFloat(), height - Term.dp(3f), fill)

        var x = pad
        if (iconSlot) {
            val s = Term.dp(36f)
            icon?.let {
                iconRect.set(x, cy - s / 2, x + s, cy + s / 2)
                canvas.drawBitmap(it, null, iconRect, iconPaint)
            }
            x += s + Term.dp(14f)
        }

        var right = width - pad
        if (note.isNotEmpty()) {
            notePaint.color = if (pressed) Term.bg else Term.shade(0.6f)
            val nw = notePaint.measureText(note)
            canvas.drawText(note, right - nw, cy - (notePaint.descent() + notePaint.ascent()) / 2, notePaint)
            right -= nw + Term.dp(12f)
        }

        if (pressed) {
            labelPaint.color = Term.bg
            labelPaint.clearShadowLayer()
        } else {
            labelPaint.color = Term.fg
            labelPaint.setShadowLayer(Term.dp(5f), 0f, 0f, Term.glow())
        }
        val shown = TextUtils.ellipsize(label, labelPaint, right - x, TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, x, cy - (labelPaint.descent() + labelPaint.ascent()) / 2, labelPaint)
    }
}

/** The row of screen names across the top; the active one sits in a notch on the rule. */
class TabBar(ctx: Context, private val names: Array<String>) : View(ctx) {
    var selected = 0
        set(v) {
            field = v
            invalidate()
        }
    var onSelect: ((Int) -> Unit)? = null

    private val text = Term.paint(27f)
    private val line = Paint().apply {
        color = Term.shade(0.7f)
        strokeWidth = Term.dp(1.5f)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthSpec), Term.dp(58f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cell = width / names.size.toFloat()
        val rule = height - Term.dp(8f)
        val top = Term.dp(10f)
        val baseline = (top + rule) / 2 - (text.descent() + text.ascent()) / 2
        for (i in names.indices) {
            val on = i == selected
            val tw = text.measureText(names[i])
            val cx = cell * i + cell / 2
            if (on) {
                text.color = Term.fg
                text.setShadowLayer(Term.dp(6f), 0f, 0f, Term.glow())
            } else {
                text.color = Term.shade(0.5f)
                text.clearShadowLayer()
            }
            canvas.drawText(names[i], cx - tw / 2, baseline, text)
            if (on) {
                val l = cx - tw / 2 - Term.dp(10f)
                val r = cx + tw / 2 + Term.dp(10f)
                val tick = Term.dp(12f)
                canvas.drawLine(0f, rule, l, rule, line)
                canvas.drawLine(r, rule, width.toFloat(), rule, line)
                canvas.drawLine(l, rule, l, rule - tick, line)
                canvas.drawLine(r, rule, r, rule - tick, line)
                canvas.drawLine(l, top, l, top + tick, line)
                canvas.drawLine(r, top, r, top + tick, line)
                canvas.drawLine(l, top, l + tick, top, line)
                canvas.drawLine(r, top, r - tick, top, line)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val i = (e.x / (width / names.size.toFloat())).toInt().coerceIn(0, names.size - 1)
            onSelect?.invoke(i)
        }
        return true
    }
}

/** Big clock, date, and a battery meter for the home screen. */
class ClockView(ctx: Context) : View(ctx) {
    var battery = -1
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    var charging = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val time = Term.paint(100f)
    private val small = Term.paint(24f)
    private val dim = Term.paint(24f, Term.shade(0.6f), glow = false)
    private val bar = Paint()
    private val dateFmt = SimpleDateFormat("EEE dd MMM yyyy", Locale.getDefault())

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = getDefaultSize(suggestedMinimumWidth, widthSpec)
        // Size the digits to the screen: "00:00" spans about two thirds of the width.
        time.textSize = 100f
        val per = time.measureText("00:00") / 100f
        time.textSize = min(w * 0.68f / per, Term.dp(170f))
        val h = Term.dp(14f) + time.textSize * 0.82f + Term.dp(10f) + small.textSize + Term.dp(16f) +
            small.textSize + Term.dp(14f)
        setMeasuredDimension(w, h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val now = Date()
        val is24 = DateFormat.is24HourFormat(context)
        val clock = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.US).format(now)
        val suffix = if (is24) "" else SimpleDateFormat("a", Locale.US).format(now)
        val cx = width / 2f

        var y = Term.dp(14f) + time.textSize * 0.82f
        val tw = time.measureText(clock)
        canvas.drawText(clock, cx - tw / 2, y, time)
        if (suffix.isNotEmpty()) canvas.drawText(suffix, cx + tw / 2 + Term.dp(6f), y, small)

        y += Term.dp(10f) + small.textSize
        val date = dateFmt.format(now).uppercase()
        canvas.drawText(date, cx - small.measureText(date) / 2, y, small)

        y += Term.dp(16f) + small.textSize
        val pad = Term.dp(18f)
        canvas.drawText("PWR", pad, y, dim)
        val pct = if (battery < 0) "--%" else "$battery%" + if (charging) " CHG" else ""
        val pw = small.measureText(pct)
        canvas.drawText(pct, width - pad - pw, y, small)
        val l = pad + dim.measureText("PWR") + Term.dp(12f)
        val r = width - pad - pw - Term.dp(12f)
        val bh = small.textSize * 0.55f
        Term.drawSegments(canvas, bar, l, y - bh, r - l, bh, battery / 100f, 24)
    }
}

/** A labelled level meter for the status screen. */
class GaugeView(ctx: Context, private val label: String) : View(ctx) {
    private var value = ""
    private var frac = 0f
    private val name = Term.paint(24f, Term.shade(0.6f), glow = false)
    private val text = Term.paint(24f)
    private val bar = Paint()

    fun set(frac: Float, value: String) {
        this.frac = frac
        this.value = value
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthSpec), Term.dp(64f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val pad = Term.dp(18f)
        val y = Term.dp(8f) + text.textSize * 0.8f
        canvas.drawText(label, pad, y, name)
        canvas.drawText(value, width - pad - text.measureText(value), y, text)
        Term.drawSegments(canvas, bar, pad, y + Term.dp(10f), width - 2 * pad, Term.dp(12f), frac, 32)
    }
}

/** A decorative oscilloscope trace; only animates while [running]. */
class ScopeView(ctx: Context) : View(ctx) {
    var running = false
        set(v) {
            field = v
            if (v) invalidate()
        }

    private val grid = Paint().apply {
        color = Term.shade(0.22f)
        strokeWidth = Term.dp(1f)
    }
    private val trace = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Term.fg
        style = Paint.Style.STROKE
        strokeWidth = Term.dp(2f)
        setShadowLayer(Term.dp(5f), 0f, 0f, Term.glow())
    }
    private val path = Path()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthSpec), Term.dp(130f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val pad = Term.dp(18f)
        val l = pad
        val r = width - pad
        val t = Term.dp(10f)
        val b = height - Term.dp(10f)
        for (i in 0..8) {
            val x = l + (r - l) * i / 8f
            canvas.drawLine(x, t, x, b, grid)
        }
        for (i in 0..4) {
            val y = t + (b - t) * i / 4f
            canvas.drawLine(l, y, r, y, grid)
        }

        val ph = SystemClock.uptimeMillis() / 1000f
        val mid = (t + b) / 2
        val amp = (b - t) * 0.36f
        path.rewind()
        val n = 96
        for (i in 0..n) {
            val k = i / n.toFloat()
            val v = sin(k * 9f + ph * 2.1f) * 0.6f + sin(k * 23f - ph * 3.3f) * 0.25f +
                sin(k * 51f + ph * 5.7f) * 0.12f
            val x = l + (r - l) * k
            val y = mid - v * amp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, trace)
        if (running) postInvalidateDelayed(33)
    }
}
