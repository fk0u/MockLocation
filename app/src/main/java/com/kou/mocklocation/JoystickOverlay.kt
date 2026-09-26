package com.kou.mocklocation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/** Floating joystick drawn over other apps (needs "display over other apps"). */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class JoystickOverlay(
    private val ctx: Context,
    kmh: Float,
    private val onSpeed: (Double) -> Unit,
    private val onStop: () -> Unit,
) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val d = ctx.resources.displayMetrics.density
    private var preset = SPEEDS.indices.minBy { abs(SPEEDS[it].kmh - kmh) }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = (16 * d).toInt(); y = (260 * d).toInt() }

    private fun pill(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius * d; setStroke((1 * d).toInt(), 0x22FFFFFF)
    }

    private fun label(text: String, bg: Int = 0x331B2330) = TextView(ctx).apply {
        this.text = text; setTextColor(0xFFE7EEF6.toInt()); textSize = 13f
        setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
        background = pill(bg, 14f)
        contentDescription = text
    }

    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        background = pill(0xE60E141C.toInt(), 28f)
        val pad = (10 * d).toInt(); setPadding(pad, pad, pad, pad)
    }

    init {
        val grip = label("⠿").apply { contentDescription = "Geser joystick" }
        val speed = label(speedText(), 0x335EEAD4)
        val close = label("✕", 0x33FF4D6D).apply { contentDescription = "Stop mock" }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    params.x = sx + (e.rawX - tx).toInt(); params.y = sy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(root, params)
                }
            }
            true
        }
        speed.setOnClickListener {
            preset = (preset + 1) % SPEEDS.size
            speed.text = speedText()
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onSpeed(SPEEDS[preset].kmh / 3.6)
        }
        close.setOnClickListener { onStop() }
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val gap = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (6 * d).toInt() }
        bar.addView(grip, gap); bar.addView(speed, gap); bar.addView(close)
        root.addView(bar)
        root.addView(Pad(ctx), LinearLayout.LayoutParams(-2, -2).apply { topMargin = (10 * d).toInt() })
        wm.addView(root, params)
    }

    private fun speedText() = "${SPEEDS[preset].label} · ${SPEEDS[preset].kmh.toInt()} km/j"

    fun remove() {
        MockState.joyX = 0f; MockState.joyY = 0f
        runCatching { wm.removeView(root) }
    }

    private class Pad(ctx: Context) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val size = (150 * d).toInt()
        private var kx = 0f; private var ky = 0f
        private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x661B2330 }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0x665EEAD4 }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x885EEAD4.toInt(); strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG)

        init { contentDescription = "Joystick arah" }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(size, size)

        override fun onDraw(c: Canvas) {
            val r = size / 2f; val max = r * 0.62f
            c.drawCircle(r, r, r - d, base); c.drawCircle(r, r, r - d, ring); c.drawCircle(r, r, max, ring)
            for ((dx, dy) in listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f))
                c.drawLine(r + dx * (r - 12 * d), r + dy * (r - 12 * d), r + dx * (r - 6 * d), r + dy * (r - 6 * d), tick)
            val x = r + kx * max; val y = r + ky * max
            if (kx != 0f || ky != 0f) c.drawLine(r, r, x, y, tick)
            knob.shader = RadialGradient(x - 8 * d, y - 8 * d, 34 * d, 0xFFA7F3E6.toInt(), 0xFF14B8A6.toInt(), Shader.TileMode.CLAMP)
            knob.setShadowLayer(10 * d, 0f, 3 * d, Color.argb(120, 0, 0, 0))
            c.drawCircle(x, y, 26 * d, knob)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val r = size / 2f; val max = r * 0.62f
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    var x = (e.x - r) / max; var y = (e.y - r) / max
                    val m = hypot(x, y); if (m > 1) { x /= m; y /= m }
                    kx = x; ky = y
                }
                else -> { kx = 0f; ky = 0f }
            }
            MockState.joyX = kx; MockState.joyY = ky
            invalidate()
            return true
        }
    }
}
