package de.akkaoui.edelmetall.ui.components

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.akkaoui.edelmetall.data.GRAMS_PER_OZ
import de.akkaoui.edelmetall.data.Metal
import de.akkaoui.edelmetall.data.Product
import de.akkaoui.edelmetall.data.ProductForm
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Produktbild: eigenes Foto, sonst eine detailliert gezeichnete Darstellung mit Prägung
 * (Hersteller, Gewicht, Feinheit). Keine fremden Produktfotos – damit gibt es keine Bildrechte-Probleme.
 */
@Composable
fun ProductImage(
    metal: Metal,
    form: ProductForm,
    size: Dp,
    fineGrams: Double? = null,
    maker: String? = null,
    name: String? = null,
    fineness: String? = null,
    photo: File? = null,
) {
    if (photo != null && photo.exists()) {
        val bmp = remember(photo.path, photo.lastModified()) {
            runCatching { BitmapFactory.decodeFile(photo.path)?.asImageBitmap() }.getOrNull()
        }
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.18f))
            )
            return
        }
    }
    val detailed = size >= 72.dp
    val showWeight = size >= 36.dp
    Canvas(Modifier.size(size)) {
        if (form == ProductForm.COIN) {
            drawCoin(metal, fineGrams, name, fineness, detailed, showWeight)
        } else {
            drawBar(metal, fineGrams, maker, fineness, detailed, showWeight)
        }
    }
}

@Composable
fun ProductImage(p: Product, size: Dp, photo: File?) =
    ProductImage(p.metal, p.form, size, p.fineGrams, p.manufacturer, p.name, p.fineness, photo)

/** Kurzes Gewicht für die Prägung: „1 oz“, „1/10 oz“, „5 g“, „1 kg“. */
fun weightShort(g: Double?): String {
    if (g == null || g <= 0) return ""
    val oz = g / GRAMS_PER_OZ
    val fractions = listOf(1.0 to "1 oz", 0.5 to "1/2 oz", 0.25 to "1/4 oz", 0.1 to "1/10 oz", 0.04 to "1/25 oz",
        0.05 to "1/20 oz", 2.0 to "2 oz", 5.0 to "5 oz", 10.0 to "10 oz", 100.0 to "100 oz")
    fractions.firstOrNull { abs(oz - it.first) < 0.0005 * it.first + 0.0001 }?.let { return it.second }
    return when {
        g >= 1000 && g % 1000 == 0.0 -> "${(g / 1000).toInt()} kg"
        g % 1.0 == 0.0 -> "${g.toInt()} g"
        else -> String.format(Locale.GERMANY, "%.2f g", g).replace(",00", "")
    }
}

private fun metalWord(m: Metal) = when (m) {
    Metal.GOLD -> "FEINGOLD"
    Metal.SILVER -> "FEINSILBER"
    Metal.PLATINUM -> "FEINPLATIN"
    Metal.PALLADIUM -> "FEINPALLADIUM"
}

private fun chemSymbol(m: Metal) = when (m) {
    Metal.GOLD -> "Au"
    Metal.SILVER -> "Ag"
    Metal.PLATINUM -> "Pt"
    Metal.PALLADIUM -> "Pd"
}

/** Münzname ohne Gewichts- und Metallzusatz, z. B. „Maple Leaf 1 Unze Gold“ → „MAPLE LEAF“. */
private fun coinTitle(name: String?): String {
    if (name.isNullOrBlank()) return ""
    return name
        .replace(Regex("\\s+\\d+(/\\d+)?\\s*(Unze|Unzen|g|kg)\\b.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+(Gold|Silber|Platin|Palladium)$", RegexOption.IGNORE_CASE), "")
        .trim()
        .uppercase(Locale.GERMANY)
}

private class Tones(metal: Metal) {
    val base = Color(metal.color)
    val light = lerp(base, Color.White, 0.55f)
    val mid = lerp(base, Color.White, 0.18f)
    val dark = lerp(base, Color.Black, 0.38f)
    val deep = lerp(base, Color.Black, 0.62f)
    val ink = lerp(base, Color.Black, 0.58f)
}

private fun textPaint(color: Color, sizePx: Float, bold: Boolean = true, spacing: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color.toArgb()
    textSize = sizePx
    typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    textAlign = Paint.Align.CENTER
    letterSpacing = spacing
}

/** Geprägter Text: heller Lichtrand unten rechts, dunkle Schrift darüber. */
private fun DrawScope.embossed(text: String, cx: Float, baseline: Float, sizePx: Float, t: Tones, maxWidth: Float, spacing: Float = 0f, bold: Boolean = true) {
    if (text.isBlank()) return
    val ink = textPaint(t.ink, sizePx, bold, spacing)
    val w = ink.measureText(text)
    if (w > maxWidth && w > 0) ink.textSize = sizePx * maxWidth / w
    val hi = Paint(ink).apply { color = t.light.copy(alpha = 0.85f).toArgb() }
    val off = (sizePx * 0.06f).coerceAtLeast(0.6f)
    drawIntoCanvas { c ->
        c.nativeCanvas.drawText(text, cx + off, baseline + off, hi)
        c.nativeCanvas.drawText(text, cx, baseline, ink)
    }
}

// ------------------------------------------------------------------ Barren

private fun DrawScope.drawBar(metal: Metal, grams: Double?, maker: String?, fineness: String?, detailed: Boolean, showWeight: Boolean) {
    val t = Tones(metal)
    val s = size.minDimension
    val cast = (grams ?: 0.0) >= 250.0
    if (cast) {
        drawCastBar(t, s, grams, maker, fineness, metal, detailed, showWeight)
        return
    }
    // Geprägter (Minted) Barren im Hochformat
    val bw = s * 0.64f
    val bh = s * 0.92f
    val left = (size.width - bw) / 2
    val top = (size.height - bh) / 2
    val r = CornerRadius(s * 0.07f)

    drawRoundRect(Color.Black.copy(alpha = 0.22f), Offset(left + s * 0.025f, top + s * 0.03f), Size(bw, bh), r)
    drawRoundRect(
        Brush.linearGradient(listOf(t.light, t.base, t.dark), Offset(left, top), Offset(left + bw, top + bh)),
        Offset(left, top), Size(bw, bh), r
    )
    // umlaufende Fase
    val inset1 = s * 0.035f
    drawRoundRect(
        Brush.linearGradient(listOf(t.dark, t.mid, t.light), Offset(left, top), Offset(left + bw, top + bh)),
        Offset(left + inset1, top + inset1), Size(bw - 2 * inset1, bh - 2 * inset1), CornerRadius(s * 0.05f)
    )
    // Prägefläche
    val inset2 = s * 0.06f
    val fx = left + inset2
    val fy = top + inset2
    val fw = bw - 2 * inset2
    val fh = bh - 2 * inset2
    drawRoundRect(
        Brush.verticalGradient(listOf(t.mid, t.base, lerp(t.base, t.dark, 0.35f)), fy, fy + fh),
        Offset(fx, fy), Size(fw, fh), CornerRadius(s * 0.035f)
    )
    if (detailed) {
        drawRoundRect(
            t.dark.copy(alpha = 0.55f), Offset(fx + s * 0.025f, fy + s * 0.025f),
            Size(fw - s * 0.05f, fh - s * 0.05f), CornerRadius(s * 0.025f), style = Stroke(s * 0.006f)
        )
    }
    sheen(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(left, top, left + bw, top + bh, r)) }, s)

    val cx = size.width / 2
    val maxW = fw * 0.88f
    if (detailed) {
        embossed((maker ?: "").uppercase(Locale.GERMANY), cx, fy + fh * 0.17f, s * 0.052f, t, maxW, 0.06f)
        // kleines Herstellerzeichen
        drawCircle(t.dark.copy(alpha = 0.6f), s * 0.05f, Offset(cx, fy + fh * 0.30f), style = Stroke(s * 0.008f))
        embossed(chemSymbol(metal), cx, fy + fh * 0.30f + s * 0.018f, s * 0.045f, t, maxW)
        embossed(weightShort(grams), cx, fy + fh * 0.55f, s * 0.13f, t, maxW)
        embossed(metalWord(metal), cx, fy + fh * 0.71f, s * 0.05f, t, maxW, 0.08f)
        embossed(fineness ?: "", cx, fy + fh * 0.84f, s * 0.07f, t, maxW)
        embossed("ASSAYER · MELTER", cx, fy + fh * 0.94f, s * 0.026f, t, maxW, 0.05f, bold = false)
    } else if (showWeight) {
        embossed(weightShort(grams).replace(" ", ""), cx, fy + fh * 0.58f, s * 0.17f, t, maxW)
    }
}

private fun DrawScope.drawCastBar(t: Tones, s: Float, grams: Double?, maker: String?, fineness: String?, metal: Metal, detailed: Boolean, showWeight: Boolean) {
    // Gegossener Barren: Trapez in leichter Draufsicht
    val w = s * 0.96f
    val h = s * 0.62f
    val l = (size.width - w) / 2
    val tp = (size.height - h) / 2 + s * 0.02f
    val inset = w * 0.12f
    val outer = Path().apply {
        moveTo(l, tp + h); lineTo(l + inset, tp); lineTo(l + w - inset, tp); lineTo(l + w, tp + h); close()
    }
    val shadow = Path().apply {
        moveTo(l + s * 0.02f, tp + h + s * 0.03f); lineTo(l + w + s * 0.02f, tp + h + s * 0.03f)
        lineTo(l + w - inset, tp + s * 0.03f); lineTo(l + inset, tp + s * 0.03f); close()
    }
    drawPath(shadow, Color.Black.copy(alpha = 0.22f))
    drawPath(outer, Brush.verticalGradient(listOf(t.dark, t.deep), tp, tp + h))
    // Oberseite
    val fx = l + inset * 0.85f
    val fy = tp + h * 0.04f
    val fw = w - inset * 1.7f
    val fh = h * 0.74f
    val topFace = Path().apply {
        moveTo(fx, fy + fh); lineTo(fx + fw * 0.06f, fy); lineTo(fx + fw * 0.94f, fy); lineTo(fx + fw, fy + fh); close()
    }
    drawPath(topFace, Brush.linearGradient(listOf(t.light, t.base, t.dark), Offset(fx, fy), Offset(fx + fw, fy + fh)))
    // Gussstruktur
    if (detailed) {
        for (i in 0 until 7) {
            val yy = fy + fh * (0.12f + i * 0.13f)
            drawLine(t.light.copy(alpha = 0.18f), Offset(fx + fw * 0.10f, yy), Offset(fx + fw * 0.90f, yy + fh * 0.02f), strokeWidth = s * 0.004f)
        }
    }
    sheen(topFace, s)
    val cx = size.width / 2
    val maxW = fw * 0.82f
    if (detailed) {
        embossed((maker ?: "").uppercase(Locale.GERMANY), cx, fy + fh * 0.22f, s * 0.05f, t, maxW, 0.06f)
        embossed(weightShort(grams), cx, fy + fh * 0.55f, s * 0.12f, t, maxW)
        embossed("${metalWord(metal)} ${fineness ?: ""}".trim(), cx, fy + fh * 0.80f, s * 0.05f, t, maxW, 0.05f)
    } else if (showWeight) {
        embossed(weightShort(grams).replace(" ", ""), cx, fy + fh * 0.66f, s * 0.2f, t, maxW)
    }
}

/** Diagonaler Lichtreflex über einer Fläche. */
private fun DrawScope.sheen(shape: Path, s: Float) {
    clipPath(shape) {
        val band = Path().apply {
            moveTo(size.width * 0.05f, 0f); lineTo(size.width * 0.38f, 0f)
            lineTo(size.width * 0.05f, size.height * 0.75f); lineTo(-size.width * 0.28f, size.height * 0.75f); close()
        }
        drawPath(band, Brush.linearGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0f)),
            Offset(0f, 0f), Offset(s * 0.5f, s * 0.5f)))
    }
}

// ------------------------------------------------------------------ Münzen

private fun DrawScope.drawCoin(metal: Metal, grams: Double?, name: String?, fineness: String?, detailed: Boolean, showWeight: Boolean) {
    val t = Tones(metal)
    val s = size.minDimension
    val c = Offset(size.width / 2, size.height / 2)
    val rOut = s * 0.47f

    drawCircle(Color.Black.copy(alpha = 0.22f), rOut, c + Offset(s * 0.02f, s * 0.025f))
    // Rand mit Riffelung
    drawCircle(Brush.linearGradient(listOf(t.light, t.base, t.deep), c - Offset(rOut, rOut), c + Offset(rOut, rOut)), rOut, c)
    if (detailed) {
        val n = 150
        for (i in 0 until n) {
            val a = (2 * PI * i / n).toFloat()
            val p1 = c + Offset(cos(a) * rOut * 0.965f, sin(a) * rOut * 0.965f)
            val p2 = c + Offset(cos(a) * rOut, sin(a) * rOut)
            drawLine(t.deep.copy(alpha = 0.55f), p1, p2, strokeWidth = s * 0.0045f)
        }
    }
    // erhabener Randring
    val rRim = rOut * 0.93f
    drawCircle(Brush.linearGradient(listOf(t.dark, t.light, t.mid), c - Offset(rRim, rRim), c + Offset(rRim, rRim)), rRim, c)
    // Münzfeld
    val rField = rOut * 0.85f
    drawCircle(
        Brush.radialGradient(listOf(t.light, t.base, lerp(t.base, t.dark, 0.45f)), c - Offset(rField * 0.3f, rField * 0.35f), rField * 1.5f),
        rField, c
    )
    drawCircle(t.dark.copy(alpha = 0.5f), rField, c, style = Stroke(s * 0.008f))

    if (detailed) {
        // Schriftring
        val rInner = rOut * 0.62f
        drawCircle(t.dark.copy(alpha = 0.45f), rInner, c, style = Stroke(s * 0.006f))
        // Perlkreis
        val beads = 48
        for (i in 0 until beads) {
            val a = (2 * PI * i / beads).toFloat()
            drawCircle(t.dark.copy(alpha = 0.35f), s * 0.005f, c + Offset(cos(a) * rInner * 0.93f, sin(a) * rInner * 0.93f))
        }
        val ringText = s * 0.058f
        val title = coinTitle(name)
        arcText(title, c, rInner + s * 0.025f, top = true, sizePx = ringText, t = t)
        val bottom = listOf(weightShort(grams).uppercase(Locale.GERMANY), metalWord(metal), fineness ?: "")
            .filter { it.isNotBlank() }.joinToString(" · ")
        arcText(bottom, c, rField - s * 0.035f, top = false, sizePx = ringText * 0.85f, t = t)

        // Mittelfeld: Gewicht und chemisches Zeichen
        embossed(chemSymbol(metal), c.x, c.y - rInner * 0.30f, s * 0.07f, t, rInner * 1.4f)
        embossed(weightShort(grams), c.x, c.y + rInner * 0.22f, s * 0.12f, t, rInner * 1.6f)
        // feine Sternchen links und rechts
        embossed("✦", c.x - rInner * 0.72f, c.y + s * 0.02f, s * 0.045f, t, s)
        embossed("✦", c.x + rInner * 0.72f, c.y + s * 0.02f, s * 0.045f, t, s)
    } else if (showWeight) {
        embossed(weightShort(grams).replace(" ", ""), c.x, c.y + s * 0.07f, s * 0.2f, t, rField * 1.7f)
    }
    sheen(Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, rOut)) }, s)
}

/** Text entlang eines Kreisbogens, oben oder unten mittig. */
private fun DrawScope.arcText(text: String, c: Offset, radius: Float, top: Boolean, sizePx: Float, t: Tones) {
    if (text.isBlank()) return
    val ink = textPaint(t.ink, sizePx, true, 0.08f).apply { textAlign = Paint.Align.LEFT }
    val arcLen = (PI * radius).toFloat() * 0.92f
    val w = ink.measureText(text)
    if (w > arcLen) ink.textSize = sizePx * arcLen / w
    val tw = ink.measureText(text)
    val hi = Paint(ink).apply { color = t.light.copy(alpha = 0.8f).toArgb() }
    val path = android.graphics.Path().apply {
        val oval = RectF(c.x - radius, c.y - radius, c.x + radius, c.y + radius)
        if (top) addArc(oval, 180f, 180f) else addArc(oval, 180f, -180f)
    }
    val hOff = ((PI * radius).toFloat() - tw) / 2
    val vOff = 0f
    drawIntoCanvas { cv ->
        cv.nativeCanvas.drawTextOnPath(text, path, hOff + 0.6f, vOff + 0.6f, hi)
        cv.nativeCanvas.drawTextOnPath(text, path, hOff, vOff, ink)
    }
}
