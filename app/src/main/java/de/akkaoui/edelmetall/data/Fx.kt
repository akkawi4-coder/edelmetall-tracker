package de.akkaoui.edelmetall.data

/**
 * Währungsumrechnung. Intern rechnet die App in EUR; angezeigt wird in der gewählten Währung.
 * Kurse = Einheiten Fremdwährung je 1 EUR.
 *  - live: aus gold-api.com abgeleitet (Goldpreis in Fremdwährung / Goldpreis in EUR)
 *  - history: EZB-Referenzkurse aus der mitgelieferten Kurshistorie
 */
object Fx {
    @Volatile var display: Currency = Currency.EUR
    @Volatile var live: Map<Currency, Double> = emptyMap()
    @Volatile var history: Map<Currency, List<Pair<Long, Double>>> = emptyMap()

    fun rate(c: Currency = display): Double {
        if (c == Currency.EUR) return 1.0
        return live[c] ?: history[c]?.lastOrNull()?.second ?: 1.0
    }

    /** true, wenn für die Währung (noch) kein Kurs vorliegt */
    fun missing(c: Currency = display): Boolean = c != Currency.EUR && live[c] == null && history[c].isNullOrEmpty()

    /** Kurs an einem Tag (Epochentag); nach dem Ende der Historie gilt der aktuelle Kurs. */
    fun rateAt(c: Currency, epochDay: Long): Double {
        if (c == Currency.EUR) return 1.0
        val list = history[c]
        if (list.isNullOrEmpty() || epochDay > list.last().first + 3) return rate(c)
        var lo = 0
        var hi = list.size - 1
        if (epochDay <= list[0].first) return list[0].second
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (list[mid].first <= epochDay) lo = mid else hi = mid - 1
        }
        return list[lo].second
    }

    fun fromEur(v: Double, c: Currency = display): Double = v * rate(c)
    fun toEur(v: Double, c: Currency): Double = v / rate(c)
}
