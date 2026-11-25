package com.doomscroller.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The "do less" game.
 *
 * Design notes (why it works the way it does):
 *  - Reduction is invisible, so it is converted into something that accumulates: minutes
 *    *reclaimed* versus your own baseline. Like steps, it only ever goes up.
 *  - Goals are relative to you, specific, and just-manageable: the budget starts ~10% under your
 *    calibration-week average and moves one notch per week (Locke & Latham goal-setting).
 *  - Budgets change only on Mondays, a natural "fresh start" (Dai, Milkman & Riis 2014).
 *  - If a week goes badly the budget eases instead of ratcheting down (protects self-efficacy).
 *  - Streaks use "never miss twice": one miss pauses the streak, two in a row resets it. This
 *    avoids the abstinence-violation / what-the-hell effect where one slip triggers a binge.
 *  - Three independent daily wins (budget, clean night, no binges) so a bad day can still have a win.
 */

enum class Win { WON, MISSED, PENDING, NONE }

data class DayRecord(
    val date: LocalDate,
    val minutes: Double,
    val reels: Int,
    val budgetMin: Int?,
    val budget: Win,
    val night: Win,
    val binge: Win,
    val reclaimedMin: Double,
    val longestSessionMin: Double,
    val lateNightReels: Int,
) {
    val perfect get() = budget == Win.WON && night == Win.WON && binge == Win.WON
}

data class BudgetChange(val from: Int, val to: Int, val hits: Int, val of: Int)

data class Badge(
    val id: String,
    val emoji: String,
    val title: String,
    val desc: String,
    val earnedOn: LocalDate?,
    val progress: String? = null,
)

data class Progress(
    val calibrating: Boolean,
    val calibrationDay: Int,
    val budgetStartsOn: LocalDate,
    val baselineMin: Double?,
    val manualBudget: Boolean,
    val bedtimeHour: Int,
    val today: DayRecord,
    val week: List<DayRecord?>,
    val budgetChange: BudgetChange?,
    val streak: Int,
    val bestStreak: Int,
    val streakAtRisk: Boolean,
    val reclaimedThisWeek: Double,
    val reclaimedLastWeek: Double,
    val reclaimedLifetime: Double,
    val weeklyReclaimed: List<Pair<LocalDate, Double>>,
    val perfectDays: Int,
    val badges: List<Badge>,
    val records: List<Pair<String, String>>,
)

object ProgressEngine {
    const val CALIBRATION_DAYS = 7
    const val MIN_BUDGET = 10
    const val BINGE_MIN = 20.0
    const val NIGHT_END_HOUR = 5

    fun load(db: Db, prefs: Prefs, now: Long = System.currentTimeMillis()): Progress =
        compute(
            reels = db.reelsSince(0),
            sessions = db.sessionsSince(0),
            trackingStart = prefs.trackingStart,
            now = now,
            zone = ZoneId.systemDefault(),
            manualBudgetMin = prefs.manualBudgetMin,
            bedtimeHour = prefs.bedtimeHour,
        )

    /**
     * Which night a late reel belongs to. Night of day D = D bedtime → D+1 05:00.
     * bedtimeHour is 21..23, or 0 for midnight.
     */
    fun nightOf(ts: Long, zone: ZoneId, bedtimeHour: Int): LocalDate? {
        val z = Instant.ofEpochMilli(ts).atZone(zone)
        return when {
            bedtimeHour != 0 && z.hour >= bedtimeHour -> z.toLocalDate()
            z.hour < NIGHT_END_HOUR -> z.toLocalDate().minusDays(1)
            else -> null
        }
    }

    private fun ceil5(x: Double) = (ceil(x / 5) * 5).toInt()
    private fun round5(x: Double) = ((x / 5).roundToInt() * 5)
    private fun clampB(x: Int) = max(MIN_BUDGET, x)

    fun compute(
        reels: List<ReelRow>,
        sessions: List<SessionRow>,
        trackingStart: Long,
        now: Long,
        zone: ZoneId,
        manualBudgetMin: Int,
        bedtimeHour: Int,
    ): Progress {
        fun dateOf(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        fun millisOf(d: LocalDate, hour: Int) = d.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

        val today = dateOf(now)
        val firstReel = reels.minOfOrNull { it.startTs }?.let(::dateOf)
        val startCandidates = listOfNotNull(firstReel, trackingStart.takeIf { it > 0 }?.let(::dateOf))
        val start = (startCandidates.minOrNull() ?: today).let { if (it.isAfter(today)) today else it }

        // ---- per-day aggregates
        val minutes = HashMap<LocalDate, Double>()
        val reelCount = HashMap<LocalDate, Int>()
        val late = HashMap<LocalDate, Int>()
        for (r in reels) {
            val d = dateOf(r.startTs)
            minutes[d] = (minutes[d] ?: 0.0) + r.durationMs / 60_000.0
            reelCount[d] = (reelCount[d] ?: 0) + 1
            nightOf(r.startTs, zone, bedtimeHour)?.let { late[it] = (late[it] ?: 0) + 1 }
        }
        val longest = HashMap<LocalDate, Double>()
        for (s in sessions) {
            val d = dateOf(s.startTs)
            longest[d] = max(longest[d] ?: 0.0, s.durationMs / 60_000.0)
        }
        fun mins(d: LocalDate) = minutes[d] ?: 0.0

        // ---- calibration & baseline
        val manual = manualBudgetMin > 0
        val calEnd = start.plusDays(CALIBRATION_DAYS.toLong()) // exclusive
        val baselineKnown = !today.isBefore(calEnd)
        val calDays = generateSequence(start) { it.plusDays(1) }
            .takeWhile { it.isBefore(calEnd) && !it.isAfter(today) }.toList()
        val baseline: Double? = if (calDays.isEmpty()) null else calDays.sumOf { mins(it) } / calDays.size
        val firstBudgetDay = if (manual) start else calEnd
        val calibrating = !manual && today.isBefore(calEnd)

        // ---- budget replay (deterministic from history; nothing stored)
        val budgets = HashMap<LocalDate, Int>()
        var budgetChange: BudgetChange? = null
        var firstBudgetDrop: LocalDate? = null
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        if (manual) {
            var d = start
            while (!d.isAfter(today)) { budgets[d] = manualBudgetMin; d = d.plusDays(1) }
        } else if (baselineKnown && baseline != null) {
            var b = clampB(round5(baseline * 0.9))
            var d = firstBudgetDay
            while (!d.isAfter(today)) {
                if (d.dayOfWeek == DayOfWeek.MONDAY && d.isAfter(firstBudgetDay)) {
                    val prev = (1L..7L).map { d.minusDays(it) }.filter { !it.isBefore(firstBudgetDay) }
                    if (prev.size >= 4) {
                        val hits = prev.count { mins(it) <= budgets.getValue(it) }
                        val rate = hits.toDouble() / prev.size
                        val nb = when {
                            rate >= 0.7 -> clampB(min(b - 5, ceil5(b * 0.9))) // one notch: ~10%, at least 5m
                            rate < 0.4 -> max(b, min(clampB(ceil5(baseline)), max(b + 5, ceil5(b * 1.1))))
                            else -> b
                        }
                        if (d == thisMonday && nb != b) budgetChange = BudgetChange(b, nb, hits, prev.size)
                        if (nb < b && firstBudgetDrop == null) firstBudgetDrop = d
                        b = nb
                    }
                }
                budgets[d] = b
                d = d.plusDays(1)
            }
        }

        // ---- day records
        val records = ArrayList<DayRecord>()
        var d = start
        while (!d.isAfter(today)) {
            val m = mins(d)
            val isToday = d == today
            val bud = budgets[d]
            val budgetWin = when {
                bud == null -> Win.NONE
                m > bud -> Win.MISSED
                isToday -> Win.PENDING
                else -> Win.WON
            }
            val lateN = late[d] ?: 0
            val nightWin = when {
                lateN > 0 -> Win.MISSED
                now >= millisOf(d.plusDays(1), NIGHT_END_HOUR) -> Win.WON
                else -> Win.PENDING
            }
            val lng = longest[d] ?: 0.0
            val bingeWin = when {
                lng > BINGE_MIN -> Win.MISSED
                isToday -> Win.PENDING
                else -> Win.WON
            }
            val reclaimed = if (baselineKnown && baseline != null && !isToday && !d.isBefore(calEnd))
                max(0.0, baseline - m) else 0.0
            records += DayRecord(d, m, reelCount[d] ?: 0, bud, budgetWin, nightWin, bingeWin, reclaimed, lng, lateN)
            d = d.plusDays(1)
        }
        val byDate = records.associateBy { it.date }

        // ---- streaks (never miss twice)
        var streak = 0
        var best = 0
        var consecutiveMiss = 0
        for (r in records) {
            when (r.budget) {
                Win.WON -> { streak++; consecutiveMiss = 0; best = max(best, streak) }
                Win.MISSED -> { consecutiveMiss++; if (consecutiveMiss >= 2) streak = 0 }
                else -> Unit
            }
        }
        val streakAtRisk = consecutiveMiss == 1 && streak > 0

        // ---- reclaimed
        fun weekSum(monday: LocalDate) = (0L..6L).sumOf { byDate[monday.plusDays(it)]?.reclaimedMin ?: 0.0 }
        // Last 8 weeks, but never bars from before tracking began (min 4 so the chart has shape).
        val startMonday = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeksTracked = (java.time.temporal.ChronoUnit.WEEKS.between(startMonday, thisMonday) + 1).toInt()
        val weekly = (minOf(8, maxOf(4, weeksTracked)) - 1 downTo 0).map { w -> thisMonday.minusWeeks(w.toLong()).let { it to weekSum(it) } }
        val lifetime = records.sumOf { it.reclaimedMin }

        // ---- badges (scan chronologically; earned date = first day the condition holds)
        val earned = LinkedHashMap<String, LocalDate>()
        fun earn(id: String, on: LocalDate) { if (id !in earned) earned[id] = on }
        var s = 0; var miss = 0; var nightRun = 0; var bestNightRun = 0; var cum = 0.0
        val fullWeeks = ArrayList<Pair<LocalDate, Double>>()
        for (r in records) {
            val completed = r.date != today
            when (r.budget) {
                Win.WON -> { s++; miss = 0; earn("first_win", r.date) }
                Win.MISSED -> { miss++; if (miss >= 2) s = 0 }
                else -> Unit
            }
            if (s >= 3) earn("streak_3", r.date)
            if (s >= 7) earn("streak_7", r.date)
            if (s >= 30) earn("streak_30", r.date)
            if (r.perfect) earn("perfect_day", r.date)
            when (r.night) {
                Win.WON -> { nightRun++; bestNightRun = max(bestNightRun, nightRun) }
                Win.MISSED -> nightRun = 0
                else -> Unit
            }
            if (nightRun >= 7) earn("night_7", r.date)
            if (completed && r.reels == 0 && r.date.isAfter(start)) earn("zero_day", r.date)
            cum += r.reclaimedMin
            if (cum >= 60) earn("reclaim_1h", r.date)
            if (cum >= 600) earn("reclaim_10h", r.date)
            if (cum >= 1440) earn("reclaim_24h", r.date)
            // a full Mon–Sun week just completed
            if (r.date.dayOfWeek == DayOfWeek.SUNDAY && completed && !r.date.minusDays(6).isBefore(start)) {
                val total = (0L..6L).sumOf { mins(r.date.minusDays(it)) }
                if (fullWeeks.size >= 2 && total < fullWeeks.minOf { it.second }) earn("best_week", r.date)
                fullWeeks += r.date.minusDays(6) to total
            }
        }
        firstBudgetDrop?.let { earn("budget_down", it) }

        val badges = listOf(
            Badge("first_win", "🌱", "First win", "Finish a day under budget", earned["first_win"]),
            Badge("perfect_day", "✨", "Perfect day", "Win all three in one day", earned["perfect_day"]),
            Badge("streak_3", "🔥", "Hat trick", "3-day budget streak", earned["streak_3"], "best $best/3"),
            Badge("streak_7", "💪", "Full week", "7-day budget streak", earned["streak_7"], "best $best/7"),
            Badge("streak_30", "🧠", "Rewired", "30-day budget streak", earned["streak_30"], "best $best/30"),
            Badge("night_7", "🌙", "Sleep defender", "7 clean nights in a row", earned["night_7"], "best $bestNightRun/7"),
            Badge("zero_day", "👻", "Ghost", "A full day with zero reels", earned["zero_day"]),
            Badge("reclaim_1h", "⏳", "First hour back", "Reclaim 1 hour", earned["reclaim_1h"], "${lifetime.roundToInt()}/60m"),
            Badge("reclaim_10h", "📚", "Ten hours back", "Reclaim 10 hours", earned["reclaim_10h"], "${(lifetime / 60).roundToInt()}/10h"),
            Badge("reclaim_24h", "🌍", "A whole day back", "Reclaim 24 hours", earned["reclaim_24h"], "${(lifetime / 60).roundToInt()}/24h"),
            Badge("budget_down", "📉", "Leveling down", "Earn a lower budget", earned["budget_down"]),
            Badge("best_week", "🏆", "Personal best", "Your lowest week ever (after 2 full weeks)", earned["best_week"]),
        ).sortedByDescending { it.earnedOn }

        // ---- personal records
        val recs = buildList {
            add("Best budget streak" to "$best day${if (best == 1) "" else "s"}")
            add("Longest clean-night run" to "$bestNightRun night${if (bestNightRun == 1) "" else "s"}")
            add("Perfect days" to "${records.count { it.perfect }}")
            records.filter { it.reclaimedMin > 0 }.maxByOrNull { it.reclaimedMin }?.let {
                add("Most reclaimed in a day" to "${it.reclaimedMin.roundToInt()}m (${it.date.month.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }} ${it.date.dayOfMonth})")
            }
            if (fullWeeks.size >= 2) fullWeeks.minByOrNull { it.second }?.let { (mon, total) ->
                add("Lowest week" to "${fmtMin(total)} (week of ${mon.month.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }} ${mon.dayOfMonth})")
            }
        }

        return Progress(
            calibrating = calibrating,
            calibrationDay = (java.time.temporal.ChronoUnit.DAYS.between(start, today) + 1).toInt().coerceIn(1, CALIBRATION_DAYS),
            budgetStartsOn = calEnd,
            baselineMin = baseline,
            manualBudget = manual,
            bedtimeHour = bedtimeHour,
            today = byDate.getValue(today),
            week = (0L..6L).map { byDate[thisMonday.plusDays(it)] },
            budgetChange = budgetChange,
            streak = streak,
            bestStreak = best,
            streakAtRisk = streakAtRisk,
            reclaimedThisWeek = weekSum(thisMonday),
            reclaimedLastWeek = weekSum(thisMonday.minusWeeks(1)),
            reclaimedLifetime = lifetime,
            weeklyReclaimed = weekly,
            perfectDays = records.count { it.perfect },
            badges = badges,
            records = recs,
        )
    }

    fun fmtMin(m: Double): String {
        val t = m.roundToInt()
        return if (t >= 60) "${t / 60}h ${t % 60}m" else "${t}m"
    }

    /** Make reclaimed time concrete. */
    fun equivalent(min: Double): String? = when {
        min < 30 -> null
        min < 120 -> "≈ ${(min / 30).toInt()} workout${if (min >= 60) "s" else ""}"
        min < 60 * 20 -> "≈ ${(min / 110).let { if (it < 2) "1 movie" else "${it.toInt()} movies" }}"
        else -> "≈ ${(min / 420).let { if (it < 2) "1 book" else "${it.toInt()} books" }} read"
    }
}
