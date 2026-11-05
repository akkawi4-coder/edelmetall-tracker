package de.akkaoui.edelmetall.data

import android.content.Context
import org.json.JSONObject

/**
 * Lädt die beim Bauen der App eingepackte Kurshistorie (assets/history.json).
 * Format: {"metals":{"XAU":[[epochDay, eurProUnze], ...], ...}}
 */
object HistoryAssets {
    fun load(ctx: Context): Map<Metal, List<PricePoint>> = runCatching<Map<Metal, List<PricePoint>>> {
        val txt = ctx.assets.open("history.json").bufferedReader().use { it.readText() }
        val metals = JSONObject(txt).optJSONObject("metals") ?: return@runCatching emptyMap()
        Metal.entries.associateWith { m ->
            val arr = metals.optJSONArray(m.symbol)
            if (arr == null) {
                emptyList<PricePoint>()
            } else {
                (0 until arr.length()).mapNotNull { i ->
                    val e = arr.optJSONArray(i) ?: return@mapNotNull null
                    val day = e.optLong(0, -1L)
                    val p = e.optDouble(1, Double.NaN)
                    if (day < 0 || p.isNaN() || p <= 0.0) null else PricePoint(day * 86_400L, p)
                }.sortedBy { it.t }
            }
        }
    }.getOrDefault(emptyMap())

    /** EZB-Wechselkurse aus der Historie: Währung → Liste (Epochentag, Kurs je 1 EUR) */
    fun loadFx(ctx: Context): Map<Currency, List<Pair<Long, Double>>> = runCatching {
        val txt = ctx.assets.open("history.json").bufferedReader().use { it.readText() }
        val fx = JSONObject(txt).optJSONObject("fx") ?: return@runCatching emptyMap<Currency, List<Pair<Long, Double>>>()
        Currency.entries.filter { fx.has(it.code) }.associateWith { c ->
            val arr = fx.getJSONArray(c.code)
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONArray(i) ?: return@mapNotNull null
                val r = e.optDouble(1, Double.NaN)
                if (r.isNaN() || r <= 0) null else e.getLong(0) to r
            }.sortedBy { it.first }
        }
    }.getOrDefault(emptyMap())
}
