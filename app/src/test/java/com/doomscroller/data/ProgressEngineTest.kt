package com.doomscroller.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ProgressEngineTest {
    private val zone = ZoneId.of("America/Toronto")
    // Monday
    private val start = LocalDate.of(2026, 11, 2)

    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    /** One session per day at 3 PM with [minutesPerDay] of 1-minute reels (sessions of ≤ 15 min). */
    private fun history(minutesPerDay: List<Int>, lateNightOn: Set<Int> = emptySet()): Pair<List<ReelRow>, List<SessionRow>> {
        val reels = ArrayList<ReelRow>()
        val sessions = ArrayList<SessionRow>()
        minutesPerDay.forEachIndexed { i, mins ->
            val d = start.plusDays(i.toLong())
            var t = at(d, 15)
            var left = mins
            while (left > 0) {
                val chunk = minOf(15, left)
                val s = t
                repeat(chunk) { reels += ReelRow(t, t + 60_000, s, false, false, false, "a"); t += 60_000 }
                sessions += SessionRow(0, s, t, chunk)
                t += 3_600_000
                left -= chunk
            }
            if (i in lateNightOn) reels += ReelRow(at(d, 23, 30), at(d, 23, 31), at(d, 23, 30), false, false, false, null)
        }
        return reels to sessions
    }

    private fun run(mins: List<Int>, todayIdx: Int, late: Set<Int> = emptySet(), manual: Int = 0, hour: Int = 20) =
        history(mins, late).let { (r, s) ->
            ProgressEngine.compute(r, s, at(start, 0), at(start.plusDays(todayIdx.toLong()), hour), zone, manual, 23)
        }

    @Test fun calibratesForSevenDays() {
        val p = run(List(3) { 60 }, todayIdx = 2)
        assertTrue(p.calibrating)
        assertEquals(3, p.calibrationDay)
        assertNull(p.today.budgetMin)
        assertEquals(0.0, p.reclaimedLifetime, 0.0)
    }

    @Test fun budgetStartsTenPercentUnderBaseline() {
        val p = run(List(8) { 60 }, todayIdx = 7)
        assertFalse(p.calibrating)
        assertEquals(60.0, p.baselineMin!!, 0.01)
        assertEquals(55, p.today.budgetMin) // 54 rounded to nearest 5
    }

    @Test fun reclaimedCountsCompletedDaysOnly() {
        // baseline 60; day 7 used 30 → 30 reclaimed; day 8 (today) excluded
        val p = run(List(7) { 60 } + listOf(30, 10), todayIdx = 8)
        assertEquals(30.0, p.reclaimedLifetime, 0.01)
    }

    @Test fun neverMissTwice() {
        // calibration 60/day, then: win, win, miss, win  → streak 3, not reset
        val p = run(List(7) { 60 } + listOf(30, 30, 90, 30, 30), todayIdx = 11)
        assertEquals(3, p.streak)
        // two misses in a row resets
        val q = run(List(7) { 60 } + listOf(30, 30, 90, 90, 30, 30), todayIdx = 12)
        assertEquals(1, q.streak)
        assertEquals(2, q.bestStreak)
    }

    @Test fun streakAtRiskAfterSingleMiss() {
        val p = run(List(7) { 60 } + listOf(30, 30, 90, 0), todayIdx = 10)
        assertTrue(p.streakAtRisk)
    }

    @Test fun budgetDropsOnMondayAfterGoodWeek() {
        // calibration = Mon 2..Sun 8 (60/day). Week of Mon 9: all wins at 30. Monday 16 → 55 → 50
        val p = run(List(7) { 60 } + List(7) { 30 } + listOf(0), todayIdx = 14)
        assertEquals(50, p.today.budgetMin)
        assertNotNull(p.budgetChange)
        assertEquals(55, p.budgetChange!!.from)
        assertTrue(p.badges.first { it.id == "budget_down" }.earnedOn != null)
    }

    @Test fun budgetEasesAfterBadWeekButNotAboveBaseline() {
        val p = run(List(7) { 60 } + List(7) { 120 } + listOf(0), todayIdx = 14)
        assertEquals(60, p.today.budgetMin)
    }

    @Test fun lateNightBreaksThatNightAndNightResolvesAt5am() {
        // late reel on Wed Nov 11 (idx 9); today Thu Nov 12 (same Mon–Sun week)
        val p = run(List(11) { 10 }, todayIdx = 10, late = setOf(9))
        val r = p.week.filterNotNull().first { it.date == start.plusDays(9) }
        assertEquals(Win.WON, p.week.filterNotNull().first { it.date == start.plusDays(8) }.night)
        assertEquals(Win.MISSED, r.night)
        assertEquals(Win.PENDING, p.today.night)
    }

    @Test fun bingeOver20MinutesMisses() {
        val (reels, sessions) = history(List(2) { 10 })
        val long = SessionRow(0, at(start, 10), at(start, 10, 30), 40)
        val p = ProgressEngine.compute(reels, sessions + long, at(start, 0), at(start.plusDays(1), 20), zone, 0, 23)
        val first = p.week.filterNotNull().first { it.date == start }
        assertEquals(Win.MISSED, first.binge)
        assertEquals(Win.PENDING, p.today.binge)
    }

    @Test fun manualBudgetSkipsCalibration() {
        val p = run(List(2) { 20 }, todayIdx = 1, manual = 30)
        assertFalse(p.calibrating)
        assertEquals(30, p.today.budgetMin)
        assertEquals(Win.WON, p.week.filterNotNull().first().budget)
    }

    @Test fun nightAttribution() {
        val d = LocalDate.of(2026, 11, 10)
        assertEquals(d, ProgressEngine.nightOf(at(d, 23, 10), zone, 23))
        assertEquals(d, ProgressEngine.nightOf(at(d.plusDays(1), 2), zone, 23))
        assertNull(ProgressEngine.nightOf(at(d, 22), zone, 23))
        assertNull(ProgressEngine.nightOf(at(d, 23, 30), zone, 0))
    }
}
