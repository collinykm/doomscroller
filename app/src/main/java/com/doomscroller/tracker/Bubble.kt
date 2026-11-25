package com.doomscroller.tracker

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.doomscroller.data.Prefs
import kotlin.math.abs

/**
 * Draggable counter drawn as an accessibility overlay (no "draw over other apps" permission needed).
 * A ring around it shows how much of today's budget is used. Tap to toggle the detail view.
 * [flash] briefly expands it with a message: awareness nudges, never blocking.
 */
class Bubble(private val svc: AccessibilityService, private val prefs: Prefs) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val dp = svc.resources.displayMetrics.density
    private var view: RingText? = null
    private var expanded = false
    private var flashText: String? = null
    private var lastCount = -1
    private var count = 0
    private var ms = 0L
    private var budgetMs: Long? = null
    private var otherMs = 0L
    private var surface = "reels"
    private val clearFlash = Runnable { flashText = null; milestoneUntil = 0; render() }
    /** Where the user put the bubble; the window shifts left of this when expanded text wouldn't fit. */
    private var anchorX = 0
    /** While a milestone is showing: no ordinary pulses, and a hot-pink outline. */
    private var milestoneUntil = 0L

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    val isShowing get() = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return
        val metrics = svc.resources.displayMetrics
        anchorX = prefs.bubbleX.takeIf { it >= 0 } ?: (metrics.widthPixels - (64 * dp).toInt())
        params.x = anchorX
        params.y = prefs.bubbleY.takeIf { it >= 0 } ?: (metrics.heightPixels * 0.18f).toInt()
        val tv = RingText(svc, dp).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = (48 * dp).toInt()
            minHeight = (48 * dp).toInt()
            elevation = 8 * dp
            setOnTouchListener(DragListener())
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> fitOnScreen(v) }
        }
        view = tv
        render()
        runCatching { wm.addView(tv, params) }.onFailure { view = null }
    }

    private fun fitOnScreen(v: View) {
        val screenW = svc.resources.displayMetrics.widthPixels
        val x = anchorX.coerceAtMost(screenW - v.width - (6 * dp).toInt()).coerceAtLeast(0)
        if (x != params.x) {
            params.x = x
            runCatching { wm.updateViewLayout(v, params) }
        }
    }

    fun hide() {
        view?.let {
            it.removeCallbacks(clearFlash)
            runCatching { wm.removeView(it) }
        }
        view = null
        flashText = null
    }

    /** [otherMs] = Home feed + Explore time today; on those screens the bubble shows it instead of the reel count. */
    fun update(count: Int, ms: Long, budgetMs: Long?, otherMs: Long = 0, surface: String = "reels") {
        val bumped = lastCount in 0 until count
        this.count = count; this.ms = ms; this.budgetMs = budgetMs; this.otherMs = otherMs; this.surface = surface
        lastCount = count
        render()
        if (bumped && android.os.SystemClock.uptimeMillis() > milestoneUntil) pulse(1.2f)
    }

    /** Every 10 reels: expand with [text], bounce three times and glow, so it's hard to miss. */
    fun milestone(text: String) {
        val v = view ?: return
        milestoneUntil = android.os.SystemClock.uptimeMillis() + 3500
        flashText = text
        render()
        v.removeCallbacks(clearFlash)
        v.postDelayed(clearFlash, 3500)
        bounce(3)
    }

    private fun bounce(times: Int) {
        if (times <= 0) return
        view?.animate()?.scaleX(1.35f)?.scaleY(1.35f)?.setDuration(140)?.withEndAction {
            view?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(180)?.withEndAction { bounce(times - 1) }?.start()
        }?.start()
    }

    /** Expand for a few seconds with [text]. */
    fun flash(text: String, durationMs: Long = 4500) {
        val v = view ?: return
        flashText = text
        render()
        pulse(1.08f)
        v.removeCallbacks(clearFlash)
        v.postDelayed(clearFlash, durationMs)
    }

    private fun pulse(scale: Float) {
        view?.animate()?.scaleX(scale)?.scaleY(scale)?.setDuration(110)?.withEndAction {
            view?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(160)?.start()
        }?.start()
    }

    private fun render() {
        val tv = view ?: return
        val frac = budgetMs?.takeIf { it > 0 }?.let { ms.toFloat() / it }
        val hot = android.os.SystemClock.uptimeMillis() < milestoneUntil
        val accent = when {
            hot -> 0xFFFF3D7F.toInt()
            surface == "feed" -> 0xFF9B7BFF.toInt()   // violet: Home feed
            surface == "explore" -> 0xFFFFB020.toInt() // amber: Explore
            else -> colorFor(frac)
        }
        val bg = GradientDrawable().apply { setColor(0xE61A1520.toInt()) }
        val flash = flashText
        when {
            flash != null || expanded -> {
                bg.cornerRadius = 24 * dp
                bg.setStroke(((if (hot) 3 else 2) * dp).toInt(), accent)
                if (hot) bg.setColor(0xF2401030.toInt())
                tv.ringFraction = null
                tv.text = flash ?: buildString {
                    append("$count reels · ${fmtDur(ms)}")
                    budgetMs?.let { append(" of ${fmtDur(it)}") }
                    if (otherMs >= 1000) append(" · feed ${fmtDur(otherMs)}")
                }
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                tv.setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            }
            else -> {
                bg.shape = GradientDrawable.OVAL
                if (frac == null) bg.setStroke((2 * dp).toInt(), accent)
                tv.ringFraction = frac.takeIf { surface == "reels" }
                if (surface != "reels") bg.setStroke((2 * dp).toInt(), accent)
                tv.ringColor = accent
                val label = if (surface == "reels") count.toString() else compactDur(otherMs)
                tv.text = label
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, when { label.length >= 4 -> 13f; label.length == 3 -> 15f; else -> 18f })
                tv.setPadding(0, 0, 0, 0)
            }
        }
        tv.background = bg
        tv.invalidate()
    }

    /** Teal while comfortably under budget, amber near it, soft coral past it. Never alarm-red. */
    private fun colorFor(frac: Float?): Int = when {
        frac == null -> 0xFF2EC4B6.toInt()
        frac < 0.75f -> 0xFF2EC4B6.toInt()
        frac < 1f -> 0xFFFFB020.toInt()
        else -> 0xFFFF7A6B.toInt()
    }

    private inner class DragListener : View.OnTouchListener {
        private val slop = ViewConfiguration.get(svc).scaledTouchSlop
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).toInt().coerceAtLeast(0)
                        params.y = (startY + dy).toInt().coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        anchorX = params.x
                        prefs.bubbleX = params.x; prefs.bubbleY = params.y
                    } else {
                        if (flashText != null) flashText = null else expanded = !expanded
                        render()
                    }
                }
            }
            return true
        }
    }
}

/** TextView that draws a progress ring around itself. */
private class RingText(ctx: Context, private val dp: Float) : TextView(ctx) {
    var ringFraction: Float? = null
    var ringColor: Int = Color.WHITE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = ringFraction ?: return
        val sw = 3.5f * dp
        val inset = sw / 2 + 1 * dp
        rect.set(inset, inset, width - inset, height - inset)
        paint.strokeWidth = sw
        paint.color = 0x33FFFFFF
        canvas.drawArc(rect, 0f, 360f, false, paint)
        paint.color = ringColor
        canvas.drawArc(rect, -90f, 360f * f.coerceIn(0f, 1f), false, paint)
    }
}

/** Fits inside the round bubble: 45s, 12m, 1h05. */
fun compactDur(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        else -> "${s / 3600}h%02d".format((s % 3600) / 60)
    }
}

fun fmtDur(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        totalMin > 0 -> "${m}m"
        else -> "${ms / 1000}s"
    }
}
