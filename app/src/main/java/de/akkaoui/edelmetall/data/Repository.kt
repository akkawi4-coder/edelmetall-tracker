package de.akkaoui.edelmetall.data

import android.content.Context
import de.akkaoui.edelmetall.security.CryptoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Zentrale Datenhaltung. Alles bleibt auf dem Gerät:
 *  - vault.bin   : Portfolio + Einstellungen, verschlüsselt (Android Keystore)
 *  - prices.json : aufgezeichnete Kurse (öffentliche Marktdaten, nicht personenbezogen)
 */
class Repository private constructor(private val ctx: Context) {

    companion object {
        @Volatile private var instance: Repository? = null
        fun get(context: Context): Repository =
            instance ?: synchronized(this) {
                instance ?: Repository(context.applicationContext).also { instance = it }
            }
    }

    private val lock = Any()
    private val vault = CryptoStore(File(ctx.filesDir, "vault.bin"))
    private val pricesFile = File(ctx.filesDir, "prices.json")

    private val _quotes = MutableStateFlow<Map<Metal, Quote>>(emptyMap())
    private val _recorded = MutableStateFlow<Map<Metal, List<PricePoint>>>(emptyMap())
    private val _holdings = MutableStateFlow<List<Holding>>(emptyList())
    private val _settings = MutableStateFlow(Settings())

    val quotes: StateFlow<Map<Metal, Quote>> = _quotes
    val recorded: StateFlow<Map<Metal, List<PricePoint>>> = _recorded
    val holdings: StateFlow<List<Holding>> = _holdings
    val settings: StateFlow<Settings> = _settings

    /** Mitgelieferte Tageshistorie (aus dem Build). */
    val daily: Map<Metal, List<PricePoint>> by lazy { HistoryAssets.load(ctx) }

    init {
        loadPrices()
        loadVault()
    }

    // ------------------------------------------------------------------ Preise

    suspend fun refreshPrices(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val fresh = mutableMapOf<Metal, Quote>()
            var first = true
            var lastError: Exception? = null
            for (m in Metal.entries) {
                if (!first) delay(1_100) // Anbieter-Regel: max. 1 Anfrage pro Sekunde
                first = false
                try {
                    fresh[m] = PriceApi.fetchQuote(m)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            if (fresh.isEmpty()) throw lastError ?: IllegalStateException("Keine Preise")
            synchronized(lock) {
                val now = System.currentTimeMillis() / 1000
                _quotes.value = _quotes.value + fresh
                val rec = _recorded.value.toMutableMap()
                for ((m, q) in fresh) {
                    val list = rec[m].orEmpty().toMutableList()
                    if (list.isEmpty() || now - list.last().t >= 50) list += PricePoint(now, q.pricePerOz)
                    rec[m] = thin(list, now)
                }
                _recorded.value = rec
                savePrices()
            }
            Unit
        }
    }

    /** Ältere Punkte verdichten: <36 h voll, bis 35 Tage stündlich, älter täglich. */
    private fun thin(list: List<PricePoint>, now: Long): List<PricePoint> {
        val recent = now - 36 * 3_600L
        val mid = now - 35 * 86_400L
        val old = list.filter { it.t < mid }.groupBy { it.t / 86_400L }.map { it.value.last() }
        val middle = list.filter { it.t in mid until recent }.groupBy { it.t / 3_600L }.map { it.value.last() }
        val fresh = list.filter { it.t >= recent }
        return (old + middle + fresh).sortedBy { it.t }
    }

    /** Kursreihe für Chart: kurze Zeiträume aus eigener Aufzeichnung, lange aus Tageshistorie. */
    fun series(metal: Metal, range: ChartRange, now: Long = System.currentTimeMillis() / 1000): List<PricePoint> {
        val seconds = range.seconds
        val start = if (seconds == null) 0L else now - seconds
        val rec = _recorded.value[metal].orEmpty()
        val hist = daily[metal].orEmpty()
        return if (seconds != null && seconds <= 31 * 86_400L) {
            val r = rec.filter { it.t >= start }
            val firstRec = r.firstOrNull()?.t ?: Long.MAX_VALUE
            hist.filter { it.t >= start && it.t < firstRec - 86_400L } + r
        } else {
            val d = hist.filter { it.t >= start }
            val lastHist = hist.lastOrNull()?.t ?: Long.MIN_VALUE
            val recDaily = rec.filter { it.t >= start && it.t > lastHist + 86_400L }
                .groupBy { it.t / 86_400L }
                .map { it.value.last() }
                .sortedBy { it.t }
            d + recDaily
        }
    }

    private fun loadPrices() {
        if (!pricesFile.exists()) return
        runCatching {
            val root = JSONObject(pricesFile.readText())
            val q = root.optJSONObject("q") ?: JSONObject()
            val r = root.optJSONObject("r") ?: JSONObject()
            val quotes = mutableMapOf<Metal, Quote>()
            val rec = mutableMapOf<Metal, List<PricePoint>>()
            for (m in Metal.entries) {
                q.optJSONArray(m.symbol)?.let { quotes[m] = Quote(m, it.getDouble(0), it.getLong(1)) }
                r.optJSONArray(m.symbol)?.let { arr ->
                    rec[m] = (0 until arr.length()).map { i ->
                        val e = arr.getJSONArray(i)
                        PricePoint(e.getLong(0), e.getDouble(1))
                    }
                }
            }
            _quotes.value = quotes
            _recorded.value = rec
        }
    }

    private fun savePrices() {
        val q = JSONObject()
        for ((m, v) in _quotes.value) q.put(m.symbol, JSONArray().put(v.pricePerOz).put(v.updatedAt))
        val r = JSONObject()
        for ((m, list) in _recorded.value) {
            val arr = JSONArray()
            for (p in list) arr.put(JSONArray().put(p.t).put(p.p))
            r.put(m.symbol, arr)
        }
        val tmp = File(pricesFile.path + ".tmp")
        tmp.writeText(JSONObject().put("q", q).put("r", r).toString())
        if (!tmp.renameTo(pricesFile)) {
            pricesFile.delete()
            tmp.renameTo(pricesFile)
        }
    }

    // ------------------------------------------------------------------ Portfolio

    fun upsertHolding(h: Holding) = synchronized(lock) {
        val list = _holdings.value.toMutableList()
        val idx = list.indexOfFirst { it.id == h.id }
        if (idx >= 0) list[idx] = h else list += h
        _holdings.value = list
        saveVault()
    }

    fun deleteHolding(id: String) = synchronized(lock) {
        _holdings.value = _holdings.value.filterNot { it.id == id }
        saveVault()
    }

    fun updateSettings(change: (Settings) -> Settings) = synchronized(lock) {
        _settings.value = change(_settings.value)
        saveVault()
    }

    fun exportJson(): String = synchronized(lock) { vaultJson().toString() }

    /** Ersetzt Portfolio und Einstellungen durch ein Backup. Gibt die Anzahl Positionen zurück. */
    fun importJson(text: String): Int = synchronized(lock) {
        val root = JSONObject(text)
        val holdings = parseHoldings(root.optJSONArray("holdings"))
        val settings = root.optJSONObject("settings")?.let { parseSettings(it) } ?: _settings.value
        _holdings.value = holdings
        _settings.value = settings
        saveVault()
        holdings.size
    }

    private fun loadVault() {
        val text = vault.read() ?: return
        runCatching {
            val root = JSONObject(text)
            _holdings.value = parseHoldings(root.optJSONArray("holdings"))
            root.optJSONObject("settings")?.let { _settings.value = parseSettings(it) }
        }
    }

    private fun saveVault() = vault.write(vaultJson().toString())

    private fun vaultJson(): JSONObject {
        val arr = JSONArray()
        for (h in _holdings.value) {
            arr.put(
                JSONObject()
                    .put("id", h.id)
                    .put("productId", h.productId ?: JSONObject.NULL)
                    .put("name", h.name)
                    .put("metal", h.metal.symbol)
                    .put("form", h.form.name)
                    .put("fineGrams", h.fineGramsPerUnit)
                    .put("qty", h.quantity)
                    .put("total", h.purchaseTotal)
                    .put("day", h.purchaseEpochDay)
                    .put("note", h.note)
            )
        }
        val s = _settings.value
        val settings = JSONObject()
            .put("lock", s.lockEnabled)
            .put("discreet", s.discreet)
            .put("mode", s.valueMode.name)
            .put("discount", s.buybackDiscountPct)
            .put("purchaseHeadline", s.purchaseHeadline)
        return JSONObject().put("version", 1).put("holdings", arr).put("settings", settings)
    }

    private fun parseHoldings(arr: JSONArray?): List<Holding> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val metal = Metal.fromSymbol(o.optString("metal")) ?: return@mapNotNull null
            Holding(
                id = o.getString("id"),
                productId = if (o.isNull("productId")) null else o.optString("productId"),
                name = o.optString("name"),
                metal = metal,
                form = runCatching { ProductForm.valueOf(o.optString("form")) }.getOrDefault(ProductForm.BAR),
                fineGramsPerUnit = o.getDouble("fineGrams"),
                quantity = o.getDouble("qty"),
                purchaseTotal = o.getDouble("total"),
                purchaseEpochDay = o.getLong("day"),
                note = o.optString("note"),
            )
        }
    }

    private fun parseSettings(o: JSONObject) = Settings(
        lockEnabled = o.optBoolean("lock", true),
        discreet = o.optBoolean("discreet", false),
        valueMode = runCatching { ValueMode.valueOf(o.optString("mode")) }.getOrDefault(ValueMode.SPOT),
        buybackDiscountPct = o.optDouble("discount", 3.0),
        purchaseHeadline = o.optBoolean("purchaseHeadline", false),
    )
}
