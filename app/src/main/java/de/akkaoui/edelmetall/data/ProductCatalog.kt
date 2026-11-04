package de.akkaoui.edelmetall.data

import java.util.Locale
import kotlin.math.abs

/**
 * Lokal mitgelieferte Produktdatenbank der gängigsten Barren und Münzen.
 * Gespeichert wird das FEINgewicht (reiner Metallanteil) pro Stück.
 */
object ProductCatalog {

    val all: List<Product> by lazy { build() }

    fun byId(id: String?): Product? = if (id == null) null else all.firstOrNull { it.id == id }

    fun search(query: String, metal: Metal?): List<Product> {
        val tokens = query.lowercase(Locale.GERMANY).split(" ").filter { it.isNotBlank() }
        return all.filter { p ->
            if (metal != null && p.metal != metal) return@filter false
            if (tokens.isEmpty()) return@filter true
            val hay = "${p.name} ${p.manufacturer} ${p.metal.label} ${p.form.label}".lowercase(Locale.GERMANY)
            val hayNoSpace = hay.replace(" ", "")
            tokens.all { hay.contains(it) || hayNoSpace.contains(it) }
        }
    }

    fun sizeLabel(g: Double): String = when {
        abs(g - GRAMS_PER_OZ) < 0.01 -> "1 Unze"
        abs(g - 10 * GRAMS_PER_OZ) < 0.01 -> "10 Unzen"
        g >= 1000 && g % 1000 == 0.0 -> "${(g / 1000).toInt()} kg"
        g % 1.0 == 0.0 -> "${g.toInt()} g"
        else -> String.format(Locale.GERMANY, "%.1f g", g)
    }

    private const val OZ = GRAMS_PER_OZ

    private fun slug(s: String) = s.lowercase(Locale.ROOT)
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun build(): List<Product> {
        val l = mutableListOf<Product>()

        fun bars(metal: Metal, typeName: String, makers: List<String>, sizes: List<Double>, fineness: String) {
            for (m in makers) for (g in sizes) {
                l += Product(
                    id = "${metal.symbol}-bar-${slug(m)}-${slug(sizeLabel(g))}",
                    name = "$typeName ${sizeLabel(g)}",
                    manufacturer = m,
                    metal = metal,
                    form = ProductForm.BAR,
                    fineGrams = g,
                    fineness = fineness,
                )
            }
        }

        fun coin(metal: Metal, name: String, issuer: String, fineGrams: Double, fineness: String) {
            l += Product(
                id = "${metal.symbol}-coin-${slug(name)}",
                name = name,
                manufacturer = issuer,
                metal = metal,
                form = ProductForm.COIN,
                fineGrams = fineGrams,
                fineness = fineness,
            )
        }

        // ---------- Gold ----------
        bars(
            Metal.GOLD, "Goldbarren",
            listOf("Heraeus", "Umicore", "C.Hafner", "Heimerle + Meule", "Valcambi", "PAMP Suisse", "Argor-Heraeus", "Degussa", "Rand Refinery"),
            listOf(1.0, 2.0, 2.5, 5.0, 10.0, 20.0, OZ, 50.0, 100.0, 250.0, 500.0, 1000.0),
            "999,9"
        )
        val goldCoins1oz = listOf(
            Triple("Krügerrand", "Südafrika (Rand Refinery)", "916,7"),
            Triple("Maple Leaf", "Kanada (Royal Canadian Mint)", "999,9"),
            Triple("Wiener Philharmoniker", "Österreich (Münze Österreich)", "999,9"),
            Triple("American Eagle", "USA (US Mint)", "916,7"),
            Triple("American Buffalo", "USA (US Mint)", "999,9"),
            Triple("Britannia", "Großbritannien (Royal Mint)", "999,9"),
            Triple("Känguru / Nugget", "Australien (Perth Mint)", "999,9"),
            Triple("Libertad", "Mexiko (Casa de Moneda)", "999,0"),
        )
        for ((n, issuer, fin) in goldCoins1oz) coin(Metal.GOLD, "$n 1 Unze Gold", issuer, OZ, fin)
        for ((n, issuer, fin) in listOf(goldCoins1oz[0], goldCoins1oz[1], goldCoins1oz[2], goldCoins1oz[5], goldCoins1oz[6])) {
            coin(Metal.GOLD, "$n 1/2 Unze Gold", issuer, OZ / 2, fin)
            coin(Metal.GOLD, "$n 1/4 Unze Gold", issuer, OZ / 4, fin)
            coin(Metal.GOLD, "$n 1/10 Unze Gold", issuer, OZ / 10, fin)
        }
        coin(Metal.GOLD, "Wiener Philharmoniker 1/25 Unze Gold", "Österreich (Münze Österreich)", OZ / 25, "999,9")
        coin(Metal.GOLD, "Panda 30 g Gold", "China", 30.0, "999,0")
        coin(Metal.GOLD, "20 Mark Kaiserreich", "Deutsches Kaiserreich", 7.168, "900,0")
        coin(Metal.GOLD, "10 Mark Kaiserreich", "Deutsches Kaiserreich", 3.584, "900,0")
        coin(Metal.GOLD, "Vreneli 20 Franken", "Schweiz", 5.806, "900,0")
        coin(Metal.GOLD, "Sovereign", "Großbritannien (Royal Mint)", 7.322, "916,7")
        coin(Metal.GOLD, "1 Dukat", "Österreich (Münze Österreich)", 3.442, "986,1")
        coin(Metal.GOLD, "4 Dukaten", "Österreich (Münze Österreich)", 13.768, "986,1")
        coin(Metal.GOLD, "100 Kronen", "Österreich (Münze Österreich)", 30.488, "900,0")

        // ---------- Silber ----------
        bars(
            Metal.SILVER, "Silberbarren",
            listOf("Heraeus", "Umicore", "C.Hafner", "Heimerle + Meule", "Valcambi", "PAMP Suisse", "Argor-Heraeus", "Degussa"),
            listOf(OZ, 100.0, 250.0, 500.0, 1000.0, 5000.0),
            "999,0"
        )
        val silverCoins = listOf(
            Triple("Maple Leaf", "Kanada (Royal Canadian Mint)", "999,9"),
            Triple("Wiener Philharmoniker", "Österreich (Münze Österreich)", "999,0"),
            Triple("Krügerrand", "Südafrika (SA Mint)", "999,0"),
            Triple("Känguru", "Australien (Perth Mint)", "999,9"),
            Triple("Kookaburra", "Australien (Perth Mint)", "999,9"),
            Triple("Britannia", "Großbritannien (Royal Mint)", "999,0"),
            Triple("American Eagle", "USA (US Mint)", "999,0"),
            Triple("Libertad", "Mexiko (Casa de Moneda)", "999,0"),
            Triple("Arche Noah", "Armenien (Geiger Edelmetalle)", "999,0"),
            Triple("Lunar", "Australien (Perth Mint)", "999,9"),
        )
        for ((n, issuer, fin) in silverCoins) coin(Metal.SILVER, "$n 1 Unze Silber", issuer, OZ, fin)
        coin(Metal.SILVER, "Kookaburra 1 kg Silber", "Australien (Perth Mint)", 1000.0, "999,9")
        coin(Metal.SILVER, "Panda 30 g Silber", "China", 30.0, "999,0")

        // ---------- Platin ----------
        bars(Metal.PLATINUM, "Platinbarren", listOf("Heraeus", "Valcambi", "PAMP Suisse", "C.Hafner"), listOf(10.0, OZ, 50.0, 100.0), "999,5")
        coin(Metal.PLATINUM, "Wiener Philharmoniker 1 Unze Platin", "Österreich (Münze Österreich)", OZ, "999,5")
        coin(Metal.PLATINUM, "Maple Leaf 1 Unze Platin", "Kanada (Royal Canadian Mint)", OZ, "999,5")
        coin(Metal.PLATINUM, "Britannia 1 Unze Platin", "Großbritannien (Royal Mint)", OZ, "999,5")
        coin(Metal.PLATINUM, "Platypus 1 Unze Platin", "Australien (Perth Mint)", OZ, "999,5")

        // ---------- Palladium ----------
        bars(Metal.PALLADIUM, "Palladiumbarren", listOf("Heraeus", "Valcambi", "PAMP Suisse"), listOf(10.0, OZ, 50.0, 100.0), "999,5")
        coin(Metal.PALLADIUM, "Maple Leaf 1 Unze Palladium", "Kanada (Royal Canadian Mint)", OZ, "999,5")
        coin(Metal.PALLADIUM, "Britannia 1 Unze Palladium", "Großbritannien (Royal Mint)", OZ, "999,5")

        return l
    }
}
