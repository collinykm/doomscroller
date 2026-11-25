package com.doomscroller.tracker

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.AlarmClock
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.doomscroller.data.Db
import com.doomscroller.data.Prefs
import com.doomscroller.data.ProgressEngine
import com.doomscroller.data.ReelRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

data class TrackerStatus(
    val connected: Boolean = false,
    val igForeground: Boolean = false,
    val inReels: Boolean = false,
    val surface: String = "-",
    val via: String = "-",
    val signature: String = "",
    val indexMode: Boolean = false,
    val index: Int = -1,
    val todayCount: Int = 0,
    val nodeCount: Int = 0,
    val ids: List<String> = emptyList(),
    val updatedAt: Long = 0,
)

/**
 * Watches Instagram through the accessibility API.
 *
 * A "session" is one continuous stretch inside the Reels viewer. Leaving Reels pauses the session
 * (and the current reel) for [GRACE_MS]; coming back inside that window resumes it, so peeking at
 * a profile or DMs and returning does not count as a new session or a new reel.
 *
 * A new reel is detected one of two ways:
 *  - index mode: the pager's scroll events report item positions; a settled position change = new reel.
 *  - signature mode (fallback): the author+caption of the on-screen reel changes and stays changed.
 */
class ReelsService : AccessibilityService() {

    companion object {
        const val IG = "com.instagram.android"
        private const val TAG = "Doomscroller"
        private const val KICK_END_HOUR = 5

        @Volatile private var instance: ReelsService? = null

        /** Settings "Test it" button: run the bedtime kick in 3 seconds, ignoring the clock. */
        fun testKick(): Boolean {
            val svc = instance ?: return false
            svc.main.postDelayed({ svc.kick() }, 3000)
            return true
        }

        /** Settings "Test it" for the 8-minute limit: go Home in 3 seconds. */
        fun testLimit(): Boolean {
            val svc = instance ?: return false
            svc.main.postDelayed({ svc.limitKick() }, 3000)
            return true
        }
        private const val GRACE_MS = 30_000L
        private const val OPEN_DEBOUNCE_MS = 10_000L
        private const val MAX_REEL_MS = 10 * 60_000L
        private const val SCROLL_SETTLE_MS = 250L

        private val _status = MutableStateFlow(TrackerStatus())
        val status: StateFlow<TrackerStatus> = _status
    }

    private class ReelState(val start: Long, var signature: String, var index: Int, var author: String?) {
        var commented = false
        var liked = false
        var shared = false
    }

    private class SessionState(val start: Long) {
        var reels = 0
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var db: Db
    private lateinit var prefs: Prefs
    private lateinit var bubble: Bubble
    private lateinit var blackout: Blackout

    // Scrolling across Reels, Home feed and Explore. One "scroll session" survives short trips
    // elsewhere (DMs, a profile) for up to GRACE_MS; the 8-minute limit and bedtime kick time it.
    private var surface: String? = null
    private var surfaceSince = 0L
    private var scrollStart = 0L
    private var scrollAwayAt = 0L
    private var limitWarned = false

    // Bedtime kick: threshold for the current scroll session, and how many kicks happened tonight.
    private var kickThresholdMs = 0L
    private var kickNight: LocalDate? = null
    private var kicksTonight = 0

    // Foreground / reels state
    private var igForeground = false
    private var igLeftAt = 0L
    private var inReels = false
    private var reelMisses = 0
    private var pausedAt = 0L
    private var resumePending = false
    private var session: SessionState? = null
    private var reel: ReelState? = null

    // Reel-change detection
    private var lastScrollTs = 0L
    private var pendingIndex = -1
    private val seenIndices = HashSet<Int>()
    private var indexMode = false
    private var candidateSig: String? = null
    private var candidateSince = 0L
    private var lastScan: Scan? = null

    // Today counters (bubble)
    private var dayStart = 0L
    private var todayCount = 0
    private var todayMs = 0L
    /** Saved Home feed + Explore time today (the live segment is added on top). */
    private var todayOtherMs = 0L
    private var budgetMin: Int? = null

    // Nudges: each fires at most once per session / day / night.
    private var nudgedSession = false
    private var nudgedBudgetDay = 0L
    private var nudgedNight: LocalDate? = null

    private var checkPending = false
    private val checkRunnable = Runnable { checkPending = false; check() }
    private val graceRunnable = Runnable { finalizeAll(pausedAt.takeIf { it > 0 } ?: now()) }
    private val ticker = object : Runnable {
        override fun run() {
            if (!igForeground) return
            scheduleCheck(0)
            val t = now()
            // Save long stretches every minute so the app's numbers stay current.
            if (surface != null && t - surfaceSince >= 60_000L) saveSegment(t)
            updateBubble()
            enforceLimits(t)
            main.postDelayed(this, 1500)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
        }
    }

    private fun now() = System.currentTimeMillis()
    private val screenH get() = resources.displayMetrics.heightPixels

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        db = Db.get(this)
        prefs = Prefs(this)
        bubble = Bubble(this, prefs)
        blackout = Blackout(this)
        instance = this
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        if (prefs.trackingStart == 0L) prefs.trackingStart = now()
        dayStart = startOfToday()
        loadToday()
        scope.launch {
            Prefs.version.collect {
                if (!::bubble.isInitialized) return@collect
                bubble.hide()
                syncBubbleVisibility()
                refreshBudget()
            }
        }
        publish()
    }

    override fun onDestroy() {
        instance = null
        if (::blackout.isInitialized) blackout.hide()
        finalizeAll(now())
        if (::bubble.isInitialized) bubble.hide()
        runCatching { unregisterReceiver(screenReceiver) }
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        _status.value = TrackerStatus(connected = false)
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    private fun loadToday() {
        val ds = dayStart
        Thread {
            val (n, ms, _) = runCatching { db.todayTotals(ds) }.getOrDefault(Triple(0, 0L, 0))
            val other = runCatching { db.surfacesSince(ds).filter { it.surface != "reels" }.sumOf { it.durationMs } }.getOrDefault(0L)
            main.post {
                if (dayStart == ds) {
                    todayCount += n
                    todayMs += ms
                    todayOtherMs += other
                    updateBubble()
                    publish()
                }
            }
        }.start()
        refreshBudget()
    }

    /** Today's budget comes from replaying history through the progress engine. */
    private fun refreshBudget() {
        Thread {
            val b = runCatching { ProgressEngine.load(db, prefs).today.budgetMin }.getOrNull()
            main.post { budgetMin = b; updateBubble() }
        }.start()
    }

    // ---------------------------------------------------------------- events

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (igForeground) scheduleCheck(200)
            return
        }
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg != IG) {
            if (igForeground && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) scheduleCheck(150)
            return
        }
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> { onScroll(e); scheduleCheck(SCROLL_SETTLE_MS + 50) }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> { onClick(e); scheduleCheck(400) }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scheduleCheck(150)
            else -> scheduleCheck(450)
        }
    }

    /** Coalesces bursts of events into one tree scan. */
    private fun scheduleCheck(delay: Long) {
        if (checkPending) return
        checkPending = true
        main.postDelayed(checkRunnable, delay)
    }

    private fun onScroll(e: AccessibilityEvent) {
        if (!inReels) return
        val src = e.source
        val id = src?.viewIdResourceName?.substringAfter(":id/") ?: ""
        Log.v(TAG, "scroll id=$id cls=${e.className} from=${e.fromIndex} to=${e.toIndex} y=${e.scrollY}/${e.maxScrollY}")
        if (id.contains("comment")) return
        val r = Rect()
        src?.getBoundsInScreen(r)
        val cls = e.className?.toString() ?: ""
        val pagerLike = id.contains("clips") || id.contains("pager") ||
            ((cls.contains("ViewPager") || cls.contains("RecyclerView")) && r.height() >= screenH * 0.7f)
        if (!pagerLike) return

        lastScrollTs = now()
        var idx = e.fromIndex
        if (idx < 0 && e.maxScrollY > 0 && r.height() > 0) idx = (e.scrollY.toFloat() / r.height()).roundToInt()
        if (idx >= 0) {
            pendingIndex = idx
            seenIndices += idx
            if (seenIndices.size >= 2) indexMode = true
        }
    }

    private fun onClick(e: AccessibilityEvent) {
        if (!inReels) return
        val r = reel ?: return
        val label = (e.contentDescription?.toString() ?: e.text.joinToString(" ")).trim().lowercase()
        val id = e.source?.viewIdResourceName?.substringAfter(":id/")?.lowercase() ?: ""
        when {
            label == "comment" || label.startsWith("comment") || id.contains("comment_button") -> r.commented = true
            label == "unlike" -> r.liked = false
            label == "like" || id.contains("like_button") -> r.liked = true
            label.startsWith("share") || label == "send" || label.startsWith("send post") ||
                id.contains("share_button") || id.contains("direct_share") -> r.shared = true
        }
    }

    private fun onScreenOff() {
        val t = now()
        inReels = false
        setSurface(null, t)
        main.removeCallbacks(graceRunnable)
        finalizeAll(pausedAt.takeIf { it > 0 } ?: t)
        if (igForeground) {
            igForeground = false
            igLeftAt = t
        }
        publish()
    }

    // ---------------------------------------------------------------- main check

    private fun topAppRoot(): AccessibilityNodeInfo? {
        val ws = runCatching { windows }.getOrNull().orEmpty()
        val apps = ws.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val top = apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused } ?: apps.firstOrNull()
        return top?.root ?: rootInActiveWindow
    }

    private fun check() {
        val t = now()
        rollDayIfNeeded(t)
        val root = topAppRoot() ?: return
        if (root.packageName?.toString() != IG) {
            if (igForeground) onIgLeft(t)
            publish()
            return
        }
        if (!igForeground) onIgOpened(t)

        val scan = IgScanner.scan(root, screenH)
        lastScan = scan

        if (!scan.inReels) {
            if (inReels) {
                Log.d(TAG, "reels not found; ids=${scan.ids.take(30)}")
                // Require two misses in a row; the tree is briefly empty while reels load.
                reelMisses++
                if (reelMisses >= 2) leaveReels(t) else scheduleCheck(400)
            }
            if (!inReels) setSurface(scan.surface, t)
            enforceLimits(t)
            publish()
            return
        }
        reelMisses = 0
        if (!inReels) {
            Log.d(TAG, "enter reels via=${scan.via} ids=${scan.ids}")
            enterReels(t)
        }
        setSurface("reels", t)

        if (t - lastScrollTs < SCROLL_SETTLE_MS) {
            scheduleCheck(SCROLL_SETTLE_MS)
        } else if (!scan.commentsOpen) {
            detectReel(scan, t)
        }
        reel?.let {
            if (scan.commentsOpen) it.commented = true
            if (scan.likedNow) it.liked = true
            if (it.author == null) it.author = scan.author
        }
        updateBubble()
        enforceLimits(t)
        publish()
    }

    private fun detectReel(scan: Scan, t: Long) {
        val cur = reel
        if (cur == null) {
            startReel(scan, t)
            return
        }
        if (resumePending) {
            resumePending = false
            if (scan.signature.isBlank() || sameReel(scan.signature, cur.signature)) {
                if (cur.signature.isBlank()) cur.signature = scan.signature
                return // came back to the same reel
            }
            finishReel(pausedAt.takeIf { it > 0 } ?: t)
            startReel(scan, t)
            return
        }

        if (indexMode) {
            if (pendingIndex < 0) return
            if (cur.index < 0) {
                // First index we've seen for this reel: only count it if the content changed too.
                if (scan.signature.isBlank() || sameReel(scan.signature, cur.signature)) {
                    cur.index = pendingIndex
                    if (cur.signature.isBlank()) cur.signature = scan.signature
                    return
                }
            } else if (pendingIndex == cur.index) {
                if (cur.signature.isBlank()) cur.signature = scan.signature
                return
            }
            finishReel(t)
            startReel(scan, t)
            return
        }

        // Signature mode: a change must be seen on two consecutive checks to count.
        if (scan.signature.isBlank()) return
        if (cur.signature.isBlank()) { cur.signature = scan.signature; return }
        if (sameReel(scan.signature, cur.signature)) { candidateSig = null; return }
        if (candidateSig == scan.signature) {
            val startedAt = candidateSince
            candidateSig = null
            finishReel(startedAt)
            startReel(scan, startedAt)
        } else {
            candidateSig = scan.signature
            candidateSince = t
            scheduleCheck(350)
        }
    }

    /** Treat signatures as the same reel when one is a partially-loaded version of the other. */
    private fun sameReel(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isBlank() || b.isBlank()) return true
        if (a.startsWith(b) || b.startsWith(a)) return true
        val aa = a.substringBefore('|'); val bb = b.substringBefore('|')
        return aa.isNotEmpty() && aa == bb && (a.substringAfter('|', "").isEmpty() || b.substringAfter('|', "").isEmpty())
    }

    // ---------------------------------------------------------------- state transitions

    private fun onIgOpened(t: Long) {
        igForeground = true
        if (t - igLeftAt > OPEN_DEBOUNCE_MS) db.write { insertOpen(t) }
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 1500)
    }

    private fun onIgLeft(t: Long) {
        igForeground = false
        igLeftAt = t
        main.removeCallbacks(ticker)
        if (inReels) leaveReels(t)
        setSurface(null, t)
    }

    private fun enterReels(t: Long) {
        inReels = true
        main.removeCallbacks(graceRunnable)
        seenIndices.clear()
        indexMode = false
        pendingIndex = -1
        candidateSig = null
        if (reel != null && pausedAt > 0 && t - pausedAt < GRACE_MS) {
            resumePending = true
            reel?.index = -1
        } else {
            finalizeAll(pausedAt.takeIf { it > 0 } ?: t)
        }
        pausedAt = 0
    }

    private fun leaveReels(t: Long) {
        inReels = false
        reelMisses = 0
        pausedAt = t
        main.removeCallbacks(graceRunnable)
        main.postDelayed(graceRunnable, GRACE_MS)
    }

    private fun startReel(scan: Scan, t: Long) {
        val s = session ?: SessionState(t).also { session = it; nudgedSession = false }
        s.reels++
        reel = ReelState(t, scan.signature, if (indexMode) pendingIndex else -1, scan.author)
        if (t >= dayStart) {
            todayCount++
            // Every 10th reel: make the bubble hard to ignore for a moment.
            if (todayCount % 10 == 0) bubble.milestone("$todayCount reels today")
        }
        Log.d(TAG, "reel #$todayCount via=${scan.via} mode=${if (indexMode) "index:$pendingIndex" else "sig"} author=${scan.author} sig=${scan.signature}")
    }

    private fun finishReel(end: Long) {
        val r = reel ?: return
        reel = null
        val s = session
        val endTs = end.coerceIn(r.start, r.start + MAX_REEL_MS)
        val row = ReelRow(r.start, endTs, s?.start ?: r.start, r.commented, r.liked, r.shared, r.author)
        db.write { insertReel(row) }
        if (r.start >= dayStart) todayMs += endTs - r.start
    }

    private fun finishSession(end: Long) {
        val s = session ?: return
        session = null
        if (s.reels == 0) return
        db.write { upsertSession(0, s.start, end.coerceAtLeast(s.start), s.reels) }
    }

    private fun finalizeAll(end: Long) {
        finishReel(end)
        finishSession(end)
        resumePending = false
        pausedAt = 0
    }

    // ---------------------------------------------------------------- day + bubble

    /** Records time per surface and keeps the scroll session (and the bubble) in sync. */
    private fun setSurface(s: String?, t: Long) {
        if (s == surface) return
        saveSegment(t)
        Log.d(TAG, "surface ${surface ?: "-"} -> ${s ?: "-"}")
        surface = s
        surfaceSince = t
        if (s != null) {
            if (scrollStart == 0L || (scrollAwayAt > 0 && t - scrollAwayAt > GRACE_MS)) {
                scrollStart = t
                limitWarned = false
                kickThresholdMs = 0
            }
            scrollAwayAt = 0
        } else if (scrollStart > 0) {
            scrollAwayAt = t
        }
        syncBubbleVisibility()
    }

    /** Writes the open surface segment up to [t] and starts a new one there. */
    private fun saveSegment(t: Long) {
        val prev = surface ?: return
        val from = surfaceSince
        if (t <= from) return
        db.write { insertSurface(from, t, prev) }
        if (prev != "reels" && from >= dayStart) todayOtherMs += t - from
        surfaceSince = t
    }

    /** 12–5 AM: the bedtime kick. Otherwise: the 8-minute limit. */
    private fun enforceLimits(t: Long) {
        if (surface == null || scrollStart == 0L) return
        val hour = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).hour
        if (hour < KICK_END_HOUR && prefs.bedtimeKick) { maybeKick(t); return }
        if (!prefs.sessionLimit) return
        val limitMs = Prefs.SESSION_LIMIT_MIN * 60_000L
        val elapsed = t - scrollStart
        if (!limitWarned && elapsed >= limitMs - 60_000L) {
            limitWarned = true
            bubble.flash("1 min left · closing at ${Prefs.SESSION_LIMIT_MIN}")
        }
        if (elapsed >= limitMs) limitKick()
    }

    /**
     * Between midnight and 5 AM, a scroll session that reaches a random 5–7 minutes gets ended:
     * Home, two seconds of black, then the Clock app. Each repeat kick the same night comes twice
     * as fast (never under 2 minutes) so reopening Instagram doesn't reset the clock.
     */
    private fun maybeKick(t: Long) {
        val z = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())
        val night = z.toLocalDate()
        if (kickNight != night) { kickNight = night; kicksTonight = 0 }
        if (kickThresholdMs == 0L) {
            val base = kotlin.random.Random.nextLong(5 * 60_000L, 7 * 60_000L + 1)
            kickThresholdMs = (base shr kicksTonight.coerceAtMost(4)).coerceAtLeast(2 * 60_000L)
            Log.d(TAG, "bedtime kick armed: ${kickThresholdMs / 1000}s (kick #${kicksTonight + 1} tonight)")
        }
        val counted = t - maxOf(scrollStart, dayStart) // only time after midnight counts
        if (counted >= kickThresholdMs) kick()
    }

    /** Ends the current scroll session and leaves Instagram for the home screen. */
    private fun endScrolling(t: Long) {
        inReels = false
        main.removeCallbacks(graceRunnable)
        finalizeAll(t)
        setSurface(null, t)
        scrollStart = 0
        scrollAwayAt = 0
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun limitKick() {
        Log.d(TAG, "session limit kick")
        endScrolling(now())
        android.widget.Toast.makeText(
            this, "${Prefs.SESSION_LIMIT_MIN} minutes of scrolling. That's a session.", android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    private fun kick() {
        Log.d(TAG, "bedtime kick")
        kicksTonight++
        kickThresholdMs = 0
        endScrolling(now())
        blackout.show()
        main.postDelayed({
            openClock()
            main.postDelayed({ blackout.hide() }, 500)
        }, 2000)
    }

    private fun openClock() {
        val intents = listOf(
            Intent(AlarmClock.ACTION_SHOW_ALARMS),
            Intent(AlarmClock.ACTION_SET_ALARM),
            packageManager.getLaunchIntentForPackage("com.google.android.deskclock"),
        )
        for (i in intents.filterNotNull()) {
            val ok = runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return
        }
    }

    private fun startOfToday(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun rollDayIfNeeded(t: Long) {
        val ds = startOfToday()
        if (ds != dayStart && t >= ds) {
            dayStart = ds
            todayCount = 0
            todayMs = 0
            todayOtherMs = 0
            refreshBudget()
        }
    }

    private fun syncBubbleVisibility() {
        if (surface != null && prefs.bubbleEnabled) {
            bubble.show()
            updateBubble()
        } else bubble.hide()
    }

    private fun updateBubble() {
        if (!bubble.isShowing) return
        val t = now()
        val live = reel?.takeIf { inReels }?.let { (t - it.start).coerceIn(0, MAX_REEL_MS) } ?: 0L
        val total = todayMs + live
        val budgetMs = budgetMin?.let { it * 60_000L }
        val otherLive = if (surface == "feed" || surface == "explore") (t - surfaceSince).coerceAtLeast(0) else 0L
        bubble.update(todayCount, total, budgetMs, todayOtherMs + otherLive, surface ?: "reels")
        nudge(t, total, budgetMs)
    }

    /**
     * Just-in-time awareness prompts at the moments that matter. They never block anything;
     * the point is to interrupt autopilot, not to scold.
     */
    private fun nudge(t: Long, total: Long, budgetMs: Long?) {
        if (!inReels) return
        val sessionMs = session?.let { t - it.start } ?: 0L
        val night = ProgressEngine.nightOf(t, ZoneId.systemDefault(), prefs.bedtimeHour)
        when {
            budgetMs != null && total >= budgetMs && nudgedBudgetDay != dayStart -> {
                nudgedBudgetDay = dayStart
                bubble.flash("Budget reached · ${fmtDur(budgetMs)}")
            }
            night != null && nudgedNight != night -> {
                nudgedNight = night
                bubble.flash("Past bedtime · tonight's win is at stake")
            }
            !nudgedSession && sessionMs >= 15 * 60_000L -> {
                nudgedSession = true
                bubble.flash("15 min this session · binge at 20")
            }
        }
    }

    private fun publish() {
        val s = lastScan
        _status.value = TrackerStatus(
            connected = true,
            igForeground = igForeground,
            inReels = inReels,
            surface = surface ?: "-",
            via = s?.via ?: "-",
            signature = s?.signature ?: "",
            indexMode = indexMode,
            index = pendingIndex,
            todayCount = todayCount,
            nodeCount = s?.nodeCount ?: 0,
            ids = s?.ids.orEmpty(),
            updatedAt = now(),
        )
    }
}
