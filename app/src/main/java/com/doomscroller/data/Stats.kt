package com.doomscroller.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DayStat(val date: LocalDate, val reels: Int, val ms: Long, val opens: Int, val sessions: Int)

data class LongestSession(val reels: Int, val ms: Long, val start: Long)

data class Analytics(
    // Today
    val today: DayStat,
    val todayAvgSec: Double,
    val todayComments: Int,
    val todayLikes: Int,
    val todayShares: Int,
    val todayLongestSession: LongestSession?,
    val avgReelsPrev7: Double,
    // Range
    val rangeDays: Int,
    val days: List<DayStat>,
    val rangeReels: Int,
    val rangeMs: Long,
    val hourly: IntArray,             // reels started in each hour of the day
    val weekdayAvg: DoubleArray,      // avg reels per Mon..Sun
    val avgReelSec: Double,
    val medianReelSec: Double,
    val skipRate: Double,             // share of reels watched < 3s
    val longWatchRate: Double,        // share of reels watched > 20s
    val durationBuckets: IntArray,    // <2, 2-5, 5-10, 10-20, 20-40, 40s+
    val commentRate: Double,
    val likeRate: Double,
    val shareRate: Double,
    val avgReelsPerSession: Double,
    val avgSessionMs: Long,
    val longestSession: LongestSession?,
    val sessionsPerDay: Double,
    val opensPerDay: Double,
    val peakHour: Int?,
    val lateNightReels: Int,          // 00:00-04:59
    val topCreators: List<Pair<String, Int>>,
    val bingeSessions: Int,           // sessions with 50+ reels
    val avgGapBetweenSessionsMin: Double?,
    // All time
    val allTimeReels: Int,
    val allTimeMs: Long,
    val trackingSince: LocalDate?,
    val biggestDay: DayStat?,
    val streakDays: Int,              // consecutive days (ending today) with any reels
    val cleanDaysInRange: Int,
    val yearlyHoursProjection: Double,
    val thumbMeters: Double,
    // Time per surface ("reels", "feed", "explore")
    val todaySurfaceMs: Map<String, Long>,
    val rangeSurfaceMs: Map<String, Long>,
)

object Stats {
    /** Roughly how far your thumb travels per swipe, in meters. */
    private const val SWIPE_METERS = 0.09
    /** Ignore absurd durations (phone left on a reel) in averages. */
    private const val AVG_CAP_MS = 5 * 60_000L

    fun compute(db: Db, rangeDays: Int): Analytics {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val loadDays = maxOf(rangeDays, 8).toLong()
        val firstDay = today.minusDays(loadDays - 1)
        val from = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()

        val reels = db.reelsSince(from)
        val sessions = db.sessionsSince(from)
        val opens = db.opensSince(from)
        val surfaces = db.surfacesSince(from)
        val (allCount, allMs, firstTs) = db.allTime()

        fun dayOf(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

        val reelsByDay = reels.groupBy { dayOf(it.startTs) }
        val sessionsByDay = sessions.groupBy { dayOf(it.startTs) }
        val opensByDay = opens.groupingBy { dayOf(it) }.eachCount()

        fun dayStat(d: LocalDate) = DayStat(
            d,
            reelsByDay[d]?.size ?: 0,
            reelsByDay[d]?.sumOf { it.durationMs } ?: 0L,
            opensByDay[d] ?: 0,
            sessionsByDay[d]?.size ?: 0,
        )

        // ---- today
        val todayReels = reelsByDay[today].orEmpty()
        val todayStat = dayStat(today)
        val prev7 = (1..7).map { dayStat(today.minusDays(it.toLong())).reels }
        val longestToday = sessionsByDay[today].orEmpty().maxByOrNull { it.reelCount }
            ?.let { LongestSession(it.reelCount, it.durationMs, it.startTs) }

        // ---- range
        val rangeFirst = today.minusDays(rangeDays.toLong() - 1)
        val days = (0 until rangeDays).map { dayStat(rangeFirst.plusDays(it.toLong())) }
        val rangeFrom = rangeFirst.atStartOfDay(zone).toInstant().toEpochMilli()
        val rr = reels.filter { it.startTs >= rangeFrom }
        val rs = sessions.filter { it.startTs >= rangeFrom }
        val ro = opens.filter { it >= rangeFrom }

        val hourly = IntArray(24)
        var lateNight = 0
        rr.forEach {
            val h = Instant.ofEpochMilli(it.startTs).atZone(zone).hour
            hourly[h]++
            if (h < 5) lateNight++
        }

        val weekdayTotals = DoubleArray(7)
        val weekdayCounts = IntArray(7)
        days.forEach {
            val i = it.date.dayOfWeek.value - 1
            weekdayTotals[i] += it.reels.toDouble()
            weekdayCounts[i]++
        }
        val weekdayAvg = DoubleArray(7) { if (weekdayCounts[it] == 0) 0.0 else weekdayTotals[it] / weekdayCounts[it] }

        val durs = rr.map { it.durationMs.coerceAtMost(AVG_CAP_MS) }.sorted()
        val buckets = IntArray(6)
        durs.forEach {
            val s = it / 1000.0
            buckets[when {
                s < 2 -> 0; s < 5 -> 1; s < 10 -> 2; s < 20 -> 3; s < 40 -> 4; else -> 5
            }]++
        }
        fun rate(n: Int) = if (rr.isEmpty()) 0.0 else n.toDouble() / rr.size

        val longest = rs.maxWithOrNull(compareBy<SessionRow> { it.reelCount }.thenBy { it.durationMs })
            ?.let { LongestSession(it.reelCount, it.durationMs, it.startTs) }

        val gaps = rs.zipWithNext { a, b -> (b.startTs - a.endTs) / 60_000.0 }.filter { it in 0.0..(16 * 60.0) }

        val activeDays = days.count { it.reels > 0 }.coerceAtLeast(1)

        // ---- records & streaks (within loaded window, which is >= range)
        val loadedDays = (0 until loadDays.toInt()).map { dayStat(firstDay.plusDays(it.toLong())) }
        var streak = 0
        for (d in loadedDays.reversed()) { if (d.reels > 0) streak++ else if (d.date != today) break }

        val trackingSince = firstTs?.let { dayOf(it) }
        val trackedDays = trackingSince?.let {
            (java.time.temporal.ChronoUnit.DAYS.between(it, today) + 1).coerceAtLeast(1)
        } ?: 1
        val cleanDays = days.count { it.reels == 0 && (trackingSince == null || !it.date.isBefore(trackingSince)) }

        // Use the last 7 days' pace for the "per year" projection.
        val last7Ms = (0..6).sumOf { dayStat(today.minusDays(it.toLong())).ms }
        val daysForPace = minOf(7L, trackedDays)
        val yearly = last7Ms / daysForPace.toDouble() * 365 / 3_600_000.0

        val todayFrom = today.atStartOfDay(zone).toInstant().toEpochMilli()
        fun surfaceTotals(since: Long) = surfaces.filter { it.startTs >= since }
            .groupBy { it.surface }.mapValues { (_, v) -> v.sumOf { it.durationMs } }

        return Analytics(
            today = todayStat,
            todayAvgSec = todayReels.map { it.durationMs.coerceAtMost(AVG_CAP_MS) }.average().orZero() / 1000,
            todayComments = todayReels.count { it.commented },
            todayLikes = todayReels.count { it.liked },
            todayShares = todayReels.count { it.shared },
            todayLongestSession = longestToday,
            avgReelsPrev7 = prev7.average(),
            rangeDays = rangeDays,
            days = days,
            rangeReels = rr.size,
            rangeMs = rr.sumOf { it.durationMs },
            hourly = hourly,
            weekdayAvg = weekdayAvg,
            avgReelSec = durs.average().orZero() / 1000,
            medianReelSec = if (durs.isEmpty()) 0.0 else durs[durs.size / 2] / 1000.0,
            skipRate = rate(durs.count { it < 3000 }),
            longWatchRate = rate(durs.count { it > 20_000 }),
            durationBuckets = buckets,
            commentRate = rate(rr.count { it.commented }),
            likeRate = rate(rr.count { it.liked }),
            shareRate = rate(rr.count { it.shared }),
            avgReelsPerSession = if (rs.isEmpty()) 0.0 else rs.sumOf { it.reelCount }.toDouble() / rs.size,
            avgSessionMs = if (rs.isEmpty()) 0L else rs.sumOf { it.durationMs } / rs.size,
            longestSession = longest,
            sessionsPerDay = rs.size.toDouble() / activeDays,
            opensPerDay = ro.size.toDouble() / days.size.coerceAtMost(trackedDays.toInt()).coerceAtLeast(1),
            peakHour = hourly.indices.maxByOrNull { hourly[it] }?.takeIf { hourly[it] > 0 },
            lateNightReels = lateNight,
            topCreators = rr.mapNotNull { it.author?.takeIf { a -> a.isNotBlank() } }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            bingeSessions = rs.count { it.reelCount >= 50 },
            avgGapBetweenSessionsMin = gaps.takeIf { it.isNotEmpty() }?.average(),
            allTimeReels = allCount,
            allTimeMs = allMs,
            trackingSince = trackingSince,
            biggestDay = loadedDays.maxByOrNull { it.reels }?.takeIf { it.reels > 0 },
            streakDays = streak,
            cleanDaysInRange = cleanDays,
            yearlyHoursProjection = yearly,
            thumbMeters = allCount * SWIPE_METERS,
            todaySurfaceMs = surfaceTotals(todayFrom),
            rangeSurfaceMs = surfaceTotals(rangeFrom),
        )
    }

    private fun Double.orZero() = if (isNaN()) 0.0 else this

    val WEEKDAYS = DayOfWeek.entries.map { it.name.take(1) }
}
