package de.akkaoui.edelmetall.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import de.akkaoui.edelmetall.security.CryptoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Zentrale Datenhaltung. Alles bleibt auf dem Gerät:
 *  - vault.bin   : Portfolios, Positionen, eigene Produkte, Einstellungen – verschlüsselt (Android Keystore)
 *  - photos/     : eigene Produktfotos (privater App-Speicher)
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
    private val _portfolios = MutableStateFlow<List<Portfolio>>(emptyList())
    private val _customProducts = MutableStateFlow<List<Product>>(emptyList())

    val quotes: StateFlow<Map<Metal, Quote>> = _quotes
    val recorded: StateFlow<Map<Metal, List<PricePoint>>> = _recorded
    val holdings: StateFlow<List<Holding>> = _holdings
    val settings: StateFlow<Settings> = _settings
    val portfolios: StateFlow<List<Portfolio>> = _portfolios
    val customProducts: StateFlow<List<Product>> = _customProducts

    /** Mitgelieferte Tageshistorie (aus dem Build). */
    val daily: Map<Metal, List<PricePoint>> by lazy { HistoryAssets.load(ctx) }

    private val _fx = MutableStateFlow<Map<Currency, Double>>(emptyMap())
    /** Live-Wechselkurse (Fremdwährung je 1 EUR) */
    val fx: StateFlow<Map<Currency, Double>> = _fx

    init {
        Fx.history = HistoryAssets.loadFx(ctx)
        loadPrices()
        loadVault()
    }

    private fun setSettings(s: Settings) {
        _settings.value = s
        Fx.display = s.currency
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
            // Wechselkurs für die Anzeigewährung: Goldpreis in Fremdwährung / Goldpreis in EUR
            val cur = _settings.value.currency
            val goldEur = fresh[Metal.GOLD]?.pricePerOz
            if (cur != Currency.EUR && goldEur != null) {
                delay(1_100)
                runCatching { PriceApi.fetchQuote(Metal.GOLD, cur.code) }.getOrNull()?.let { q ->
                    val r = q.pricePerOz / goldEur
                    if (r > 0.01 && r < 100) {
                        _fx.value = _fx.value + (cur to r)
                        Fx.live = _fx.value
                    }
                }
            }
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
            root.optJSONObject("fx")?.let { o ->
                _fx.value = Currency.entries.filter { o.has(it.code) }.associateWith { o.getDouble(it.code) }
                Fx.live = _fx.value
            }
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
        val fx = JSONObject()
        for ((c, v) in _fx.value) fx.put(c.code, v)
        val tmp = File(pricesFile.path + ".tmp")
        tmp.writeText(JSONObject().put("q", q).put("r", r).put("fx", fx).toString())
        if (!tmp.renameTo(pricesFile)) {
            pricesFile.delete()
            tmp.renameTo(pricesFile)
        }
    }

    /** EUR je Feinunze an einem Tag (letzter bekannter Tageswert), heute = Live-Kurs. */
    fun priceOn(metal: Metal, epochDay: Long, today: Long = System.currentTimeMillis() / 86_400_000L): Double? {
        if (epochDay >= today) _quotes.value[metal]?.let { return it.pricePerOz }
        val list = daily[metal].orEmpty()
        val t = epochDay * 86_400L + 86_399L
        if (list.isNotEmpty() && list[0].t <= t) {
            var lo = 0
            var hi = list.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (list[mid].t <= t) lo = mid else hi = mid - 1
            }
            val hist = list[lo]
            // Nach dem Ende der Historie: eigene Aufzeichnung oder Live-Kurs
            if (t - hist.t <= 10 * 86_400L) return hist.p
        }
        _recorded.value[metal]?.lastOrNull { it.t <= t }?.let { return it.p }
        return _quotes.value[metal]?.pricePerOz ?: list.lastOrNull()?.p
    }

    /**
     * Wertentwicklung eines Portfolios in der Anzeigewährung.
     * Ergebnis: Wert-Reihe und Einkaufswert-Reihe (t = Unix-Sekunden).
     */
    fun portfolioSeries(holdings: List<Holding>, s: Settings, fromDay: Long?): Pair<List<PricePoint>, List<PricePoint>> {
        if (holdings.isEmpty()) return emptyList<PricePoint>() to emptyList()
        val today = System.currentTimeMillis() / 86_400_000L
        val first = holdings.minOf { it.purchaseEpochDay }.coerceAtMost(today)
        val start = maxOf(first, fromDay ?: first)
        val span = today - start
        val step = when {
            span > 3 * 365 -> 7L
            span > 400 -> 3L
            else -> 1L
        }
        val factor = if (s.valueMode == ValueMode.BUYBACK) 1 - s.buybackDiscountPct / 100.0 else 1.0
        val sorted = holdings.sortedBy { it.purchaseEpochDay }
        val values = ArrayList<PricePoint>()
        val costs = ArrayList<PricePoint>()
        var d = start
        while (true) {
            val day = if (d > today) today else d
            var v = 0.0
            var c = 0.0
            for (h in sorted) {
                if (h.purchaseEpochDay > day) break
                val p = priceOn(h.metal, day, today) ?: continue
                v += h.totalFineGrams / GRAMS_PER_OZ * p * factor
                c += Fx.toEur(h.purchaseTotal, h.currency)
            }
            val t = day * 86_400L + 43_200L
            values += PricePoint(t, v * Fx.rateAt(s.currency, day))
            costs += PricePoint(t, Fx.fromEur(c, s.currency))
            if (day >= today) break
            d += step
        }
        return values to costs
    }

    // ------------------------------------------------------------------ Portfolios

    fun addPortfolio(name: String): String = synchronized(lock) {
        val p = Portfolio(UUID.randomUUID().toString(), name.trim().ifBlank { "Portfolio" })
        _portfolios.value = _portfolios.value + p
        saveVault()
        p.id
    }

    fun renamePortfolio(id: String, name: String) = synchronized(lock) {
        _portfolios.value = _portfolios.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }
        saveVault()
    }

    /** Löscht ein Portfolio samt Positionen. Das letzte Portfolio bleibt immer erhalten. */
    fun deletePortfolio(id: String) = synchronized(lock) {
        if (_portfolios.value.size <= 1) return@synchronized
        _portfolios.value = _portfolios.value.filterNot { it.id == id }
        _holdings.value = _holdings.value.filterNot { it.portfolioId == id }
        saveVault()
    }

    // ------------------------------------------------------------------ Positionen

    fun upsertHolding(h: Holding) = synchronized(lock) {
        val fixed = if (_portfolios.value.none { it.id == h.portfolioId }) h.copy(portfolioId = _portfolios.value.first().id) else h
        val list = _holdings.value.toMutableList()
        val idx = list.indexOfFirst { it.id == fixed.id }
        if (idx >= 0) list[idx] = fixed else list += fixed
        _holdings.value = list
        saveVault()
    }

    fun deleteHolding(id: String) = synchronized(lock) {
        _holdings.value = _holdings.value.filterNot { it.id == id }
        saveVault()
    }

    // ------------------------------------------------------------------ Eigene Produkte

    /** Produkt aus der eingebauten Datenbank oder den eigenen Produkten. */
    fun product(id: String?): Product? =
        if (id == null) null else _customProducts.value.firstOrNull { it.id == id } ?: ProductCatalog.byId(id)

    fun searchProducts(query: String, metal: Metal?): List<Product> =
        ProductCatalog.filter(_customProducts.value, query, metal) + ProductCatalog.search(query, metal)

    fun upsertCustomProduct(p: Product) = synchronized(lock) {
        val list = _customProducts.value.toMutableList()
        val idx = list.indexOfFirst { it.id == p.id }
        val old = if (idx >= 0) list[idx] else null
        if (old?.photo != null && old.photo != p.photo) photoFile(old.photo).delete()
        if (idx >= 0) list[idx] = p else list += p
        _customProducts.value = list
        // Positionen mit diesem Produkt übernehmen Name/Metall/Gewicht nicht automatisch – nur das Bild.
        saveVault()
    }

    fun deleteCustomProduct(id: String) = synchronized(lock) {
        val p = _customProducts.value.firstOrNull { it.id == id } ?: return@synchronized
        p.photo?.let { photoFile(it).delete() }
        _customProducts.value = _customProducts.value.filterNot { it.id == id }
        saveVault()
    }

    private val photoDir: File get() = File(ctx.filesDir, "photos").apply { mkdirs() }

    fun photoFile(name: String): File = File(photoDir, File(name).name)

    /** Liest ein Foto ein, verkleinert es auf max. 900 px und speichert es privat in der App. */
    fun importPhoto(uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 900) sample *= 2
        val raw = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotated = rotateByExif(uri, raw)
        val scale = 900f / maxOf(rotated.width, rotated.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true) else rotated
        val name = "p-${UUID.randomUUID()}.jpg"
        photoFile(name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        name
    }.getOrNull()

    private fun rotateByExif(uri: Uri, bmp: Bitmap): Bitmap = runCatching {
        val deg = ctx.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (deg == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
    }.getOrDefault(bmp)

    // ------------------------------------------------------------------ Einstellungen & Backup

    fun updateSettings(change: (Settings) -> Settings) = synchronized(lock) {
        setSettings(change(_settings.value))
        saveVault()
    }

    /** Backup-Inhalt inkl. eigener Fotos (Base64). */
    fun exportJson(): String = synchronized(lock) {
        val root = vaultJson()
        val photos = JSONObject()
        for (p in _customProducts.value) {
            val name = p.photo ?: continue
            val f = photoFile(name)
            if (f.exists()) photos.put(name, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        }
        root.put("photos", photos).toString()
    }

    /** Ersetzt alles durch ein Backup. Gibt die Anzahl Positionen zurück. */
    fun importJson(text: String): Int = synchronized(lock) {
        val root = JSONObject(text)
        root.optJSONObject("photos")?.let { photos ->
            for (key in photos.keys()) {
                runCatching { photoFile(key).writeBytes(Base64.decode(photos.getString(key), Base64.NO_WRAP)) }
            }
        }
        applyVault(root)
        saveVault()
        _holdings.value.size
    }

    private fun loadVault() {
        val text = vault.read()
        if (text == null) {
            _portfolios.value = listOf(Portfolio(DEFAULT_PORTFOLIO_ID, "Mein Portfolio"))
            return
        }
        runCatching { applyVault(JSONObject(text)) }
        if (_portfolios.value.isEmpty()) _portfolios.value = listOf(Portfolio(DEFAULT_PORTFOLIO_ID, "Mein Portfolio"))
    }

    private fun applyVault(root: JSONObject) {
        val portfolios = root.optJSONArray("portfolios")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Portfolio(o.optString("id").ifBlank { return@mapNotNull null }, o.optString("name", "Portfolio"))
            }
        }.orEmpty().ifEmpty { listOf(Portfolio(DEFAULT_PORTFOLIO_ID, "Mein Portfolio")) }
        val ids = portfolios.map { it.id }.toSet()
        _portfolios.value = portfolios
        _customProducts.value = parseProducts(root.optJSONArray("customProducts"))
        // Version 1 kannte nur ein Portfolio: alles ins erste übernehmen
        _holdings.value = parseHoldings(root.optJSONArray("holdings"), portfolios.first().id)
            .map { if (it.portfolioId in ids) it else it.copy(portfolioId = portfolios.first().id) }
        root.optJSONObject("settings")?.let { setSettings(parseSettings(it)) }
    }

    private fun saveVault() = vault.write(vaultJson().toString())

    private fun vaultJson(): JSONObject {
        val ports = JSONArray()
        for (p in _portfolios.value) ports.put(JSONObject().put("id", p.id).put("name", p.name))
        val prods = JSONArray()
        for (p in _customProducts.value) {
            prods.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("maker", p.manufacturer)
                    .put("metal", p.metal.symbol)
                    .put("form", p.form.name)
                    .put("fineGrams", p.fineGrams)
                    .put("fineness", p.fineness)
                    .put("photo", p.photo ?: JSONObject.NULL)
            )
        }
        val arr = JSONArray()
        for (h in _holdings.value) {
            arr.put(
                JSONObject()
                    .put("id", h.id)
                    .put("portfolio", h.portfolioId)
                    .put("productId", h.productId ?: JSONObject.NULL)
                    .put("name", h.name)
                    .put("metal", h.metal.symbol)
                    .put("form", h.form.name)
                    .put("fineGrams", h.fineGramsPerUnit)
                    .put("qty", h.quantity)
                    .put("total", h.purchaseTotal)
                    .put("day", h.purchaseEpochDay)
                    .put("note", h.note)
                    .put("cur", h.currency.code)
            )
        }
        val s = _settings.value
        val settings = JSONObject()
            .put("lock", s.lockEnabled)
            .put("discreet", s.discreet)
            .put("mode", s.valueMode.name)
            .put("discount", s.buybackDiscountPct)
            .put("purchaseHeadline", s.purchaseHeadline)
            .put("theme", s.themeMode.name)
            .put("currency", s.currency.code)
            .put("unit", s.unit.name)
            .put("sort", s.sort.name)
        return JSONObject()
            .put("version", 2)
            .put("portfolios", ports)
            .put("customProducts", prods)
            .put("holdings", arr)
            .put("settings", settings)
    }

    private fun parseProducts(arr: JSONArray?): List<Product> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val metal = Metal.fromSymbol(o.optString("metal")) ?: return@mapNotNull null
            Product(
                id = o.getString("id"),
                name = o.optString("name"),
                manufacturer = o.optString("maker"),
                metal = metal,
                form = runCatching { ProductForm.valueOf(o.optString("form")) }.getOrDefault(ProductForm.BAR),
                fineGrams = o.getDouble("fineGrams"),
                fineness = o.optString("fineness"),
                custom = true,
                photo = if (o.isNull("photo")) null else o.optString("photo").ifBlank { null },
            )
        }
    }

    private fun parseHoldings(arr: JSONArray?, defaultPortfolio: String): List<Holding> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val metal = Metal.fromSymbol(o.optString("metal")) ?: return@mapNotNull null
            Holding(
                id = o.getString("id"),
                portfolioId = o.optString("portfolio").ifBlank { defaultPortfolio },
                productId = if (o.isNull("productId")) null else o.optString("productId"),
                name = o.optString("name"),
                metal = metal,
                form = runCatching { ProductForm.valueOf(o.optString("form")) }.getOrDefault(ProductForm.BAR),
                fineGramsPerUnit = o.getDouble("fineGrams"),
                quantity = o.getDouble("qty"),
                purchaseTotal = o.getDouble("total"),
                purchaseEpochDay = o.getLong("day"),
                note = o.optString("note"),
                currency = Currency.of(o.optString("cur", "EUR")),
            )
        }
    }

    private fun parseSettings(o: JSONObject) = Settings(
        lockEnabled = o.optBoolean("lock", true),
        discreet = o.optBoolean("discreet", false),
        valueMode = runCatching { ValueMode.valueOf(o.optString("mode")) }.getOrDefault(ValueMode.SPOT),
        buybackDiscountPct = o.optDouble("discount", 3.0),
        purchaseHeadline = o.optBoolean("purchaseHeadline", false),
        themeMode = runCatching { ThemeMode.valueOf(o.optString("theme")) }.getOrDefault(ThemeMode.SYSTEM),
        currency = Currency.of(o.optString("currency", "EUR")),
        unit = runCatching { WeightUnit.valueOf(o.optString("unit")) }.getOrDefault(WeightUnit.OZ),
        sort = runCatching { SortMode.valueOf(o.optString("sort")) }.getOrDefault(SortMode.DATE_DESC),
    )
}
