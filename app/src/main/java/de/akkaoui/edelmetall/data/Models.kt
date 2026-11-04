package de.akkaoui.edelmetall.data

const val GRAMS_PER_OZ = 31.1034768

enum class Metal(val symbol: String, val label: String, val color: Long) {
    GOLD("XAU", "Gold", 0xFFD4AF37),
    SILVER("XAG", "Silber", 0xFFC9CCD3),
    PLATINUM("XPT", "Platin", 0xFF9FB3C8),
    PALLADIUM("XPD", "Palladium", 0xFFB9B1A3);

    companion object {
        fun fromSymbol(s: String?): Metal? = entries.firstOrNull { it.symbol == s }
    }
}

enum class ProductForm(val label: String) { BAR("Barren"), COIN("Münze") }

data class Product(
    val id: String,
    val name: String,
    val manufacturer: String,
    val metal: Metal,
    val form: ProductForm,
    /** Feingewicht pro Stück in Gramm */
    val fineGrams: Double,
    val fineness: String,
)

/** Preis je Feinunze (31,1035 g) in EUR */
data class Quote(val metal: Metal, val pricePerOz: Double, val updatedAt: Long)

/** t = Unix-Sekunden, p = EUR je Feinunze */
data class PricePoint(val t: Long, val p: Double)

data class Holding(
    val id: String,
    val productId: String?,
    val name: String,
    val metal: Metal,
    val form: ProductForm,
    val fineGramsPerUnit: Double,
    val quantity: Double,
    /** Kaufpreis gesamt für die Position in EUR */
    val purchaseTotal: Double,
    val purchaseEpochDay: Long,
    val note: String,
) {
    val totalFineGrams: Double get() = fineGramsPerUnit * quantity
}

enum class ValueMode(val label: String) { SPOT("Spotpreis"), BUYBACK("Ankaufspreis") }

data class Settings(
    val lockEnabled: Boolean = true,
    val discreet: Boolean = false,
    val valueMode: ValueMode = ValueMode.SPOT,
    /** Abschlag auf den Spotpreis für die Ankaufs-Schätzung in Prozent */
    val buybackDiscountPct: Double = 3.0,
    /** true = Einkaufswert als große Hauptzahl anzeigen */
    val purchaseHeadline: Boolean = false,
)

enum class ChartRange(val label: String, val seconds: Long?) {
    H1("1 Std", 3_600L),
    D1("1 T", 86_400L),
    W1("1 W", 7 * 86_400L),
    M1("1 M", 30 * 86_400L),
    M3("3 M", 91 * 86_400L),
    M6("6 M", 182 * 86_400L),
    Y1("1 J", 365 * 86_400L),
    Y5("5 J", 5 * 365 * 86_400L),
    MAX("Max", null),
}
