package com.doomscroller.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doomscroller.data.Analytics
import com.doomscroller.data.Db
import com.doomscroller.data.Prefs
import com.doomscroller.data.Progress
import com.doomscroller.data.ProgressEngine
import com.doomscroller.data.Stats
import com.doomscroller.tracker.ReelsService
import com.doomscroller.tracker.fmtDur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

fun isServiceEnabled(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    val me = ComponentName(ctx, ReelsService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
}

private fun hourLabel(h: Int): String = when {
    h == 0 -> "12a"; h < 12 -> "${h}a"; h == 12 -> "12p"; else -> "${h - 12}p"
}

private fun hourLong(h: Int): String = when {
    h == 0 -> "midnight"; h < 12 -> "$h AM"; h == 12 -> "noon"; else -> "${h - 12} PM"
}

internal fun pct(x: Double) = "${(x * 100).roundToInt()}%"
internal fun one(x: Double) = String.format(Locale.US, "%.1f", x)

@Composable
fun DashboardScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val prefs = remember { Prefs(ctx) }
    var range by remember { mutableIntStateOf(7) }
    var tab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val changes by Db.changes.collectAsState()
    val prefsVersion by Prefs.version.collectAsState()
    val status by ReelsService.status.collectAsState()
    var a by remember { mutableStateOf<Analytics?>(null) }
    var p by remember { mutableStateOf<Progress?>(null) }
    var seen by remember { mutableStateOf(prefs.seenBadges) }
    val enabled = remember(resumeTick, status.connected) { isServiceEnabled(ctx) }

    LaunchedEffect(range, changes, resumeTick) {
        delay(150)
        a = withContext(Dispatchers.IO) { runCatching { Stats.compute(db, range) }.getOrNull() }
    }
    LaunchedEffect(changes, resumeTick, prefsVersion) {
        delay(150)
        p = withContext(Dispatchers.IO) { runCatching { ProgressEngine.load(db, prefs) }.getOrNull() }
    }

    val insets = WindowInsets.systemBars.asPaddingValues()
    LazyColumn(
        Modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = insets.calculateTopPadding() + 12.dp,
            bottom = insets.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Doomscroller", fontSize = 26.sp, fontWeight = FontWeight.Black, color = TextHi)
                    StatusLine(enabled, status.igForeground, status.inReels)
                }
                Text(
                    "Settings",
                    color = Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { showSettings = true }
                        .border(1.dp, CardHi, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        if (!enabled) item { SetupCard(ctx) }
        item { Segmented(listOf("Progress", "Insights"), tab) { tab = it } }

        if (tab == 0) {
            val pr = p
            if (pr == null) item { Text("Crunching numbers…", color = Muted) }
            else progressItems(pr, seen) { seen = seen + it; prefs.seenBadges = seen }
            return@LazyColumn
        }

        val an = a
        if (an == null) {
            item { Text("Crunching numbers…", color = Muted) }
            return@LazyColumn
        }

        item { HeroCard(an) }
        item { TodayTiles(an) }
        if (an.rangeSurfaceMs.isNotEmpty()) item { SurfacesCard(an) }
        item { RangePicker(range) { range = it } }
        item { TrendCard(an, p?.today?.budgetMin) }
        item { WhenCard(an) }
        item { WatchCard(an) }
        item { EngagementCard(an) }
        item { SessionsCard(an) }
        if (an.topCreators.isNotEmpty()) item { CreatorsCard(an) }
        item { DamageCard(an) }
    }

    // Scrim so content doesn't scroll under the transparent status bar.
    Box(Modifier.fillMaxWidth().height(insets.calculateTopPadding()).background(Bg))

    if (showSettings) SettingsDialog(prefs, db, status) { showSettings = false }
}

@Composable
private fun StatusLine(enabled: Boolean, igFg: Boolean, inReels: Boolean) {
    val (dot, text) = when {
        !enabled -> Pink to "Tracking off"
        inReels -> Amber to "Watching you scroll reels right now"
        igFg -> Teal to "Instagram open"
        else -> Teal to "Tracking on"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(end = 6.dp).width(8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(dot))
        Text(text, color = Muted, fontSize = 13.sp)
    }
}

@Composable
internal fun CardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp),
        content = content,
    )
}

@Composable
internal fun SectionTitle(title: String, sub: String? = null) {
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextHi)
    if (sub != null) Text(sub, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SetupCard(ctx: Context) {
    CardBox(Modifier.border(1.dp, Pink.copy(alpha = 0.6f), RoundedCornerShape(20.dp))) {
        SectionTitle("Turn on tracking", "Doomscroller reads Instagram's screen through Android's accessibility service to count reels.")
        Text(
            "1. Tap \"Open accessibility settings\", find Doomscroller under Downloaded apps, switch it on.\n" +
                "2. If the switch is greyed out (Android 13+ blocks sideloaded apps): tap \"App info\", " +
                "then the ⋮ menu → \"Allow restricted settings\", and try step 1 again.",
            color = TextHi, fontSize = 14.sp, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                colors = ButtonDefaults.buttonColors(containerColor = Pink),
            ) { Text("Open accessibility settings") }
            TextButton(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("App info", color = Muted) }
        }
    }
}

@Composable
private fun HeroCard(a: Analytics) {
    val t = a.today
    val vsAvg = if (a.avgReelsPrev7 > 0) (t.reels - a.avgReelsPrev7) / a.avgReelsPrev7 else null
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3A1230), Color(0xFF2A1640), Color(0xFF1A1520))))
            .padding(20.dp)
    ) {
        Text("TODAY", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${t.reels}", fontSize = 64.sp, fontWeight = FontWeight.Black, color = TextHi, lineHeight = 64.sp)
            Text(" reels", fontSize = 20.sp, color = Muted, modifier = Modifier.padding(bottom = 10.dp))
        }
        Text("${fmtDur(t.ms)} of your life", color = TextHi, fontSize = 16.sp)
        val other = (a.todaySurfaceMs["feed"] ?: 0L) + (a.todaySurfaceMs["explore"] ?: 0L)
        if (other >= 1000) Text("+ ${fmtDur(other)} on Home & Explore", color = Muted, fontSize = 14.sp)
        if (vsAvg != null) {
            val up = vsAvg >= 0
            Text(
                "${if (up) "▲" else "▼"} ${pct(kotlin.math.abs(vsAvg))} vs your 7-day average (${a.avgReelsPrev7.roundToInt()})",
                color = if (up) Pink else Teal, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Tile(label: String, value: String, sub: String? = null, accent: Color = TextHi, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun TileRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)

@Composable
private fun TodayTiles(a: Analytics) {
    val t = a.today
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TileRow {
            Tile("IG opens", "${t.opens}", modifier = Modifier.weight(1f))
            Tile("Sessions", "${t.sessions}", modifier = Modifier.weight(1f))
            Tile("Avg / reel", "${one(a.todayAvgSec)}s", modifier = Modifier.weight(1f))
        }
        TileRow {
            Tile("Comments", "${a.todayComments}", accent = Amber, modifier = Modifier.weight(1f))
            Tile("Liked", "${a.todayLikes}", accent = Pink, modifier = Modifier.weight(1f))
            Tile("Shared", "${a.todayShares}", accent = Teal, modifier = Modifier.weight(1f))
        }
        a.todayLongestSession?.let {
            TileRow {
                Tile(
                    "Longest binge today", "${it.reels} reels",
                    sub = "${fmtDur(it.ms)} straight, started ${timeOf(it.start)}",
                    accent = Violet, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun timeOf(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a"))

@Composable
private fun RangePicker(range: Int, onPick: (Int) -> Unit) {
    val opts = listOf(7, 30, 90)
    Segmented(listOf("7 days", "30 days", "90 days"), opts.indexOf(range)) { onPick(opts[it]) }
}

@Composable
internal fun Segmented(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val sel = i == selected
            Text(
                label,
                textAlign = TextAlign.Center,
                color = if (sel) Color.White else Muted,
                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Pink else Color.Transparent)
                    .clickable { onPick(i) }.padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun TrendCard(a: Analytics, budget: Int?) {
    val n = a.days.size
    val labelEvery = when { n <= 7 -> 1; n <= 30 -> 5; else -> 15 }
    val fmt = DateTimeFormatter.ofPattern(if (n <= 7) "EEE" else "M/d")
    val labels = a.days.mapIndexed { i, d -> if ((n - 1 - i) % labelEvery == 0) d.date.format(fmt) else null }
    CardBox {
        val perDay = a.rangeReels.toDouble() / n
        SectionTitle("Reels per day", "${a.rangeReels} reels over $n days · ${one(perDay)}/day")
        BarChart(
            a.days.map { it.reels.toFloat() }, labels, highlight = n - 1, color = Pink,
        )
        Spacer(Modifier.height(18.dp))
        SectionTitle("Time per day", "${fmtDur(a.rangeMs)} total · ${fmtDur(a.rangeMs / n)}/day")
        BarChart(
            a.days.map { it.ms / 60_000f }, labels, highlight = n - 1, color = Violet,
            valueLabel = { "${it.roundToInt()}m" },
            refLine = budget?.let { it.toFloat() to "budget ${it}m" },
        )
        Spacer(Modifier.height(18.dp))
        SectionTitle("Instagram opens per day", "${one(a.opensPerDay)} opens/day on average")
        BarChart(a.days.map { it.opens.toFloat() }, labels, highlight = n - 1, color = Teal, height = 100.dp)
    }
}

@Composable
private fun WhenCard(a: Analytics) {
    CardBox {
        val peak = a.peakHour
        SectionTitle(
            "When you scroll",
            if (peak != null) "Peak hour: ${hourLong(peak)}–${hourLong((peak + 1) % 24)}" else "No data yet",
        )
        BarChart(
            a.hourly.map { it.toFloat() },
            (0 until 24).map { if (it % 6 == 0) hourLabel(it) else null },
            highlight = peak, color = Amber,
        )
        if (a.lateNightReels > 0) {
            Text(
                "${a.lateNightReels} reels between midnight and 5 AM. Go to sleep.",
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        val wd = a.weekdayAvg
        val peakDay = wd.indices.maxByOrNull { wd[it] }?.takeIf { wd[it] > 0 }
        SectionTitle(
            "By day of week",
            peakDay?.let { "Worst day: ${java.time.DayOfWeek.of(it + 1).name.lowercase().replaceFirstChar { c -> c.uppercase() }} (${wd[it].roundToInt()} avg)" },
        )
        BarChart(wd.map { it.toFloat() }, Stats.WEEKDAYS, highlight = peakDay, color = Amber, height = 110.dp)
    }
}

@Composable
private fun WatchCard(a: Analytics) {
    CardBox {
        SectionTitle("Attention span", "How long you give each reel before swiping")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat("Average", "${one(a.avgReelSec)}s", Modifier.weight(1f))
            MiniStat("Median", "${one(a.medianReelSec)}s", Modifier.weight(1f))
            MiniStat("Skipped <3s", pct(a.skipRate), Modifier.weight(1f))
            MiniStat("Watched >20s", pct(a.longWatchRate), Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        BarChart(
            a.durationBuckets.map { it.toFloat() },
            listOf("<2s", "2-5", "5-10", "10-20", "20-40", "40s+"),
            color = Teal, height = 110.dp,
        )
    }
}

@Composable
internal fun MiniStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun EngagementCard(a: Analytics) {
    CardBox {
        SectionTitle("Engagement", "Out of ${a.rangeReels} reels in this range")
        RateRow("Opened comments", a.commentRate, Amber, (a.commentRate * a.rangeReels).roundToInt())
        RateRow("Liked", a.likeRate, Pink, (a.likeRate * a.rangeReels).roundToInt())
        RateRow("Shared / sent", a.shareRate, Teal, (a.shareRate * a.rangeReels).roundToInt())
        if (a.commentRate > 0) {
            Text(
                "You read the comments on 1 in every ${(1 / a.commentRate).roundToInt()} reels.",
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RateRow(label: String, rate: Double, color: Color, count: Int) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Row {
            Text(label, color = TextHi, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("${pct(rate)}  ·  $count", color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        RateBar(rate.toFloat(), color)
    }
}

private val SURFACES = listOf(Triple("reels", "Reels", Pink), Triple("feed", "Home feed", Violet), Triple("explore", "Explore", Amber))

@Composable
private fun SurfacesCard(a: Analytics) {
    CardBox {
        val todayTotal = a.todaySurfaceMs.values.sum()
        SectionTitle("Where you scroll", "Today ${fmtDur(todayTotal)} across Reels, Home and Explore")
        val total = a.rangeSurfaceMs.values.sum().coerceAtLeast(1)
        for ((key, label, color) in SURFACES) {
            val ms = a.rangeSurfaceMs[key] ?: 0L
            val today = a.todaySurfaceMs[key] ?: 0L
            Column(Modifier.padding(bottom = 12.dp)) {
                Row {
                    Text(label, color = TextHi, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("today ${fmtDur(today)}  ·  ${a.rangeDays}d ${fmtDur(ms)}", color = Muted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                RateBar(ms.toFloat() / total, color)
            }
        }
    }
}

@Composable
private fun SessionsCard(a: Analytics) {
    CardBox {
        SectionTitle("Sessions", "A session is one continuous stretch in Reels")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("Sessions/day", one(a.sessionsPerDay), Modifier.weight(1f))
                MiniStat("Reels/session", one(a.avgReelsPerSession), Modifier.weight(1f))
                MiniStat("Avg length", fmtDur(a.avgSessionMs), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("50+ reel binges", "${a.bingeSessions}", Modifier.weight(1f))
                MiniStat(
                    "Avg break between",
                    a.avgGapBetweenSessionsMin?.let { if (it >= 60) "${one(it / 60)}h" else "${it.roundToInt()}m" } ?: "–",
                    Modifier.weight(1f),
                )
                MiniStat("Clean days", "${a.cleanDaysInRange}", Modifier.weight(1f))
            }
        }
        a.longestSession?.let {
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = CardHi)
            val d = Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault())
            Text(
                "Longest binge: ${it.reels} reels in ${fmtDur(it.ms)} on ${d.format(DateTimeFormatter.ofPattern("EEE MMM d 'at' h:mm a"))}",
                color = TextHi, fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun CreatorsCard(a: Analytics) {
    CardBox {
        SectionTitle("Who you watch most")
        a.topCreators.forEachIndexed { i, (name, n) ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text("${i + 1}.", color = Muted, modifier = Modifier.width(24.dp))
                Text("@$name", color = TextHi, modifier = Modifier.weight(1f))
                Text(if (n == 1) "1 reel" else "$n reels", color = Muted)
            }
        }
    }
}

@Composable
private fun DamageCard(a: Analytics) {
    CardBox {
        SectionTitle(
            "The damage",
            a.trackingSince?.let { "Since ${it.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}" },
        )
        val km = a.thumbMeters / 1000
        val lines = buildList {
            add("All-time reels" to "${a.allTimeReels}")
            add("All-time watch time" to fmtDur(a.allTimeMs))
            add("Thumb distance swiped" to if (km >= 1) "${one(km)} km" else "${a.thumbMeters.roundToInt()} m")
            add("At this week's pace" to "${a.yearlyHoursProjection.roundToInt()} h/year (${one(a.yearlyHoursProjection / 24)} days)")
            a.biggestDay?.let {
                add("Worst day (last ${maxOf(a.rangeDays, 8)}d)" to "${it.reels} reels on ${it.date.format(DateTimeFormatter.ofPattern("MMM d"))}")
            }
            add("Doomscroll streak" to "${a.streakDays} day${if (a.streakDays == 1) "" else "s"}")
        }
        lines.forEach { (k, v) ->
            Row(Modifier.padding(vertical = 5.dp)) {
                Text(k, color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(v, color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    prefs: Prefs,
    db: Db,
    status: com.doomscroller.tracker.TrackerStatus,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var adaptive by remember { mutableStateOf(prefs.manualBudgetMin == 0) }
    var manual by remember { mutableFloatStateOf(prefs.manualBudgetMin.takeIf { it > 0 }?.toFloat() ?: 45f) }
    var bedtime by remember { mutableIntStateOf(prefs.bedtimeHour) }
    var kickOn by remember { mutableStateOf(prefs.bedtimeKick) }
    var limitOn by remember { mutableStateOf(prefs.sessionLimit) }
    var limitTestMsg by remember { mutableStateOf<String?>(null) }
    var kickTestMsg by remember { mutableStateOf<String?>(null) }
    fun save() { prefs.manualBudgetMin = if (adaptive) 0 else manual.roundToInt() }
    var bubbleOn by remember { mutableStateOf(prefs.bubbleEnabled) }
    var wipeArmed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { save(); onDismiss() },
        confirmButton = {
            TextButton(onClick = { save(); onDismiss() }) { Text("Done", color = Pink) }
        },
        containerColor = Card,
        title = { Text("Settings", color = TextHi) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Adaptive daily budget", color = TextHi)
                        Text(
                            "Learns your normal for 7 days, starts ~10% under it, and adjusts every Monday.",
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = adaptive, onCheckedChange = { adaptive = it })
                }
                if (!adaptive) {
                    Text("Fixed budget: ${manual.roundToInt()} min/day", color = TextHi, modifier = Modifier.padding(top = 8.dp))
                    Slider(value = manual, onValueChange = { manual = (it / 5).roundToInt() * 5f }, valueRange = 5f..240f)
                }

                Text("Bedtime", color = TextHi, modifier = Modifier.padding(top = 12.dp))
                Text("Reels between bedtime and 5 AM cost you the night win.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                val bts = listOf(21, 22, 23, 0)
                Segmented(listOf("9 PM", "10 PM", "11 PM", "12 AM"), bts.indexOf(bedtime).coerceAtLeast(0)) {
                    bedtime = bts[it]; prefs.bedtimeHour = bts[it]
                }
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("8-minute limit", color = TextHi)
                        Text(
                            "After 8 straight minutes on Reels, Home or Explore, sends you to the home screen. " +
                                "Heads-up at 7.",
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = limitOn, onCheckedChange = { limitOn = it; prefs.sessionLimit = it })
                }
                TextButton(onClick = {
                    limitTestMsg = if (ReelsService.testLimit()) "Going Home in 3 seconds…" else "Turn on tracking first"
                }) { Text(limitTestMsg ?: "Test it (runs in 3s)", color = Muted) }
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bedtime kick", color = TextHi)
                        Text(
                            "12–5 AM: after a random 5–7 min of scrolling, closes Instagram, blacks out the " +
                                "screen for 2s and opens your alarms. Repeat kicks that night come faster.",
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = kickOn, onCheckedChange = { kickOn = it; prefs.bedtimeKick = it })
                }
                TextButton(onClick = {
                    kickTestMsg = if (ReelsService.testKick()) "Kicking in 3 seconds…" else "Turn on tracking first"
                }) { Text(kickTestMsg ?: "Test it (runs in 3s)", color = Muted) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Floating bubble", color = TextHi, modifier = Modifier.weight(1f))
                    Switch(checked = bubbleOn, onCheckedChange = { bubbleOn = it; prefs.bubbleEnabled = it })
                }
                TextButton(onClick = { prefs.resetBubblePosition() }) { Text("Reset bubble position", color = Muted) }

                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = CardHi)
                Text("Debug", color = TextHi, fontWeight = FontWeight.Bold)
                Text(
                    "If reels aren't being counted, open Reels, come back here and check what was detected. " +
                        "Instagram renames its view ids sometimes.",
                    color = Muted, fontSize = 12.sp,
                )
                val dbg = buildString {
                    appendLine("service: ${if (status.connected) "connected" else "NOT connected"}")
                    appendLine("instagram foreground: ${status.igForeground}")
                    appendLine("in reels: ${status.inReels} (via ${status.via})"); appendLine("surface: ${status.surface}")
                    appendLine("mode: ${if (status.indexMode) "pager index (${status.index})" else "signature"}")
                    appendLine("signature: ${status.signature.take(60)}")
                    appendLine("nodes scanned: ${status.nodeCount}")
                    append("ids: ${status.ids.joinToString(", ")}")
                }
                Text(
                    dbg, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(Bg).padding(8.dp),
                )
                TextButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("doomscroller debug", dbg))
                }) { Text("Copy debug info", color = Muted) }

                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = CardHi)
                TextButton(onClick = {
                    if (wipeArmed) { db.write { wipe() }; prefs.resetProgress(); wipeArmed = false } else wipeArmed = true
                }) {
                    Text(if (wipeArmed) "Tap again to delete ALL data" else "Delete all data", color = Pink)
                }
            }
        },
    )
}
