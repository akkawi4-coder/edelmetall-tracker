package de.akkaoui.edelmetall.data

import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

/**
 * Live-Spotpreise von gold-api.com.
 * Frei, ohne Schlüssel und ohne Anmeldung; kommerzielle Nutzung laut Nutzungsbedingungen erlaubt.
 * Regel des Anbieters: höchstens 1 Anfrage pro Sekunde.
 */
private const val GRAMS_PER_LB = 453.59237

object PriceApi {
    private const val BASE = "https://api.gold-api.com"

    @Throws(IOException::class)
    fun fetchQuote(metal: Metal, currency: String = "EUR"): Quote {
        val conn = URL("$BASE/price/${metal.symbol}/$currency").openConnection() as HttpsURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "EdelmetallTracker/0.3 (Android)")
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val raw = json.getDouble("price")
            if (raw.isNaN() || raw <= 0.0) throw IOException("Ungültiger Preis")
            // Kupfer (HG) wird je Pfund (lb) notiert – intern rechnet die App je Feinunze.
            val price = if (metal == Metal.COPPER) raw * GRAMS_PER_OZ / GRAMS_PER_LB else raw
            val ts = runCatching { Instant.parse(json.getString("updatedAt")).epochSecond }
                .getOrElse { System.currentTimeMillis() / 1000 }
            return Quote(metal, price, ts)
        } finally {
            conn.disconnect()
        }
    }
}
