package de.akkaoui.edelmetall.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.akkaoui.edelmetall.data.Holding
import de.akkaoui.edelmetall.data.Metal
import de.akkaoui.edelmetall.data.Portfolio
import de.akkaoui.edelmetall.data.Quote
import de.akkaoui.edelmetall.data.Settings
import de.akkaoui.edelmetall.data.ValueMode
import de.akkaoui.edelmetall.ui.components.ProductImage
import de.akkaoui.edelmetall.ui.theme.Gain
import de.akkaoui.edelmetall.ui.theme.Loss
import de.akkaoui.edelmetall.util.Fmt
import de.akkaoui.edelmetall.util.Valuation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(vm: AppViewModel) {
    val allHoldings by vm.holdings.collectAsState()
    val portfolios by vm.portfolios.collectAsState()
    val quotes by vm.quotes.collectAsState()
    val settings by vm.settings.collectAsState()

    /** null = alle Portfolios zusammen */
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = portfolios.firstOrNull { it.id == selectedId }
    LaunchedEffect(selectedId, portfolios) { if (selectedId != null && selected == null) selectedId = null }

    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Holding?>(null) }
    var deleting by remember { mutableStateOf<Holding?>(null) }
    var naming by remember { mutableStateOf<Portfolio?>(null) }
    var creatingPortfolio by remember { mutableStateOf(false) }
    var deletingPortfolio by remember { mutableStateOf<Portfolio?>(null) }

    val holdings = if (selected == null) allHoldings else allHoldings.filter { it.portfolioId == selected.id }
    val summary = Valuation.summary(holdings, quotes, settings)
    val names = portfolios.associate { it.id to it.name }
    val multi = portfolios.size > 1

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (multi) {
                        FilterChip(selected = selected == null, onClick = { selectedId = null }, label = { Text("Alle") })
                    }
                    portfolios.forEach { p ->
                        FilterChip(
                            selected = selected?.id == p.id || (!multi && selected == null),
                            onClick = { selectedId = p.id },
                            label = { Text(p.name) }
                        )
                    }
                    AssistChip(
                        onClick = { creatingPortfolio = true },
                        label = { Text("Neues Portfolio") },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) }
                    )
                }
            }

            item {
                val title = selected?.name ?: if (multi) "Alle Portfolios" else portfolios.firstOrNull()?.name ?: "Portfolio"
                val target = selected ?: if (!multi) portfolios.firstOrNull() else null
                SummaryCard(
                    title = title,
                    summary = summary,
                    holdings = holdings,
                    quotes = quotes,
                    settings = settings,
                    vm = vm,
                    onRename = target?.let { t -> { naming = t } },
                    onDelete = if (target != null && multi) ({ deletingPortfolio = target }) else null,
                )
            }

            if (selected == null && multi) {
                item { Text("Portfolios", style = MaterialTheme.typography.titleMedium) }
                items(portfolios, key = { "p-" + it.id }) { p ->
                    val list = allHoldings.filter { it.portfolioId == p.id }
                    PortfolioCard(p, list, Valuation.summary(list, quotes, settings), settings) { selectedId = p.id }
                }
            }

            if (holdings.isEmpty()) {
                item {
                    Text(
                        "Noch keine Positionen. Tippe auf „+“, um einen Barren oder eine Münze anzulegen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            } else {
                item { Text(if (selected == null && multi) "Alle Positionen" else "Positionen", style = MaterialTheme.typography.titleMedium) }
            }

            items(holdings.sortedWith(compareBy<Holding>({ it.metal.ordinal }, { -it.purchaseEpochDay })), key = { it.id }) { h ->
                HoldingCard(
                    vm, h, quotes, settings,
                    portfolioName = if (selected == null && multi) names[h.portfolioId] else null,
                    onClick = { editing = h },
                    onDelete = { deleting = h }
                )
            }
        }

        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Filled.Add, contentDescription = "Position hinzufügen") }
    }

    if (adding || editing != null) {
        HoldingEditorDialog(
            vm = vm,
            initial = editing,
            defaultPortfolioId = selected?.id ?: portfolios.firstOrNull()?.id,
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

    if (creatingPortfolio) {
        NameDialog(
            title = "Neues Portfolio",
            initial = "",
            onDismiss = { creatingPortfolio = false },
            onConfirm = { name -> selectedId = vm.addPortfolio(name); creatingPortfolio = false }
        )
    }
    naming?.let { p ->
        NameDialog(
            title = "Portfolio umbenennen",
            initial = p.name,
            onDismiss = { naming = null },
            onConfirm = { name -> vm.renamePortfolio(p.id, name); naming = null }
        )
    }
    deletingPortfolio?.let { p ->
        val count = allHoldings.count { it.portfolioId == p.id }
        AlertDialog(
            onDismissRequest = { deletingPortfolio = null },
            title = { Text("Portfolio löschen?") },
            text = {
                Text(
                    if (count == 0) "„${p.name}“ ist leer und wird entfernt."
                    else "„${p.name}“ und seine $count Position${if (count == 1) "" else "en"} werden endgültig gelöscht. " +
                        "Tipp: Positionen vorher über „Bearbeiten“ in ein anderes Portfolio verschieben."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deletePortfolio(p.id); selectedId = null; deletingPortfolio = null }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deletingPortfolio = null }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) },
                label = { Text("Name, z. B. „Gold“ oder „Käufe 2022“") },
                singleLine = true
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortfolioCard(p: Portfolio, holdings: List<Holding>, summary: Valuation.Summary, settings: Settings, onClick: () -> Unit) {
    val hide = settings.discreet
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${holdings.size} Position${if (holdings.size == 1) "" else "en"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.eur(summary.value, hide), style = MaterialTheme.typography.titleMedium)
                if (holdings.isNotEmpty()) {
                    val pct = summary.profitPct?.let { "  (${Fmt.pct(it)})" } ?: ""
                    Text(
                        "${Fmt.signedEur(summary.profit, hide)}$pct",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (summary.profit >= 0) Gain else Loss
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryCard(
    title: String,
    summary: Valuation.Summary,
    holdings: List<Holding>,
    quotes: Map<Metal, Quote>,
    settings: Settings,
    vm: AppViewModel,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val hide = settings.discreet
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (onRename != null) {
                    IconButton(onClick = onRename) {
                        Icon(Icons.Filled.Edit, contentDescription = "Umbenennen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Portfolio löschen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
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
    vm: AppViewModel,
    h: Holding,
    quotes: Map<Metal, Quote>,
    settings: Settings,
    portfolioName: String?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val hide = settings.discreet
    val value = Valuation.value(h, quotes, settings)
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
            val p = vm.repo.product(h.productId)
            if (p != null) ProductImage(p, 52.dp, p.photo?.let { vm.repo.photoFile(it) })
            else ProductImage(h.metal, h.form, 52.dp, h.fineGramsPerUnit, name = h.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (portfolioName != null) {
                    Text(portfolioName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
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
