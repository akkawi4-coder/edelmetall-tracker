package de.akkaoui.edelmetall.util

import de.akkaoui.edelmetall.data.Fx
import de.akkaoui.edelmetall.data.GRAMS_PER_OZ
import de.akkaoui.edelmetall.data.Holding
import de.akkaoui.edelmetall.data.Metal
import de.akkaoui.edelmetall.data.Quote
import de.akkaoui.edelmetall.data.Settings
import de.akkaoui.edelmetall.data.ValueMode
import java.time.LocalDate

object Valuation {

    /** EUR je Gramm Feingewicht nach gewähltem Bewertungsmodus, null wenn noch kein Preis da ist. */
    fun pricePerGram(metal: Metal, quotes: Map<Metal, Quote>, s: Settings): Double? {
        val q = quotes[metal] ?: return null
        val spot = q.pricePerOz / GRAMS_PER_OZ
        return if (s.valueMode == ValueMode.BUYBACK) spot * (1 - s.buybackDiscountPct / 100.0) else spot
    }

    fun value(h: Holding, quotes: Map<Metal, Quote>, s: Settings): Double? =
        pricePerGram(h.metal, quotes, s)?.let { it * h.totalFineGrams }

    /** Kaufpreis der Position in EUR (umgerechnet zum aktuellen Kurs, falls in anderer Währung erfasst). */
    fun costEur(h: Holding): Double = Fx.toEur(h.purchaseTotal, h.currency)

    data class Summary(
        val value: Double,
        val cost: Double,
        val missingPrice: Boolean,
    ) {
        val profit: Double get() = value - cost
        val profitPct: Double? get() = if (cost > 0) profit / cost * 100.0 else null
    }

    fun summary(holdings: List<Holding>, quotes: Map<Metal, Quote>, s: Settings): Summary {
        var value = 0.0
        var cost = 0.0
        var missing = false
        for (h in holdings) {
            val v = value(h, quotes, s)
            if (v == null) missing = true else value += v
            cost += costEur(h)
        }
        return Summary(value, cost, missing)
    }

    /**
     * Private Veräußerungsgeschäfte (§ 23 EStG): Gewinne aus physischem Edelmetall sind steuerfrei,
     * wenn zwischen Kauf und Verkauf mehr als ein Jahr liegt. Keine Steuerberatung.
     */
    fun taxFreeFrom(purchaseEpochDay: Long): LocalDate = LocalDate.ofEpochDay(purchaseEpochDay).plusYears(1).plusDays(1)

    fun isTaxFree(purchaseEpochDay: Long, today: LocalDate = LocalDate.now()): Boolean =
        !today.isBefore(taxFreeFrom(purchaseEpochDay))
}
