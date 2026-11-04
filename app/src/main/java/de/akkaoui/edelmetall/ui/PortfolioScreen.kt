package de.akkaoui.edelmetall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.akkaoui.edelmetall.data.Holding
import de.akkaoui.edelmetall.data.Metal
import de.akkaoui.edelmetall.data.Quote
import de.akkaoui.edelmetall.data.Settings
import de.akkaoui.edelmetall.data.ValueMode
import de.akkaoui.edelmetall.ui.components.ProductIcon
import de.akkaoui.edelmetall.ui.theme.Gain
import de.akkaoui.edelmetall.ui.theme.Loss
import de.akkaoui.edelmetall.util.Fmt
import de.akkaoui.edelmetall.util.Valuation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(vm: AppViewModel) {
    val holdings by vm.holdings.collectAsState()
    val quotes by vm.quotes.collectAsState()
    val settings by vm.settings.collectAsState()

    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Holding?>(null) }
    var deleting by remember { mutableStateOf<Holding?>(null) }

    val summary = Valuation.summary(holdings, quotes, settings)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SummaryCard(summary, holdings, quotes, settings, vm) }

            if (holdings.isEmpty()) {
                item {
                    Text(
                        "Noch keine Positionen. Tippe auf „+“, um deinen ersten Barren oder deine erste Münze anzulegen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            } else {
                item { Text("Positionen", style = MaterialTheme.typography.titleMedium) }
            }

            items(holdings.sortedWith(compareBy<Holding>({ it.metal.ordinal }, { -it.purchaseEpochDay })), key = { it.id }) { h ->
                HoldingCard(h, quotes, settings, onClick = { editing = h }, onDelete = { deleting = h })
            }
        }

        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Filled.Add, contentDescription = "Position hinzufügen") }
    }

    if (adding || editing != null) {
        HoldingEditorDialog(
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { vm.saveHolding(it); adding = false; editing = null }
        )
    }

    deleting?.let { h ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Position löschen?") },
            text = { Text("„${h.name}“ wird endgültig aus dem Portfolio entfernt.") },
            confirmButton = { TextButton(onClick = { vm.deleteHolding(h.id); deleting = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Abbrechen") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryCard(
    summary: Valuation.Summary,
    holdings: List<Holding>,
    quotes: Map<Metal, Quote>,
    settings: Settings,
    vm: AppViewModel,
) {
    val hide = settings.discreet
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = !settings.purchaseHeadline,
                    onClick = { vm.updateSettings { it.copy(purchaseHeadline = false) } },
                    label = { Text("Aktueller Wert") }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = settings.purchaseHeadline,
                    onClick = { vm.updateSettings { it.copy(purchaseHeadline = true) } },
                    label = { Text("Einkaufswert") }
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.updateSettings { it.copy(discreet = !it.discreet) } }) {
                    Text(if (hide) "Anzeigen" else "Ausblenden")
                }
            }

            val headline = if (settings.purchaseHeadline) summary.cost else summary.value
            Text(
                Fmt.eur(headline, hide),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                if (settings.purchaseHeadline) "Aktueller Wert: ${Fmt.eur(summary.value, hide)}"
                else "Einkaufswert: ${Fmt.eur(summary.cost, hide)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (holdings.isNotEmpty()) {
                val pctText = summary.profitPct?.let { "  (${Fmt.pct(it)})" } ?: ""
                Text(
                    "${if (summary.profit >= 0) "Gewinn" else "Verlust"}: ${Fmt.signedEur(summary.profit, hide)}$pctText",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (summary.profit >= 0) Gain else Loss,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                if (settings.valueMode == ValueMode.SPOT) "Bewertung zum Spotpreis"
                else "Bewertung zum geschätzten Ankaufspreis (Spot − ${Fmt.num(settings.buybackDiscountPct, 1)} %)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (summary.missingPrice) {
                Text(
                    "Für einige Metalle fehlt noch ein aktueller Preis.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            val metals = holdings.map { it.metal }.distinct().sortedBy { it.ordinal }
            if (metals.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                metals.forEach { m ->
                    val list = holdings.filter { it.metal == m }
                    val grams = list.sumOf { it.totalFineGrams }
                    val value = Valuation.pricePerGram(m, quotes, settings)?.let { it * grams }
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(m.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            Fmt.gramsTotal(grams, hide),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Text(value?.let { Fmt.eur(it, hide) } ?: "–", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HoldingCard(
    h: Holding,
    quotes: Map<Metal, Quote>,
    settings: Settings,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val hide = settings.discreet
    val value = Valuation.value(h, quotes, settings)
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
            ProductIcon(h.metal, h.form, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(h.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${Fmt.num(h.quantity, if (h.quantity % 1.0 == 0.0) 0 else 2)} × · ${Fmt.gramsTotal(h.totalFineGrams, hide)} fein",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Wert ${value?.let { Fmt.eur(it, hide) } ?: "–"}  ·  Kauf ${Fmt.eur(h.purchaseTotal, hide)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (value != null) {
                    val diff = value - h.purchaseTotal
                    val pct = if (h.purchaseTotal > 0) "  (${Fmt.pct(diff / h.purchaseTotal * 100)})" else ""
                    Text(
                        "${Fmt.signedEur(diff, hide)}$pct",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (diff >= 0) Gain else Loss
                    )
                }
                val taxFree = Valuation.isTaxFree(h.purchaseEpochDay)
                Text(
                    "Gekauft ${Fmt.date(h.purchaseEpochDay)} · " +
                        if (taxFree) "Haltefrist erfüllt ✓"
                        else "steuerfrei ab ${Fmt.date(Valuation.taxFreeFrom(h.purchaseEpochDay).toEpochDay())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (taxFree) Gain else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (h.note.isNotBlank()) {
                    Text(h.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Löschen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
