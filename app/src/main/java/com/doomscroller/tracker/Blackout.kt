package com.doomscroller.tracker

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager

/** Full-screen black overlay that swallows touches. Drawn as an accessibility overlay. */
class Blackout(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun show() {
        if (view != null) return
        val v = View(svc).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            isClickable = true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        ).apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        runCatching { wm.addView(v, lp) }.onSuccess {
            view = v
            v.animate().alpha(1f).setDuration(180).start()
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        v.animate().alpha(0f).setDuration(250).withEndAction { runCatching { wm.removeView(v) } }.start()
    }
}
