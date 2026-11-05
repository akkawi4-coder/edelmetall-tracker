package de.akkaoui.edelmetall.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text as MlText
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import de.akkaoui.edelmetall.data.GRAMS_PER_OZ
import de.akkaoui.edelmetall.data.Metal
import de.akkaoui.edelmetall.data.Product
import de.akkaoui.edelmetall.data.ProductForm
import de.akkaoui.edelmetall.ui.components.ProductImage
import de.akkaoui.edelmetall.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/** Vorausgefüllte Daten für eine neue Position (aus der Foto-Erkennung). */
data class Prefill(
    val product: Product?,
    val quantity: Double? = null,
    val total: Double? = null,
    val epochDay: Long? = null,
    val note: String? = null,
)


private sealed interface ScanResult {
    data class Products(val candidates: List<Pair<Product, Double>>, val text: String) : ScanResult
    data class Invoice(val items: List<Prefill>, val unmatched: List<String>, val day: Long?, val text: String) : ScanResult
    data class Failed(val message: String) : ScanResult
}

/**
 * Foto-Erkennung komplett auf dem Gerät (ML Kit Texterkennung, offline).
 * Produktfoto: Aufschrift (Hersteller, Gewicht, Münzname, Feinheit) + Farbe → passende Produkte.
 * Rechnung: Zeilen mit Produkt, Menge und Betrag + Rechnungsdatum.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanDialog(vm: AppViewModel, onDismiss: () -> Unit, onResult: (List<Prefill>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var askCamera by remember { mutableStateOf(!cameraAllowed) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var showText by remember { mutableStateOf(false) }
    val checked = remember { mutableStateListOf<Int>() }

    val shotFile = remember { File(File(ctx.cacheDir, "scans").apply { mkdirs() }, "aufnahme.jpg") }
    val shotUri = remember { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", shotFile) }

    fun analyze(uri: Uri) {
        busy = true
        result = null
        checked.clear()
        scope.launch {
            val r = runCatching {
                val bmp = withContext(Dispatchers.IO) { loadPreview(ctx, uri) }
                preview = bmp?.asImageBitmap()
                val text = recognize(ctx, uri)
                val catalog = vm.repo.searchProducts("", null)
                // Automatisch unterscheiden: Rechnung oder Produktfoto
                if (!Matcher.looksLikeInvoice(text)) {
                    val color = bmp?.let { colorHint(it) }
                    ScanResult.Products(Matcher.rank(text.text, catalog, color).take(6), text.text)
                } else {
                    Matcher.invoice(text, catalog).also { inv ->
                        inv.items.indices.forEach { checked += it }
                    }
                }
            }.getOrElse { ScanResult.Failed("Erkennung fehlgeschlagen: ${it.message ?: "unbekannter Fehler"}") }
            result = r
            busy = false
        }
    }

    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraAllowed = granted
        askCamera = false
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) analyze(shotUri) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) analyze(uri) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (askCamera) {
            CameraPermissionDialog(
                onAllow = { requestCamera.launch(Manifest.permission.CAMERA) },
                onGalleryOnly = { askCamera = false }
            )
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.safeDrawingPadding()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text("‹ Zurück") }
                        Spacer(Modifier.width(8.dp))
                        Text("Per Foto erkennen", style = MaterialTheme.typography.titleLarge)
                    }
                    // Hinweisfeld
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Was möchtest du fotografieren?", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "📷  Produkt fotografieren – die Seite mit der Aufschrift (Hersteller, Gewicht, Feinheit), " +
                                    "gerade von vorne und gut ausgeleuchtet.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "🧾  Rechnung fotografieren – flach hinlegen, vollständig und scharf im Bild.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Die App erkennt automatisch, ob es ein Produkt oder eine Rechnung ist.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = {
                            if (cameraAllowed) takePhoto.launch(shotUri) else askCamera = true
                        }) { Text("Foto aufnehmen") }
                        OutlinedButton(enabled = !busy, onClick = {
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text("Aus Galerie") }
                    }
                    Text(
                        "Die Erkennung läuft nur auf deinem Handy, das Foto verlässt das Gerät nicht.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    preview?.let {
                        Image(
                            it, contentDescription = null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))
                        )
                    }
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Wird erkannt …")
                        }
                    }

                    when (val r = result) {
                        is ScanResult.Failed -> Text(r.message, color = MaterialTheme.colorScheme.error)
                        is ScanResult.Products -> {
                            if (r.candidates.isEmpty()) {
                                Text(
                                    "Kein passendes Produkt erkannt. Versuche ein schärferes Foto der Aufschrift oder wähle das Produkt aus der Liste.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    if (r.candidates.size == 1) "Erkannt – bitte bestätigen:" else "Wahrscheinlichste Treffer – tippe auf das richtige Produkt:",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                r.candidates.forEachIndexed { i, (p, _) ->
                                    CandidateCard(vm, p, best = i == 0) { onResult(listOf(Prefill(p))) }
                                }
                            }
                            RecognizedText(r.text, showText) { showText = !showText }
                        }
                        is ScanResult.Invoice -> {
                            r.day?.let { Text("Rechnungsdatum: ${Fmt.date(it)}", style = MaterialTheme.typography.bodyMedium) }
                            if (r.items.isEmpty()) {
                                Text(
                                    "Auf der Rechnung wurden keine bekannten Produkte gefunden. Lege die Position manuell an.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text("Gefundene Positionen – Haken setzen, was übernommen werden soll:", style = MaterialTheme.typography.titleSmall)
                                r.items.forEachIndexed { i, item ->
                                    val p = item.product ?: return@forEachIndexed
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                                        if (i in checked) checked.remove(i) else checked += i
                                    }) {
                                        Checkbox(checked = i in checked, onCheckedChange = { c -> if (c) checked += i else checked.remove(i) })
                                        ProductImage(p, 44.dp, p.photo?.let { vm.repo.photoFile(it) })
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "${Fmt.num(item.quantity ?: 1.0, 0)} Stück" +
                                                    (item.total?.let { " · ${Fmt.num(it, 2)}" } ?: " · Betrag nicht erkannt"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                Button(
                                    enabled = checked.isNotEmpty(),
                                    onClick = { onResult(checked.sorted().map { r.items[it] }) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("${checked.size} Position(en) prüfen und übernehmen") }
                            }
                            if (r.unmatched.isNotEmpty()) {
                                Text(
                                    "Nicht zugeordnet: " + r.unmatched.take(5).joinToString(" | "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            RecognizedText(r.text, showText) { showText = !showText }
                        }
                        null -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPermissionDialog(onAllow: () -> Unit, onGalleryOnly: () -> Unit) {
    AlertDialog(
        onDismissRequest = onGalleryOnly,
        title = { Text("Kamera verwenden?") },
        text = {
            Text(
                "Für die Foto-Erkennung möchte die App deine Kamera verwenden, um Barren, Münzen oder Rechnungen zu fotografieren.\n\n" +
                    "• Fotos werden nur auf dem Handy ausgewertet und nicht hochgeladen.\n" +
                    "• Die Kamera wird nur genutzt, wenn du „Foto aufnehmen“ tippst.\n" +
                    "• Du kannst die Berechtigung jederzeit in den Android-Einstellungen widerrufen.\n\n" +
                    "Ohne Kamera kannst du auch ein vorhandenes Foto aus der Galerie wählen."
            )
        },
        confirmButton = { TextButton(onClick = onAllow) { Text("Weiter zur Freigabe") } },
        dismissButton = { TextButton(onClick = onGalleryOnly) { Text("Nur Galerie") } }
    )
}

@Composable
private fun CandidateCard(vm: AppViewModel, p: Product, best: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (best) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductImage(p, 56.dp, p.photo?.let { vm.repo.photoFile(it) })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${p.manufacturer} · ${Fmt.grams(p.fineGrams)} · ${p.fineness}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecognizedText(text: String, open: Boolean, onToggle: () -> Unit) {
    if (text.isBlank()) return
    TextButton(onClick = onToggle) { Text(if (open) "Erkannten Text ausblenden" else "Erkannten Text anzeigen") }
    if (open) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ Bild & Texterkennung

private fun loadPreview(ctx: Context, uri: Uri): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1000) sample *= 2
    return ctx.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

private suspend fun recognize(ctx: Context, uri: Uri): MlText {
    val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(ctx, uri) }
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    return try {
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    } finally {
        recognizer.close()
    }
}

/** Grobe Metallbestimmung über die mittlere Farbe der Bildmitte. */
private fun colorHint(bmp: android.graphics.Bitmap): Metal? {
    val w = bmp.width
    val h = bmp.height
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0
    val stepX = maxOf(1, w / 60)
    val stepY = maxOf(1, h / 60)
    var y = (h * 0.3).toInt()
    while (y < h * 0.7) {
        var x = (w * 0.3).toInt()
        while (x < w * 0.7) {
            val c = bmp.getPixel(x, y)
            r += AndroidColor.red(c); g += AndroidColor.green(c); b += AndroidColor.blue(c); n++
            x += stepX
        }
        y += stepY
    }
    if (n == 0) return null
    val hsv = FloatArray(3)
    AndroidColor.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
    val (hue, sat, v) = Triple(hsv[0], hsv[1], hsv[2])
    return when {
        v < 0.15f -> null
        sat < 0.16f -> Metal.SILVER
        hue in 34f..62f && sat > 0.22f -> Metal.GOLD
        hue in 8f..34f && sat > 0.30f -> Metal.COPPER
        else -> null
    }
}

// ------------------------------------------------------------------ Zuordnung

private object Matcher {

    private val makerKeys = mapOf(
        "Heraeus" to listOf("HERAEUS"),
        "Argor-Heraeus" to listOf("ARGOR", "HERAEUS"),
        "Umicore" to listOf("UMICORE"),
        "C.Hafner" to listOf("HAFNER"),
        "Heimerle + Meule" to listOf("HEIMERLE", "MEULE"),
        "Valcambi" to listOf("VALCAMBI"),
        "PAMP Suisse" to listOf("PAMP"),
        "Degussa" to listOf("DEGUSSA"),
        "Rand Refinery" to listOf("RAND REFINERY"),
    )

    private val coinKeys = listOf(
        "Krügerrand" to listOf("KRUGERRAND", "KRUEGERRAND"),
        "Maple Leaf" to listOf("MAPLE", "CANADA", "FEUILLE"),
        "Wiener Philharmoniker" to listOf("PHILHARMONIKER", "PHILHARMONIC", "REPUBLIK OSTERREICH", "OSTERREICH"),
        "American Eagle" to listOf("EAGLE", "UNITED STATES OF AMERICA"),
        "American Buffalo" to listOf("BUFFALO"),
        "Britannia" to listOf("BRITANNIA"),
        "Känguru" to listOf("KANGAROO", "KANGURU", "NUGGET"),
        "Kookaburra" to listOf("KOOKABURRA"),
        "Libertad" to listOf("LIBERTAD", "MEXICANOS"),
        "Panda" to listOf("PANDA", "CHINA", "ZHONGHUA"),
        "Arche Noah" to listOf("NOAH", "ARMENIA"),
        "Lunar" to listOf("LUNAR"),
        "Platypus" to listOf("PLATYPUS"),
        "Sovereign" to listOf("SOVEREIGN"),
        "Vreneli" to listOf("HELVETIA", "VRENELI"),
        "Mark Kaiserreich" to listOf("DEUTSCHES REICH"),
        "Dukat" to listOf("DUKAT", "DUCAT"),
        "Kronen" to listOf("KRONEN", "CORONA"),
        "Kupfermünze" to listOf("COPPER ROUND", "KUPFERMUNZE"),
    )

    private val metalKeys = listOf(
        Metal.PALLADIUM to listOf("PALLADIUM"),
        Metal.PLATINUM to listOf("PLATIN", "PLATINUM"),
        Metal.SILVER to listOf("SILBER", "SILVER", "ARGENT", "PLATA"),
        Metal.COPPER to listOf("KUPFER", "COPPER", "CUIVRE"),
        Metal.GOLD to listOf("GOLD", "OR PUR", "FINE GOLD", "ORO"),
    )

    fun norm(s: String): String = s.uppercase()
        .replace("Ä", "A").replace("Ö", "O").replace("Ü", "U").replace("ß", "SS")
        .replace(Regex("[^A-Z0-9/.,+\\- ]"), " ")
        .replace(Regex("\\s+"), " ")

    /** Alle im Text gefundenen Gewichte in Gramm. */
    fun weights(t: String): List<Double> {
        val out = mutableListOf<Double>()
        fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
        Regex("(\\d+)\\s*/\\s*(\\d+)\\s*(OZ|UNZE|OUNCE)").findAll(t).forEach {
            val a = num(it.groupValues[1]); val b = num(it.groupValues[2])
            if (a != null && b != null && b > 0) out += a / b * GRAMS_PER_OZ
        }
        val t2 = t.replace(Regex("(\\d+)\\s*/\\s*(\\d+)\\s*(OZ|UNZE|OUNCE)"), " ")
        Regex("(\\d+(?:[.,]\\d+)?)\\s*(KG|KILO)\\b").findAll(t2).forEach { m -> num(m.groupValues[1])?.let { out += it * 1000 } }
        Regex("(\\d+(?:[.,]\\d+)?)\\s*(G|GR|GRAM|GRAMM|GRAMS)\\b").findAll(t2).forEach { m -> num(m.groupValues[1])?.let { out += it } }
        Regex("(\\d+(?:[.,]\\d+)?)\\s*(OZ|UNZE|UNZEN|OUNCE|OUNCES)\\b").findAll(t2).forEach { m -> num(m.groupValues[1])?.let { out += it * GRAMS_PER_OZ } }
        if (t2.contains("ONE OUNCE") || t2.contains("EINE UNZE")) out += GRAMS_PER_OZ
        return out.filter { it in 0.5..100_000.0 }
    }

    private fun metalsIn(t: String): Set<Metal> {
        val found = mutableSetOf<Metal>()
        var rest = " $t "
        for ((m, keys) in metalKeys) {
            if (keys.any { rest.contains(it) }) {
                found += m
                keys.forEach { rest = rest.replace(it, " ") }
            }
        }
        return found
    }

    private fun coinKeysFor(p: Product): List<String> =
        coinKeys.firstOrNull { (k, _) -> p.name.contains(k, ignoreCase = true) }?.second
            ?: norm(p.name).split(" ").filter { it.length >= 5 && it.none(Char::isDigit) && it !in setOf("UNZE", "UNZEN", "GOLD", "SILBER") }

    fun score(t: String, p: Product, color: Metal?, strict: Boolean): Double {
        var s = 0.0
        val ws = weights(t)
        if (ws.isNotEmpty()) {
            s += if (ws.any { abs(it - p.fineGrams) <= p.fineGrams * 0.02 + 0.05 }) 3.0 else -2.0
        }
        val metals = metalsIn(t)
        if (metals.isNotEmpty()) s += if (p.metal in metals) 2.0 else -3.0
        else if (color != null) {
            s += when {
                color == p.metal -> 1.5
                color == Metal.SILVER && (p.metal == Metal.PLATINUM || p.metal == Metal.PALLADIUM) -> 0.5
                else -> -1.0
            }
        }
        if (p.form == ProductForm.BAR) {
            val keys = makerKeys[p.manufacturer] ?: norm(p.manufacturer).split(" ").filter { it.length >= 4 }
            val hits = keys.count { t.contains(it) }
            if (hits > 0) s += 3.0 + hits
            if (Regex("\\b(BARREN|BAR|INGOT|LINGOT)\\b").containsMatchIn(t)) s += 1.0
        } else {
            val hits = coinKeysFor(p).count { t.contains(it) }
            if (hits > 0) s += 4.0 + hits
            if (Regex("\\b(DOLLARS?|EURO|POUNDS?|RAND|PESOS?|YUAN|SHILLING|FRANKEN|MUNZE|COIN)\\b").containsMatchIn(t)) s += 1.0
        }
        if (strict && s < 5.0) return 0.0
        return s
    }

    fun rank(raw: String, catalog: List<Product>, color: Metal?): List<Pair<Product, Double>> {
        val t = " " + norm(raw) + " "
        return catalog.map { it to score(t, it, color, strict = false) }
            .filter { it.second >= 3.0 }
            .sortedByDescending { it.second }
    }

    /** Rechnung erkennen: typische Begriffe, mehrere Beträge oder viele Textzeilen. */
    fun looksLikeInvoice(text: MlText): Boolean {
        val t = " " + norm(text.text) + " "
        val words = listOf("RECHNUNG", "INVOICE", "MWST", "UST", "STEUER", "SUMME", "GESAMT", "NETTO", "BRUTTO",
            "BESTELL", "ARTIKEL", "MENGE", "ZAHLUNG", "LIEFER", "KUNDEN", "BETRAG", "TOTAL", "IBAN", "VERSAND")
        val hits = words.count { t.contains(it) }
        val amounts = money.findAll(text.text).count()
        val lines = text.textBlocks.sumOf { it.lines.size }
        return hits >= 2 || (hits >= 1 && amounts >= 2) || amounts >= 4 || lines >= 18
    }

    private val money = Regex("(\\d{1,3}(?:[.\\s]\\d{3})*,\\d{2}|\\d+\\.\\d{2})(?!\\s*(G|GR|KG|OZ|GRAMM)\\b)")

    fun invoice(text: MlText, catalog: List<Product>): ScanResult.Invoice {
        // Zeilen anhand der Position zu Tabellenzeilen zusammenfügen
        val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        val rows = mutableListOf<MutableList<MlText.Line>>()
        for (l in lines.sortedBy { it.boundingBox!!.centerY() }) {
            val bb = l.boundingBox!!
            val row = rows.lastOrNull()
            val ref = row?.firstOrNull()?.boundingBox
            if (row != null && ref != null && abs(ref.centerY() - bb.centerY()) < maxOf(ref.height(), bb.height()) * 0.6) row += l
            else rows += mutableListOf(l)
        }
        val rowTexts = rows.map { r -> r.sortedBy { it.boundingBox!!.left }.joinToString("  ") { it.text } }

        val day = findDate(rowTexts)
        val items = mutableListOf<Prefill>()
        val unmatched = mutableListOf<String>()
        for (row in rowTexts) {
            val t = " " + norm(row) + " "
            val best = catalog.map { it to score(t, it, null, strict = true) }.maxByOrNull { it.second }
            if (best == null || best.second <= 0.0) {
                if (weights(t).isNotEmpty() && money.containsMatchIn(row)) unmatched += row.trim()
                continue
            }
            val qty = Regex("^\\s*(\\d{1,3})\\s*(X|×|STK|STUCK|ST\\.)?\\s").find(t)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: Regex("(\\d{1,3})\\s*(X|×|STK|STUCK|ST\\.)\\s").find(t)?.groupValues?.get(1)?.toDoubleOrNull()
            val amounts = money.findAll(row).mapNotNull { m ->
                val v = m.groupValues[1]
                (if (v.contains(',')) v.replace(".", "").replace(" ", "").replace(',', '.') else v).toDoubleOrNull()
            }.toList()
            val q = qty?.takeIf { it in 1.0..999.0 && abs(it - best.first.fineGrams) > 0.01 } ?: 1.0
            items += Prefill(
                product = best.first,
                quantity = q,
                total = amounts.maxOrNull(),
                epochDay = day,
                note = "Aus Rechnung" + (day?.let { " vom ${Fmt.date(it)}" } ?: ""),
            )
        }
        return ScanResult.Invoice(items, unmatched, day, text.text)
    }

    private fun findDate(rows: List<String>): Long? {
        val re = Regex("\\b(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4})\\b")
        fun parse(s: String): Long? = re.find(s)?.let { m ->
            runCatching {
                var y = m.groupValues[3].toInt()
                if (y < 100) y += 2000
                val d = LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[1].toInt())
                if (d.year < 1990 || d.isAfter(LocalDate.now())) null else d.toEpochDay()
            }.getOrNull()
        }
        rows.firstOrNull { norm(it).contains("DATUM") || norm(it).contains("DATE") }?.let { parse(it) }?.let { return it }
        return rows.firstNotNullOfOrNull { parse(it) }
    }
}
