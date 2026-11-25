package com.doomscroller.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("doomscroller", Context.MODE_PRIVATE)

    /** 0 = adaptive budget (recommended); otherwise a fixed daily budget in minutes. */
    var manualBudgetMin: Int
        get() = sp.getInt("manual_budget", 0)
        set(v) { sp.edit { putInt("manual_budget", v) }; bump() }

    /** 21, 22, 23, or 0 (midnight). Reels between this and 5 AM break the night win. */
    var bedtimeHour: Int
        get() = sp.getInt("bedtime", 23)
        set(v) { sp.edit { putInt("bedtime", v) }; bump() }

    /** First moment tracking ran; anchors the 7-day calibration. */
    var trackingStart: Long
        get() = sp.getLong("tracking_start", 0)
        set(v) = sp.edit { putLong("tracking_start", v) }

    var seenBadges: Set<String>
        get() = sp.getStringSet("seen_badges", emptySet()).orEmpty()
        set(v) { sp.edit { putStringSet("seen_badges", v) }; bump() }

    /** Outside the bedtime-kick window: go Home after [SESSION_LIMIT_MIN] straight minutes of scrolling. */
    var sessionLimit: Boolean
        get() = sp.getBoolean("session_limit", true)
        set(v) { sp.edit { putBoolean("session_limit", v) }; bump() }

    /** 12–5 AM: end scroll sessions after a random 5–7 minutes and open the Clock app. */
    var bedtimeKick: Boolean
        get() = sp.getBoolean("bedtime_kick", true)
        set(v) { sp.edit { putBoolean("bedtime_kick", v) }; bump() }

    var bubbleEnabled: Boolean
        get() = sp.getBoolean("bubble", true)
        set(v) { sp.edit { putBoolean("bubble", v) }; bump() }

    var bubbleX: Int
        get() = sp.getInt("bx", -1)
        set(v) = sp.edit { putInt("bx", v) }

    var bubbleY: Int
        get() = sp.getInt("by", -1)
        set(v) = sp.edit { putInt("by", v) }

    fun resetBubblePosition() {
        sp.edit { remove("bx"); remove("by") }; bump()
    }

    fun resetProgress() {
        sp.edit { remove("tracking_start"); remove("seen_badges") }; bump()
    }

    companion object {
        const val SESSION_LIMIT_MIN = 8
        private val _version = MutableStateFlow(0)
        /** Bumps when a setting changes so the running service and UI can react. */
        val version: StateFlow<Int> = _version
        private fun bump() { _version.value++ }
    }
}
