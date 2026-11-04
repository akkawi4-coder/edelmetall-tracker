package de.akkaoui.edelmetall.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.akkaoui.edelmetall.data.Metal
import de.akkaoui.edelmetall.data.PricePoint
import de.akkaoui.edelmetall.data.ProductForm
import kotlin.math.abs

/** Gezeichnetes Produktsymbol (Barren oder Münze) in der Metallfarbe – keine fremden Produktfotos. */
@Composable
fun ProductIcon(metal: Metal, form: ProductForm, size: Dp = 40.dp) {
    val base = Color(metal.color)
    val light = lerp(base, Color.White, 0.45f)
    val dark = lerp(base, Color.Black, 0.35f)
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        if (form == ProductForm.COIN) {
            val c = Offset(w / 2, h / 2)
            drawCircle(Brush.linearGradient(listOf(light, base, dark), Offset(0f, 0f), Offset(w, h)), w * 0.46f, c)
            drawCircle(dark, w * 0.46f, c, style = Stroke(w * 0.05f))
            drawCircle(dark.copy(alpha = 0.55f), w * 0.33f, c, style = Stroke(w * 0.025f))
        } else {
            val body = Path().apply {
                moveTo(w * 0.06f, h * 0.78f)
                lineTo(w * 0.20f, h * 0.34f)
                lineTo(w * 0.80f, h * 0.34f)
                lineTo(w * 0.94f, h * 0.78f)
                close()
            }
            drawPath(body, Brush.verticalGradient(listOf(base, dark), startY = h * 0.34f, endY = h * 0.78f))
            val top = Path().apply {
                moveTo(w * 0.20f, h * 0.34f)
                lineTo(w * 0.26f, h * 0.22f)
                lineTo(w * 0.74f, h * 0.22f)
                lineTo(w * 0.80f, h * 0.34f)
                close()
            }
            drawPath(top, light)
            drawRoundRect(
                color = light.copy(alpha = 0.35f),
                topLeft = Offset(w * 0.32f, h * 0.46f),
                size = Size(w * 0.36f, h * 0.18f),
                cornerRadius = CornerRadius(w * 0.03f)
            )
        }
    }
}

private fun downsample(points: List<PricePoint>, max: Int): List<PricePoint> {
    if (points.size <= max) return points
    val step = points.size.toDouble() / max
    val out = ArrayList<PricePoint>(max + 1)
    var i = 0.0
    while (i < points.size) {
        out += points[i.toInt()]
        i += step
    }
    if (out.last() != points.last()) out += points.last()
    return out
}

/** Liniendiagramm mit Verlaufsfüllung. Finger auflegen oder ziehen zeigt den Wert an dieser Stelle. */
@Composable
fun PriceChart(
    points: List<PricePoint>,
    lineColor: Color,
    selected: PricePoint?,
    onSelect: (PricePoint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pts = remember(points) { downsample(points, 500) }
    if (pts.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Für diesen Zeitraum liegen noch keine Kursdaten vor.\nDie App zeichnet die Kurse ab jetzt automatisch auf.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val minT = pts.first().t
    val maxT = pts.last().t
    val minP = pts.minOf { it.p }
    val maxP = pts.maxOf { it.p }
    val tSpan = (maxT - minT).coerceAtLeast(1L).toFloat()
    val pSpan = (maxP - minP).let { if (it > 0) it else 1.0 }

    fun nearest(x: Float, width: Int): PricePoint {
        val frac = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        val target = minT + (frac * tSpan).toLong()
        return pts.minByOrNull { abs(it.t - target) } ?: pts.last()
    }

    Canvas(
        modifier
            .pointerInput(pts) {
                detectTapGestures(onPress = { pos ->
                    currentOnSelect(nearest(pos.x, size.width))
                    tryAwaitRelease()
                    currentOnSelect(null)
                })
            }
            .pointerInput(pts) {
                detectHorizontalDragGestures(
                    onDragStart = { pos -> currentOnSelect(nearest(pos.x, size.width)) },
                    onDragEnd = { currentOnSelect(null) },
                    onDragCancel = { currentOnSelect(null) },
                ) { change, _ -> currentOnSelect(nearest(change.position.x, size.width)) }
            }
    ) {
        val w = size.width
        val h = size.height
        val pad = h * 0.08f
        fun xOf(t: Long): Float = (t - minT).toFloat() / tSpan * w
        fun yOf(p: Double): Float = pad + (1f - ((p - minP) / pSpan).toFloat()) * (h - 2 * pad)

        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        val line = Path()
        val fill = Path()
        pts.forEachIndexed { i, pt ->
            val x = xOf(pt.t)
            val y = yOf(pt.p)
            if (i == 0) {
                line.moveTo(x, y)
                fill.moveTo(x, h)
                fill.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(xOf(maxT), h)
        fill.close()

        drawPath(fill, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.30f), Color.Transparent)))
        drawPath(line, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        selected?.let { s ->
            val x = xOf(s.t)
            drawLine(lineColor.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, h), strokeWidth = 1.dp.toPx())
            drawCircle(lineColor, 5.dp.toPx(), Offset(x, yOf(s.p)))
            drawCircle(Color.Black.copy(alpha = 0.5f), 2.dp.toPx(), Offset(x, yOf(s.p)))
        }
    }
}
