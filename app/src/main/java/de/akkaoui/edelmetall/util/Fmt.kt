package de.akkaoui.edelmetall.util

import de.akkaoui.edelmetall.data.GRAMS_PER_OZ
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

object Fmt {
    private val locale: Locale = Locale.GERMANY
    private val currency: NumberFormat = NumberFormat.getCurrencyInstance(Locale.GERMANY)
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    const val HIDDEN = "•••••"

    fun eur(v: Double, hidden: Boolean = false): String = if (hidden) "$HIDDEN €" else currency.format(v)

    fun signedEur(v: Double, hidden: Boolean = false): String =
        if (hidden) "$HIDDEN €" else (if (v > 0) "+" else "") + currency.format(v)

    fun pct(v: Double): String = String.format(locale, "%+.2f %%", v)

    fun num(v: Double, decimals: Int = 2): String = String.format(locale, "%,.${decimals}f", v)

    /** Gewicht pro Stück */
    fun grams(g: Double): String = when {
        abs(g - GRAMS_PER_OZ) < 0.001 -> "1 oz (31,10 g)"
        g >= 1000 && g % 1000 == 0.0 -> "${(g / 1000).toInt()} kg"
        g % 1.0 == 0.0 -> "${g.toInt()} g"
        else -> String.format(locale, "%.3f g", g)
    }

    /** Gesamtgewicht */
    fun gramsTotal(g: Double, hidden: Boolean = false): String = when {
        hidden -> "$HIDDEN g"
        g >= 1000 -> String.format(locale, "%,.3f kg", g / 1000)
        else -> String.format(locale, "%,.2f g", g)
    }

    fun date(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dateFmt)

    fun dateTime(epochSec: Long): String =
        Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).format(dateTimeFmt)

    fun time(epochSec: Long): String =
        Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).format(timeFmt)

    fun dateOfSec(epochSec: Long): String =
        Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).toLocalDate().format(dateFmt)

    /** Akzeptiert "1.234,56", "1234,56" und "1234.56". */
    fun parse(s: String): Double? {
        val t = s.trim().replace(" ", "").replace("€", "")
        if (t.isEmpty()) return null
        val normalized = if (t.contains(',')) t.replace(".", "").replace(',', '.') else t
        return normalized.toDoubleOrNull()
    }

    /** Zahl für ein Eingabefeld, ohne Tausenderpunkte. */
    fun input(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString()
        else String.format(locale, "%.4f", v).trimEnd('0').trimEnd(',')
}
