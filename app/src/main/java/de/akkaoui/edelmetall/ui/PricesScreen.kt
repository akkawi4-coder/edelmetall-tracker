package de.akkaoui.edelmetall.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.akkaoui.edelmetall.data.ChartRange
import de.akkaoui.edelmetall.data.GRAMS_PER_OZ
import de.akkaoui.edelmetall.data.Metal
import de.akkaoui.edelmetall.data.PricePoint
import de.akkaoui.edelmetall.ui.components.PriceChart
import de.akkaoui.edelmetall.ui.theme.Gain
import de.akkaoui.edelmetall.ui.theme.Loss
import de.akkaoui.edelmetall.ui.theme.metalColor
import de.akkaoui.edelmetall.util.Fmt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PricesScreen(vm: AppViewModel) {
    val quotes by vm.quotes.collectAsState()
    val recorded by vm.recorded.collectAsState()
    val loading by vm.loading.collectAsState()
    val offline by vm.offline.collectAsState()

    var metal by rememberSaveable { mutableStateOf(Metal.GOLD) }
    var range by rememberSaveable { mutableStateOf(ChartRange.D1) }
    var selected by remember { mutableStateOf<PricePoint?>(null) }

    val points = remember(metal, range, recorded) { vm.repo.series(metal, range) }
    val quote = quotes[metal]
    val current = quote?.pricePerOz ?: points.lastOrNull()?.p
    val base = points.firstOrNull()?.p
    val shown = selected?.p ?: current
    val color = metalColor(metal)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (offline) item {
            Text(
                "Keine Verbindung – es werden die zuletzt gespeicherten Preise angezeigt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metal.entries.forEach { m ->
                    FilterChip(
                        selected = m == metal,
                        onClick = { metal = m; selected = null },
                        label = { Text(m.label) }
                    )
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (selected != null) "${metal.label} · ${labelFor(selected!!.t, range)}" else "${metal.label} · Spotpreis je Unze",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        shown?.let { Fmt.eur(it) } ?: "–",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (shown != null) {
                        Text(
                            "${Fmt.eur(shown / GRAMS_PER_OZ)} je Gramm",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (base != null && shown != null && base > 0) {
                        val diff = shown - base
                        val pct = diff / base * 100
                        Text(
                            "${Fmt.signedEur(diff)}  (${Fmt.pct(pct)})  · ${range.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (diff >= 0) Gain else Loss,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    PriceChart(
                        points = points,
                        lineColor = color,
                        selected = selected,
                        onSelect = { selected = it },
                        modifier = Modifier.fillMaxWidth().height(220.dp)
                    )
                    if (points.size >= 2) {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(labelFor(points.first().t, range), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(labelFor(points.last().t, range), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "Tief ${Fmt.eur(points.minOf { it.p })}  ·  Hoch ${Fmt.eur(points.maxOf { it.p })}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChartRange.entries.forEach { r ->
                            FilterChip(
                                selected = r == range,
                                onClick = { range = r; selected = null },
                                label = { Text(r.label) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Text("Alle Metalle", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        }
        items(Metal.entries.toList(), key = { it.symbol }) { m ->
            val q = quotes[m]
            Card(
                onClick = { metal = m; selected = null },
                colors = CardDefaults.cardColors(
                    containerColor = if (m == metal) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("●", color = metalColor(m))
                    Spacer(Modifier.width(10.dp))
                    Text(m.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(q?.let { Fmt.eur(it.pricePerOz) } ?: "–", style = MaterialTheme.typography.titleMedium)
                        Text(
                            q?.let { "${Fmt.eur(it.pricePerOz / GRAMS_PER_OZ)} / g" } ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            val g = quotes[Metal.GOLD]?.pricePerOz
            val s = quotes[Metal.SILVER]?.pricePerOz
            Column(Modifier.padding(vertical = 8.dp)) {
                if (g != null && s != null && s > 0) {
                    Text("Gold/Silber-Ratio: ${Fmt.num(g / s, 1)}", style = MaterialTheme.typography.bodyMedium)
                }
                val updated = quotes.values.maxOfOrNull { it.updatedAt }
                Text(
                    (if (updated != null) "Stand: ${Fmt.dateTime(updated)} · " else "") +
                        "Spotpreise in EUR je Feinunze, Quelle: gold-api.com. Händlerpreise liegen meist darüber.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun labelFor(t: Long, range: ChartRange): String =
    if (range == ChartRange.H1 || range == ChartRange.D1) Fmt.dateTime(t) else Fmt.dateOfSec(t)
