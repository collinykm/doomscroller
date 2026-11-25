package com.doomscroller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doomscroller.data.Badge
import com.doomscroller.data.DayRecord
import com.doomscroller.data.Progress
import com.doomscroller.data.ProgressEngine
import com.doomscroller.data.ProgressEngine.fmtMin
import com.doomscroller.data.Win
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// One color per daily win, used everywhere so the rings are learnable at a glance.
private val BudgetC = Teal
private val NightC = Violet
private val BingeC = Amber
private val Over = Color(0xFFFF7A6B)

private fun bedtimeLabel(h: Int) = when (h) { 0 -> "midnight"; 12 -> "noon"; else -> "${if (h > 12) h - 12 else h} PM" }

fun LazyListScope.progressItems(p: Progress, seen: Set<String>, onSeen: (Set<String>) -> Unit) {
    val fresh = p.badges.filter { it.earnedOn != null && it.id !in seen }
    if (fresh.isNotEmpty()) item { NewBadgeBanner(fresh) { onSeen(fresh.map { it.id }.toSet()) } }

    if (p.calibrating) item { CalibrationCard(p) } else item { TodayCard(p) }
    if (!p.calibrating) item { StreakReclaimRow(p) }
    item { WeekCard(p) }
    p.budgetChange?.let { c -> item { BudgetChangeNote(c.from, c.to, c.hits, c.of) } }
    if (!p.calibrating) item { ReclaimCard(p) }
    item { BadgesCard(p.badges) }
    if (!p.calibrating) item { RecordsCard(p.records) }
    item { HowItWorks(p) }
}

// ------------------------------------------------------------------ today

@Composable
private fun TodayCard(p: Progress) {
    val t = p.today
    val budget = t.budgetMin ?: 0
    val left = budget - t.minutes
    val frac = if (budget > 0) (left / budget).toFloat().coerceIn(0f, 1f) else 0f
    val ringColor = when { left < 0 -> Over; frac < 0.25f -> Amber; else -> BudgetC }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF12302E), Color(0xFF1E1830), Color(0xFF1A1520))))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TODAY", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
            modifier = Modifier.fillMaxWidth())
        Box(Modifier.size(190.dp).padding(top = 6.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(170.dp)) {
                val sw = 16.dp.toPx()
                ring(1f, ringColor.copy(alpha = 0.14f), sw)
                if (frac > 0f) ring(frac, ringColor, sw)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    fmtMin(kotlin.math.abs(left)), color = TextHi, fontSize = 38.sp,
                    fontWeight = FontWeight.Black, lineHeight = 40.sp,
                )
                Text(if (left >= 0) "left today" else "over today", color = if (left >= 0) Muted else Over, fontSize = 14.sp)
            }
        }
        Text(
            "${fmtMin(t.minutes)} used of your ${budget}m budget",
            color = Muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WinChip("Budget", t.budget, BudgetC,
                when (t.budget) { Win.MISSED -> "Missed today"; else -> "Stay under ${budget}m" }, Modifier.weight(1f))
            WinChip("Clean night", t.night, NightC,
                when (t.night) { Win.MISSED -> "Scrolled late"; Win.WON -> "Slept clean"; else -> "Off by ${bedtimeLabel(p.bedtimeHour)}" }, Modifier.weight(1f))
            WinChip("No binges", t.binge, BingeC,
                when (t.binge) { Win.MISSED -> "${t.longestSessionMin.roundToInt()}m session"; else -> "Sessions < 20m" }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun WinChip(title: String, state: Win, color: Color, sub: String, modifier: Modifier) {
    val (mark, fg, bg) = when (state) {
        Win.WON -> Triple("✓", color, color.copy(alpha = 0.18f))
        Win.MISSED -> Triple("–", Muted, Color.White.copy(alpha = 0.04f))
        else -> Triple("…", color, Color.White.copy(alpha = 0.06f))
    }
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(bg)
            .border(1.dp, if (state == Win.PENDING) color.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(14.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (state == Win.MISSED) Muted.copy(alpha = 0.4f) else color))
            Text(" $title", color = TextHi, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(mark, color = fg, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text(sub, color = Muted, fontSize = 11.sp, lineHeight = 13.sp)
    }
}

@Composable
private fun CalibrationCard(p: Progress) {
    val fmt = DateTimeFormatter.ofPattern("EEEE")
    CardBox(Modifier.border(1.dp, BudgetC.copy(alpha = 0.4f), RoundedCornerShape(20.dp))) {
        Text("LEARNING YOUR NORMAL", color = BudgetC, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text("Day ${p.calibrationDay} of ${ProgressEngine.CALIBRATION_DAYS}", color = TextHi, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(8.dp))
        RateBar(p.calibrationDay / ProgressEngine.CALIBRATION_DAYS.toFloat(), BudgetC)
        Spacer(Modifier.height(10.dp))
        Text(
            "Scroll like you normally would. On ${p.budgetStartsOn.format(fmt)} you get a daily budget a little " +
                "under your normal, and every minute you stay under it counts as time reclaimed.",
            color = TextHi, fontSize = 14.sp, lineHeight = 20.sp,
        )
        p.baselineMin?.let {
            Text("Your normal so far: ${fmtMin(it)}/day", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("You can already win these two:", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val t = p.today
            WinChip("Clean night", t.night, NightC,
                if (t.night == Win.MISSED) "Scrolled late" else "Off by ${bedtimeLabel(p.bedtimeHour)}", Modifier.weight(1f))
            WinChip("No binges", t.binge, BingeC,
                if (t.binge == Win.MISSED) "${t.longestSessionMin.roundToInt()}m session" else "Sessions < 20m", Modifier.weight(1f))
        }
    }
}

// ------------------------------------------------------------------ streak + reclaimed

@Composable
private fun StreakReclaimRow(p: Progress) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp)) {
            Text("Budget streak", color = Muted, fontSize = 12.sp)
            Text("🔥 ${p.streak}", color = TextHi, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(
                when {
                    p.streakAtRisk -> "Missed yesterday. Don't miss twice."
                    p.streak > 0 && p.streak >= p.bestStreak -> "Personal best!"
                    else -> "Best: ${p.bestStreak}"
                },
                color = if (p.streakAtRisk) Amber else Muted, fontSize = 11.sp,
            )
        }
        Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp)) {
            Text("Reclaimed this week", color = Muted, fontSize = 12.sp)
            Text(fmtMin(p.reclaimedThisWeek), color = BudgetC, fontSize = 26.sp, fontWeight = FontWeight.Black)
            val diff = p.reclaimedThisWeek - p.reclaimedLastWeek
            Text(
                if (p.reclaimedLastWeek <= 0) "Counts finished days" else
                    if (diff >= 0) "▲ ${fmtMin(diff)} vs last week" else "${fmtMin(-diff)} to beat last week",
                color = Muted, fontSize = 11.sp,
            )
        }
    }
}

// ------------------------------------------------------------------ week strip

@Composable
private fun WeekCard(p: Progress) {
    CardBox {
        val wins = p.week.filterNotNull()
        val earned = wins.sumOf { listOf(it.budget, it.night, it.binge).count { w -> w == Win.WON } }
        SectionTitle("This week", "$earned wins so far${if (p.perfectDays > 0) " · ${p.perfectDays} perfect day${if (p.perfectDays == 1) "" else "s"} all-time" else ""}")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, label ->
                val r = p.week.getOrNull(i)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TripleRing(r, 38.dp)
                    Text(label, color = if (r?.date == p.today.date) TextHi else Muted, fontSize = 11.sp,
                        fontWeight = if (r?.date == p.today.date) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("Budget", BudgetC); Legend("Night", NightC); Legend("No binge", BingeC)
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(" $label", color = Muted, fontSize = 11.sp)
    }
}

/** Apple-style concentric rings: outer budget, middle night, inner binge. */
@Composable
private fun TripleRing(r: DayRecord?, size: Dp) {
    Canvas(Modifier.size(size).alpha(if (r == null) 0.35f else 1f)) {
        val sw = this.size.minDimension * 0.11f
        listOf(
            r?.budget to BudgetC,
            r?.night to NightC,
            r?.binge to BingeC,
        ).forEachIndexed { i, (state, c) ->
            val inset = sw / 2 + i * (sw * 1.35f)
            ring(1f, c.copy(alpha = 0.14f), sw, inset)
            when (state) {
                Win.WON -> ring(1f, c, sw, inset)
                Win.PENDING -> ring(1f, c.copy(alpha = 0.4f), sw * 0.35f, inset)
                else -> Unit
            }
        }
        if (r?.perfect == true) {
            drawCircle(Color.White, radius = sw * 0.45f, center = center)
        }
    }
}

private fun DrawScope.ring(fraction: Float, color: Color, stroke: Float, inset: Float = stroke / 2) {
    drawArc(
        color, startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false,
        topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, size.height - inset * 2),
        style = Stroke(stroke, cap = StrokeCap.Round),
    )
}

// ------------------------------------------------------------------ budget change

@Composable
private fun BudgetChangeNote(from: Int, to: Int, hits: Int, of: Int) {
    val down = to < from
    CardBox(Modifier.border(1.dp, (if (down) BudgetC else Amber).copy(alpha = 0.4f), RoundedCornerShape(20.dp))) {
        Text(if (down) "New week, new budget 📉" else "New week, fresh start", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            if (down) "You stayed under budget $hits of $of days last week, so it's leveling down: ${from}m → ${to}m a day."
            else "Last week was tough ($hits of $of days). Budget eased to ${to}m (from ${from}m). Consistency beats intensity.",
            color = Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ------------------------------------------------------------------ reclaimed chart

@Composable
private fun ReclaimCard(p: Progress) {
    CardBox {
        SectionTitle(
            "Time reclaimed",
            "${fmtMin(p.reclaimedLifetime)} total" + (ProgressEngine.equivalent(p.reclaimedLifetime)?.let { " · $it" } ?: ""),
        )
        val fmt = DateTimeFormatter.ofPattern("M/d")
        BarChart(
            p.weeklyReclaimed.map { (it.second / 60).toFloat() },
            p.weeklyReclaimed.mapIndexed { i, (mon, _) ->
                if (p.weeklyReclaimed.size <= 5 || (p.weeklyReclaimed.lastIndex - i) % 2 == 0) mon.format(fmt) else null
            },
            color = BudgetC, highlight = p.weeklyReclaimed.lastIndex, height = 130.dp,
            valueLabel = { if (it >= 1) "${String.format(java.util.Locale.US, "%.1f", it)}h" else "${(it * 60).roundToInt()}m" },
        )
        Text(
            "Minutes under your normal (${p.baselineMin?.let { fmtMin(it) } ?: "–"}/day), per week. Like steps: it only goes up.",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// ------------------------------------------------------------------ badges

@Composable
private fun NewBadgeBanner(fresh: List<Badge>, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3B2A0C), Color(0xFF2A1640))))
            .border(1.dp, Amber.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Text(if (fresh.size == 1) "Badge unlocked" else "${fresh.size} badges unlocked", color = Amber,
            fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        fresh.take(3).forEach { b ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(b.emoji, fontSize = 30.sp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(b.title, color = TextHi, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(b.desc, color = Muted, fontSize = 13.sp)
                }
            }
        }
        Text(
            "Nice",
            color = Color.Black, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Amber).clickable(onClick = onDismiss).padding(vertical = 10.dp),
        )
    }
}

@Composable
private fun BadgesCard(badges: List<Badge>) {
    CardBox {
        val n = badges.count { it.earnedOn != null }
        SectionTitle("Badges", "$n of ${badges.size} earned")
        badges.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { b -> BadgeCell(b, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun BadgeCell(b: Badge, modifier: Modifier) {
    val got = b.earnedOn != null
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(54.dp).clip(CircleShape)
                .background(if (got) Amber.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f))
                .border(1.5.dp, if (got) Amber.copy(alpha = 0.7f) else Muted.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(b.emoji, fontSize = 24.sp, modifier = Modifier.alpha(if (got) 1f else 0.25f))
        }
        Text(b.title, color = if (got) TextHi else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        Text(
            if (got) b.earnedOn!!.format(DateTimeFormatter.ofPattern("MMM d")) else (b.progress ?: b.desc),
            color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 12.sp,
        )
    }
}

// ------------------------------------------------------------------ records + explainer

@Composable
private fun RecordsCard(records: List<Pair<String, String>>) {
    CardBox {
        SectionTitle("Personal records")
        records.forEach { (k, v) ->
            Row(Modifier.padding(vertical = 5.dp)) {
                Text(k, color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(v, color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HowItWorks(p: Progress) {
    var open by remember { mutableStateOf(false) }
    CardBox(Modifier.clickable { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("How the game works", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (open) "–" else "+", color = Muted, fontSize = 18.sp)
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            listOf(
                "Budget" to (if (p.manualBudget) "You set a fixed daily budget in Settings." else
                    "The first 7 days learn your normal. Your budget starts about 10% under it. Every Monday it drops a notch if you hit it 5+ days, holds if you were close, and eases if the week was rough."),
                "Clean night" to "No reels between ${bedtimeLabel(p.bedtimeHour)} and 5 AM. Late-night scrolling costs the most sleep.",
                "No binges" to "Every session under ${ProgressEngine.BINGE_MIN.roundToInt()} minutes.",
                "Streaks" to "Never miss twice: one missed day pauses your streak, two in a row resets it.",
                "Reclaimed" to "Minutes under your normal on finished days. It only goes up.",
            ).forEach { (k, v) ->
                Text(k, color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Text(v, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}
