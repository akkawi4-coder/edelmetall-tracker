package de.akkaoui.edelmetall.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.getValue
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
import de.akkaoui.edelmetall.data.Holding
import de.akkaoui.edelmetall.data.Metal
import de.akkaoui.edelmetall.data.Product
import de.akkaoui.edelmetall.data.ProductCatalog
import de.akkaoui.edelmetall.data.ProductForm
import de.akkaoui.edelmetall.ui.components.ProductIcon
import de.akkaoui.edelmetall.util.Fmt
import java.time.LocalDate
import java.util.UUID

/** Vollbild-Dialog: erst Produkt aus der Datenbank wählen, dann Menge, Preis und Datum eintragen. */
@Composable
fun HoldingEditorDialog(initial: Holding?, onDismiss: () -> Unit, onSave: (Holding) -> Unit) {
    var product by remember { mutableStateOf(ProductCatalog.byId(initial?.productId)) }
    var custom by remember { mutableStateOf(initial != null && initial.productId == null) }
    val picking = initial == null && product == null && !custom

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.safeDrawingPadding()) {
                if (picking) {
                    ProductPicker(
                        onPick = { product = it },
                        onCustom = { custom = true },
                        onCancel = onDismiss
                    )
                } else {
                    HoldingForm(
                        initial = initial,
                        product = product,
                        onBack = if (initial == null) ({ product = null; custom = false }) else null,
                        onCancel = onDismiss,
                        onSave = onSave
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductPicker(onPick: (Product) -> Unit, onCustom: () -> Unit, onCancel: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var metalFilter by remember { mutableStateOf<Metal?>(null) }
    val results = remember(query, metalFilter) { ProductCatalog.search(query, metalFilter) }

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
                    headlineContent = { Text("Eigenes Produkt anlegen") },
                    supportingContent = { Text("Nicht in der Liste? Einfach selbst eintragen.") },
                    leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.clickable { onCustom() }
                )
                HorizontalDivider()
            }
            items(results, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text("${p.manufacturer} · ${Fmt.grams(p.fineGrams)} fein · ${p.fineness}") },
                    leadingContent = { ProductIcon(p.metal, p.form, 40.dp) },
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HoldingForm(
    initial: Holding?,
    product: Product?,
    onBack: (() -> Unit)?,
    onCancel: () -> Unit,
    onSave: (Holding) -> Unit,
) {
    val defaultName = when {
        initial != null -> initial.name
        product != null -> if (product.form == ProductForm.BAR) "${product.manufacturer} ${product.name}" else product.name
        else -> ""
    }
    var name by remember { mutableStateOf(defaultName) }
    var metal by remember { mutableStateOf(initial?.metal ?: product?.metal ?: Metal.GOLD) }
    var form by remember { mutableStateOf(initial?.form ?: product?.form ?: ProductForm.BAR) }
    var fineText by remember {
        mutableStateOf(
            initial?.fineGramsPerUnit?.let { Fmt.input(it) } ?: product?.fineGrams?.let { Fmt.input(it) } ?: ""
        )
    }
    var qtyText by remember { mutableStateOf(initial?.quantity?.let { Fmt.input(it) } ?: "1") }
    var priceText by remember { mutableStateOf(initial?.purchaseTotal?.let { Fmt.input(it) } ?: "") }
    var day by remember { mutableLongStateOf(initial?.purchaseEpochDay ?: LocalDate.now().toEpochDay()) }
    var note by remember { mutableStateOf(initial?.note ?: "") }
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

        if (product != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductIcon(product.metal, product.form, 56.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(product.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${product.manufacturer} · ${Fmt.grams(product.fineGrams)} Feingewicht · ${product.fineness}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Bezeichnung") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        if (!fromCatalog) {
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
        OutlinedTextField(
            value = priceText, onValueChange = { priceText = it },
            label = { Text("Kaufpreis gesamt in €") },
            supportingText = { Text("Für alle Stücke zusammen, inkl. Versand/Aufgeld") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
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
                        productId = product?.id,
                        name = name.trim(),
                        metal = product?.metal ?: metal,
                        form = product?.form ?: form,
                        fineGramsPerUnit = fine ?: 0.0,
                        quantity = qty ?: 0.0,
                        purchaseTotal = price ?: 0.0,
                        purchaseEpochDay = day,
                        note = note.trim(),
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
