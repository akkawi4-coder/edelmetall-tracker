package de.akkaoui.edelmetall.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.akkaoui.edelmetall.BuildConfig
import de.akkaoui.edelmetall.data.ValueMode
import de.akkaoui.edelmetall.util.Fmt
import java.time.LocalDate

private enum class PwMode { EXPORT, IMPORT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    var discountText by remember(settings.buybackDiscountPct) { mutableStateOf(Fmt.input(settings.buybackDiscountPct)) }

    var pwMode by remember { mutableStateOf<PwMode?>(null) }
    var pendingPassword by remember { mutableStateOf("") }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.exportBackup(uri, pendingPassword)
        pendingPassword = ""
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            pwMode = PwMode.IMPORT
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Section("Sicherheit") {
            SwitchRow(
                title = "Mit Displaysperre schützen",
                subtitle = "Fingerabdruck, Gesicht oder Geräte-PIN beim Öffnen",
                checked = settings.lockEnabled,
                onChange = { v -> vm.updateSettings { it.copy(lockEnabled = v) } }
            )
            SwitchRow(
                title = "Diskret-Modus",
                subtitle = "Beträge und Gewichte im Portfolio ausblenden",
                checked = settings.discreet,
                onChange = { v -> vm.updateSettings { it.copy(discreet = v) } }
            )
        }

        Section("Bewertung") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueMode.entries.forEach { m ->
                    FilterChip(
                        selected = settings.valueMode == m,
                        onClick = { vm.updateSettings { it.copy(valueMode = m) } },
                        label = { Text(m.label) }
                    )
                }
            }
            if (settings.valueMode == ValueMode.BUYBACK) {
                OutlinedTextField(
                    value = discountText,
                    onValueChange = { t ->
                        discountText = t
                        Fmt.parse(t)?.takeIf { it in 0.0..50.0 }?.let { v -> vm.updateSettings { it.copy(buybackDiscountPct = v) } }
                    },
                    label = { Text("Abschlag auf Spotpreis in %") },
                    supportingText = { Text("Händler kaufen meist 1–5 % unter Spot an, Silbermünzen oft deutlich mehr.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Section("Backup") {
            Text(
                "Deine Daten liegen nur auf diesem Handy. Sichere sie regelmäßig als verschlüsselte Datei, " +
                    "z. B. auf einen USB-Stick oder in deinen eigenen Cloud-Speicher. Ohne Passwort ist die Datei nicht lesbar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pwMode = PwMode.EXPORT }) { Text("Backup erstellen") }
                OutlinedButton(onClick = { openDoc.launch(arrayOf("*/*")) }) { Text("Wiederherstellen") }
            }
        }

        Section("Datenschutz & Info") {
            Text(
                "• Kein Konto, keine Anmeldung, keine Werbung, keine Tracker.\n" +
                    "• Portfolio und Einstellungen werden verschlüsselt nur auf diesem Gerät gespeichert.\n" +
                    "• Die App lädt ausschließlich öffentliche Kurse von gold-api.com. Dabei werden keine persönlichen Daten übertragen.\n" +
                    "• Keine Anlage- oder Steuerberatung. Die Haltefrist-Anzeige ist ein Hinweis nach § 23 EStG für Privatpersonen in Deutschland.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Version ${BuildConfig.VERSION_NAME} · Kurshistorie: ${historyLabel(vm)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    when (pwMode) {
        PwMode.EXPORT -> PasswordDialog(
            title = "Backup-Passwort festlegen",
            confirmTwice = true,
            onDismiss = { pwMode = null },
            onConfirm = { pw ->
                pendingPassword = pw
                pwMode = null
                createDoc.launch("edelmetall-backup-${LocalDate.now()}.emt")
            }
        )
        PwMode.IMPORT -> PasswordDialog(
            title = "Backup-Passwort eingeben",
            confirmTwice = false,
            onDismiss = { pwMode = null; pendingImportUri = null },
            onConfirm = { pw ->
                pendingImportUri?.let { vm.importBackup(it, pw) }
                pwMode = null
                pendingImportUri = null
            }
        )
        null -> Unit
    }
}

private fun historyLabel(vm: AppViewModel): String {
    val first = vm.repo.daily.values.mapNotNull { it.firstOrNull()?.t }.minOrNull()
    val last = vm.repo.daily.values.mapNotNull { it.lastOrNull()?.t }.maxOrNull()
    return if (first == null || last == null) "nicht eingebunden (nur eigene Aufzeichnung)"
    else "${Fmt.dateOfSec(first)} bis ${Fmt.dateOfSec(last)}"
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PasswordDialog(title: String, confirmTwice: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val ok = pw.length >= 8 && (!confirmTwice || pw == pw2)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it },
                    label = { Text("Passwort (mind. 8 Zeichen)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                if (confirmTwice) {
                    OutlinedTextField(
                        value = pw2, onValueChange = { pw2 = it },
                        label = { Text("Passwort wiederholen") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    Text(
                        "Merke dir das Passwort gut – ohne es lässt sich das Backup nicht öffnen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onConfirm(pw) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
