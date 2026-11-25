package com.doomscroller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal bar chart. Bars are dimmed except [highlight]; [refLine] draws a dashed horizontal line
 * (e.g. a daily goal or average) with its label.
 */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String?>,
    modifier: Modifier = Modifier,
    color: Color = Pink,
    highlight: Int? = null,
    height: Dp = 140.dp,
    refLine: Pair<Float, String>? = null,
    valueLabel: (Float) -> String = { it.toInt().toString() },
) {
    val tm = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Muted, fontSize = 10.sp)
    val valueStyle = TextStyle(color = TextHi, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val n = values.size
        if (n == 0) return@Canvas
        val labelH = 16.dp.toPx()
        val topPad = 16.dp.toPx()
        val chartH = size.height - labelH - topPad
        val slot = size.width / n
        val barW = (slot * 0.64f).coerceAtLeast(1.5f)
        val max = maxOf(values.maxOrNull() ?: 0f, refLine?.first ?: 0f).coerceAtLeast(1f)

        // baseline
        drawLine(Muted.copy(alpha = 0.25f), Offset(0f, topPad + chartH), Offset(size.width, topPad + chartH), 1f)

        values.forEachIndexed { i, v ->
            val h = chartH * (v / max)
            val x = i * slot + (slot - barW) / 2
            val c = if (highlight == null || i == highlight) color else color.copy(alpha = 0.45f)
            if (v > 0) {
                drawRoundRect(
                    c,
                    topLeft = Offset(x, topPad + chartH - h.coerceAtLeast(2f)),
                    size = Size(barW, h.coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(minOf(barW / 3, 6.dp.toPx())),
                )
            }
            labels.getOrNull(i)?.let { l ->
                val m = tm.measure(l, labelStyle)
                drawText(m, topLeft = Offset(i * slot + slot / 2 - m.size.width / 2f, size.height - labelH + 3.dp.toPx()))
            }
        }

        // value on top of highlighted (or tallest) bar
        val hi = highlight ?: values.indices.maxByOrNull { values[it] }
        if (hi != null && values[hi] > 0) {
            val v = values[hi]
            val m = tm.measure(valueLabel(v), valueStyle)
            val cx = (hi * slot + slot / 2 - m.size.width / 2f).coerceIn(0f, size.width - m.size.width)
            drawText(m, topLeft = Offset(cx, topPad + chartH - chartH * (v / max) - m.size.height - 1.dp.toPx()))
        }

        refLine?.let { (v, label) ->
            val y = topPad + chartH - chartH * (v / max)
            drawLine(
                Amber.copy(alpha = 0.8f), Offset(0f, y), Offset(size.width, y), 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
            val m = tm.measure(label, labelStyle.copy(color = Amber))
            drawText(m, topLeft = Offset(size.width - m.size.width, (y - m.size.height).coerceAtLeast(0f)))
        }
    }
}

/** Horizontal progress bar used for rates. */
@Composable
fun RateBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Muted.copy(alpha = 0.18f), cornerRadius = r)
        val w = size.width * fraction.coerceIn(0f, 1f)
        if (w > 0) drawRoundRect(color, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r)
    }
}
