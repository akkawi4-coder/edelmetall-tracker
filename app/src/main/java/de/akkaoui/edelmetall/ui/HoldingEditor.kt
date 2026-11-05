package de.akkaoui.edelmetall.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.akkaoui.edelmetall.data.Fx
import de.akkaoui.edelmetall.data.GRAMS_PER_OZ
import de.akkaoui.edelmetall.data.Holding
import de.akkaoui.edelmetall.data.Metal
import de.akkaoui.edelmetall.data.Product
import de.akkaoui.edelmetall.data.ProductForm
import de.akkaoui.edelmetall.ui.components.ProductImage
import de.akkaoui.edelmetall.util.Fmt
import java.time.LocalDate
import java.util.UUID

/** Vollbild-Dialog: erst Produkt wählen (oder selbst anlegen), dann Portfolio, Menge, Preis und Datum. */
@Composable
fun HoldingEditorDialog(
    vm: AppViewModel,
    initial: Holding?,
    defaultPortfolioId: String?,
    onDismiss: () -> Unit,
    onSave: (Holding) -> Unit,
    prefill: Prefill? = null,
) {
    var product by remember { mutableStateOf(prefill?.product ?: vm.repo.product(initial?.productId)) }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(prefill) }
    /** weitere erkannte Rechnungspositionen, die nacheinander geprüft werden */
    var queue by remember { mutableStateOf<List<Prefill>>(emptyList()) }
    var step by remember { mutableStateOf(0) }
    val picking = initial == null && product == null

    if (scanning) {
        ScanDialog(
            vm = vm,
            onDismiss = { scanning = false },
            onResult = { list ->
                if (list.isNotEmpty()) {
                    scanned = list.first()
                    product = list.first().product
                    queue = list.drop(1)
                    step++
                }
                scanning = false
            }
        )
        return
    }

    FullScreen(onDismiss) {
        if (picking) {
            ProductPicker(vm, onPick = { product = it }, onCancel = onDismiss, onScan = { scanning = true })
        } else {
            key(step) {
            HoldingForm(
                vm = vm,
                initial = initial,
                product = product,
                prefill = scanned,
                remaining = queue.size,
                defaultPortfolioId = defaultPortfolioId,
                onBack = if (initial == null) ({ product = null; scanned = null; queue = emptyList() }) else null,
                onCancel = onDismiss,
                onSave = { h ->
                    if (queue.isEmpty()) onSave(h)
                    else {
                        // nächste erkannte Rechnungsposition anzeigen
                        vm.saveHolding(h)
                        val next = queue.first()
                        queue = queue.drop(1)
                        scanned = next
                        product = next.product
                        step++
                    }
                }
            )
            }
        }
    }
}

@Composable
private fun FullScreen(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.safeDrawingPadding()) { content() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductPicker(vm: AppViewModel, onPick: (Product) -> Unit, onCancel: () -> Unit, onScan: () -> Unit) {
    val customs by vm.customProducts.collectAsState()
    var query by remember { mutableStateOf("") }
    var metalFilter by remember { mutableStateOf<Metal?>(null) }
    var editing by remember { mutableStateOf<Product?>(null) }
    var creating by remember { mutableStateOf(false) }
    val results = remember(query, metalFilter, customs) { vm.repo.searchProducts(query, metalFilter) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Abbrechen") }
            Spacer(Modifier.width(8.dp))
            Text("Produkt wählen", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Suchen, z. B. „Heimerle 5 g“ oder „Maple“") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = metalFilter == null, onClick = { metalFilter = null }, label = { Text("Alle") })
            Metal.entries.forEach { m ->
                FilterChip(selected = metalFilter == m, onClick = { metalFilter = m }, label = { Text(m.label) })
            }
        }
        LazyColumn(Modifier.weight(1f).imePadding()) {
            item {
                ListItem(
                    headlineContent = { Text("Per Foto erkennen") },
                    supportingContent = { Text("Barren, Münze oder Rechnung fotografieren – die Daten werden vorausgefüllt.") },
                    leadingContent = { Icon(Icons.Filled.Search, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.clickable { onScan() }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Eigenes Produkt anlegen") },
                    supportingContent = { Text("Nicht in der Liste? Einmal anlegen – danach steht es hier zur Auswahl.") },
                    leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.clickable { creating = true }
                )
                HorizontalDivider()
            }
            items(results, key = { it.id }) { p ->
                val overline: (@Composable () -> Unit)? = if (p.custom) {
                    { Text("Eigenes Produkt") }
                } else null
                val trailing: (@Composable () -> Unit)? = if (p.custom) {
                    { IconButton(onClick = { editing = p }) { Icon(Icons.Filled.Edit, contentDescription = "Bearbeiten") } }
                } else null
                ListItem(
                    headlineContent = { Text(p.name) },
                    overlineContent = overline,
                    supportingContent = { Text("${p.manufacturer.ifBlank { "–" }} · ${Fmt.grams(p.fineGrams)} fein · ${p.fineness.ifBlank { "–" }}") },
                    leadingContent = { ProductImage(p, 52.dp, p.photo?.let { vm.repo.photoFile(it) }) },
                    trailingContent = trailing,
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.clickable { onPick(p) }
                )
            }
            if (results.isEmpty()) {
                item {
                    Text(
                        "Nichts gefunden. Lege das Produkt oben selbst an.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        CustomProductDialog(
            vm = vm,
            initial = editing,
            initialMetal = metalFilter,
            onDismiss = { creating = false; editing = null },
            onSaved = { p ->
                val wasNew = creating
                creating = false
                editing = null
                if (wasNew) onPick(p)
            }
        )
    }
}

/** Eigenes Produkt anlegen oder bearbeiten – mit optionalem Foto. */
@Composable
fun CustomProductDialog(
    vm: AppViewModel,
    initial: Product?,
    initialMetal: Metal? = null,
    onDismiss: () -> Unit,
    onSaved: (Product) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var maker by remember { mutableStateOf(initial?.manufacturer ?: "") }
    var metal by remember { mutableStateOf(initial?.metal ?: initialMetal ?: Metal.GOLD) }
    var form by remember { mutableStateOf(initial?.form ?: ProductForm.BAR) }
    var fineText by remember { mutableStateOf(initial?.fineGrams?.let { Fmt.input(it) } ?: "") }
    var fineness by remember { mutableStateOf(initial?.fineness ?: "999,9") }
    var photo by remember { mutableStateOf(initial?.photo) }
    var loadingPhoto by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val fine = Fmt.parse(fineText)
    val valid = name.isNotBlank() && fine != null && fine > 0

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            loadingPhoto = true
            vm.importPhoto(uri) { file ->
                loadingPhoto = false
                if (file != null) {
                    // vorheriges, noch nicht gespeichertes Foto verwerfen
                    if (photo != null && photo != initial?.photo) vm.repo.photoFile(photo!!).delete()
                    photo = file
                }
            }
        }
    }

    fun cancel() {
        if (photo != null && photo != initial?.photo) vm.repo.photoFile(photo!!).delete()
        onDismiss()
    }

    FullScreen(onDismiss = { cancel() }) {
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { cancel() }) { Text("Abbrechen") }
                Spacer(Modifier.width(8.dp))
                Text(if (initial == null) "Eigenes Produkt" else "Produkt bearbeiten", style = MaterialTheme.typography.titleLarge)
            }

            // Live-Vorschau
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                ProductImage(
                    metal, form, 150.dp, fine, maker.ifBlank { null }, name, fineness,
                    photo?.let { vm.repo.photoFile(it) }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                OutlinedButton(enabled = !loadingPhoto, onClick = {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(if (loadingPhoto) "Lädt …" else if (photo == null) "Foto hinzufügen" else "Foto ändern") }
                if (photo != null) {
                    TextButton(onClick = {
                        if (photo != initial?.photo) vm.repo.photoFile(photo!!).delete()
                        photo = null
                    }) { Text("Foto entfernen") }
                }
            }

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Bezeichnung, z. B. „Goldbarren 5 g“") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = maker, onValueChange = { maker = it },
                label = { Text("Hersteller / Prägestätte (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Text("Metall", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metal.entries.forEach { m -> FilterChip(selected = metal == m, onClick = { metal = m }, label = { Text(m.label) }) }
            }
            Text("Form", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProductForm.entries.forEach { f -> FilterChip(selected = form == f, onClick = { form = f }, label = { Text(f.label) }) }
            }
            OutlinedTextField(
                value = fineText, onValueChange = { fineText = it },
                label = { Text("Feingewicht pro Stück in Gramm") },
                supportingText = { Text("Reiner Metallanteil. 1 Unze = 31,1035 g") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1/10 oz" to GRAMS_PER_OZ / 10, "1/4 oz" to GRAMS_PER_OZ / 4, "1/2 oz" to GRAMS_PER_OZ / 2, "1 oz" to GRAMS_PER_OZ)
                    .forEach { (label, g) -> AssistChip(onClick = { fineText = Fmt.input(g) }, label = { Text(label) }) }
            }
            OutlinedTextField(
                value = fineness, onValueChange = { fineness = it },
                label = { Text("Feinheit, z. B. 999,9") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )

            Button(
                enabled = valid && !loadingPhoto,
                onClick = {
                    val p = Product(
                        id = initial?.id ?: "custom-${UUID.randomUUID()}",
                        name = name.trim(),
                        manufacturer = maker.trim(),
                        metal = metal,
                        form = form,
                        fineGrams = fine ?: 0.0,
                        fineness = fineness.trim(),
                        custom = true,
                        photo = photo,
                    )
                    vm.saveCustomProduct(p)
                    onSaved(p)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Produkt speichern") }

            if (initial != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Produkt löschen", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Produkt löschen?") },
            text = { Text("„${initial.name}“ verschwindet aus der Auswahl. Bestehende Positionen bleiben erhalten.") },
            confirmButton = {
                TextButton(onClick = {
                    if (photo != null && photo != initial.photo) vm.repo.photoFile(photo!!).delete()
                    vm.deleteCustomProduct(initial.id)
                    confirmDelete = false
                    onDismiss()
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HoldingForm(
    vm: AppViewModel,
    initial: Holding?,
    product: Product?,
    prefill: Prefill?,
    remaining: Int,
    defaultPortfolioId: String?,
    onBack: (() -> Unit)?,
    onCancel: () -> Unit,
    onSave: (Holding) -> Unit,
) {
    val portfolios by vm.portfolios.collectAsState()
    val quotes by vm.quotes.collectAsState()
    val settings by vm.settings.collectAsState()
    val currency = initial?.currency ?: settings.currency
    val defaultName = when {
        initial != null -> initial.name
        product != null -> if (product.form == ProductForm.BAR && product.manufacturer.isNotBlank() && !product.custom &&
            !product.manufacturer.startsWith("Verschiedene"))
            "${product.manufacturer} ${product.name}" else product.name
        else -> ""
    }
    var name by remember { mutableStateOf(defaultName) }
    var portfolioId by remember {
        mutableStateOf(initial?.portfolioId ?: defaultPortfolioId ?: portfolios.firstOrNull()?.id ?: "")
    }
    var metal by remember { mutableStateOf(initial?.metal ?: product?.metal ?: Metal.GOLD) }
    var form by remember { mutableStateOf(initial?.form ?: product?.form ?: ProductForm.BAR) }
    var fineText by remember {
        mutableStateOf(initial?.fineGramsPerUnit?.let { Fmt.input(it) } ?: product?.fineGrams?.let { Fmt.input(it) } ?: "")
    }
    var qtyText by remember { mutableStateOf(initial?.quantity?.let { Fmt.input(it) } ?: prefill?.quantity?.let { Fmt.input(it) } ?: "1") }
    var priceText by remember { mutableStateOf(initial?.purchaseTotal?.let { Fmt.input(it) } ?: prefill?.total?.let { Fmt.input(it) } ?: "") }
    var day by remember { mutableLongStateOf(initial?.purchaseEpochDay ?: prefill?.epochDay ?: LocalDate.now().toEpochDay()) }
    var note by remember { mutableStateOf(initial?.note ?: prefill?.note ?: "") }
    var showDate by remember { mutableStateOf(false) }

    val fromCatalog = product != null
    val qty = Fmt.parse(qtyText)
    val price = Fmt.parse(priceText)
    val fine = if (product != null) product.fineGrams else Fmt.parse(fineText)
    val valid = name.isNotBlank() && qty != null && qty > 0 && price != null && price >= 0 && fine != null && fine > 0

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack) { Text("‹ Zurück") }
            else TextButton(onClick = onCancel) { Text("Abbrechen") }
            Spacer(Modifier.width(8.dp))
            Text(if (initial == null) "Position anlegen" else "Position bearbeiten", style = MaterialTheme.typography.titleLarge)
        }
        if (prefill != null) {
            Text(
                "Per Foto erkannt – bitte prüfen." + if (remaining > 0) " Danach folgen noch $remaining weitere Position(en)." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (product != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ProductImage(product, 170.dp, product.photo?.let { vm.repo.photoFile(it) })
                    Spacer(Modifier.height(12.dp))
                    Text(product.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOf(
                            product.manufacturer,
                            "${Fmt.grams(product.fineGrams)} Feingewicht",
                            product.fineness.takeIf { it.isNotBlank() }?.let { "Feinheit $it" },
                        ).filter { !it.isNullOrBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (portfolios.size > 1) {
            Text("Portfolio", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                portfolios.forEach { p ->
                    FilterChip(selected = portfolioId == p.id, onClick = { portfolioId = p.id }, label = { Text(p.name) })
                }
            }
        }

        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Bezeichnung") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        if (!fromCatalog) {
            // Ältere Positionen ohne Produkt aus der Liste
            Text("Metall", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metal.entries.forEach { m ->
                    FilterChip(selected = metal == m, onClick = { metal = m }, label = { Text(m.label) })
                }
            }
            Text("Form", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProductForm.entries.forEach { f ->
                    FilterChip(selected = form == f, onClick = { form = f }, label = { Text(f.label) })
                }
            }
            OutlinedTextField(
                value = fineText, onValueChange = { fineText = it },
                label = { Text("Feingewicht pro Stück in Gramm") },
                supportingText = { Text("1 Unze = 31,1035 g") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        }

        OutlinedTextField(
            value = qtyText, onValueChange = { qtyText = it },
            label = { Text("Anzahl (Stück)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        // Vorschlag: aktueller reiner Metallwert (Spot) in der Währung der Position
        val metalNow = product?.metal ?: metal
        val suggestion = if (qty != null && qty > 0 && fine != null && fine > 0) {
            quotes[metalNow]?.let { Fx.fromEur(it.pricePerOz / GRAMS_PER_OZ * fine * qty, currency) }
        } else null
        val placeholder: (@Composable () -> Unit)? = if (suggestion != null) {
            { Text("Vorschlag: ${Fmt.money(suggestion, c = currency)}") }
        } else null
        OutlinedTextField(
            value = priceText, onValueChange = { priceText = it },
            label = { Text("Kaufpreis gesamt in ${currency.code}") },
            placeholder = placeholder,
            supportingText = {
                Text(
                    if (suggestion != null && priceText.isBlank()) "Vorschlag = reiner Metallwert, Händlerpreis meist höher"
                    else "Für alle Stücke zusammen, inkl. Versand/Aufgeld"
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        if (suggestion != null && priceText.isBlank()) {
            AssistChip(
                onClick = { priceText = Fmt.input(Math.round(suggestion * 100) / 100.0) },
                label = { Text("Vorschlag übernehmen: ${Fmt.money(suggestion, c = currency)}") }
            )
        }
        OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Kaufdatum: ${Fmt.date(day)}")
        }
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            label = { Text("Notiz (optional, z. B. Händler, Lagerort)") },
            modifier = Modifier.fillMaxWidth()
        )

        if (qty != null && fine != null && qty > 0) {
            Text(
                "Gesamt-Feingewicht: ${Fmt.gramsTotal(qty * fine)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(4.dp))
        Button(
            enabled = valid,
            onClick = {
                onSave(
                    Holding(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        portfolioId = portfolioId,
                        productId = product?.id ?: initial?.productId,
                        name = name.trim(),
                        metal = product?.metal ?: metal,
                        form = product?.form ?: form,
                        fineGramsPerUnit = fine ?: 0.0,
                        quantity = qty ?: 0.0,
                        purchaseTotal = price ?: 0.0,
                        purchaseEpochDay = day,
                        note = note.trim(),
                        currency = currency,
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Speichern") }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = day * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { day = it / 86_400_000L }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Abbrechen") } }
        ) {
            DatePicker(state = state)
        }
    }
}
