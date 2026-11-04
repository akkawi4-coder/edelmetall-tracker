package de.akkaoui.edelmetall.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.akkaoui.edelmetall.data.Holding
import de.akkaoui.edelmetall.data.Repository
import de.akkaoui.edelmetall.data.Settings
import de.akkaoui.edelmetall.security.BackupCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val repo: Repository = Repository.get(app)

    val quotes = repo.quotes
    val recorded = repo.recorded
    val holdings = repo.holdings
    val settings = repo.settings

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline

    private val refreshMutex = Mutex()

    suspend fun refresh() {
        if (refreshMutex.isLocked) return
        refreshMutex.withLock {
            _loading.value = true
            val result = repo.refreshPrices()
            _offline.value = result.isFailure
            _loading.value = false
        }
    }

    fun refreshNow() {
        viewModelScope.launch { refresh() }
    }

    fun saveHolding(h: Holding) = repo.upsertHolding(h)

    fun deleteHolding(id: String) = repo.deleteHolding(id)

    fun updateSettings(change: (Settings) -> Settings) = repo.updateSettings(change)

    fun exportBackup(uri: Uri, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val data = BackupCrypto.encrypt(repo.exportJson().toByteArray(Charsets.UTF_8), password.toCharArray())
                val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                    ?: error("Datei nicht beschreibbar")
                out.use { it.write(data) }
            }.isSuccess
            toast(if (ok) "Backup gespeichert" else "Backup konnte nicht gespeichert werden")
        }
    }

    fun importBackup(uri: Uri, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Datei nicht lesbar")
                val json = String(BackupCrypto.decrypt(bytes, password.toCharArray()), Charsets.UTF_8)
                repo.importJson(json)
            }
            toast(
                result.fold(
                    onSuccess = { "Backup wiederhergestellt: $it Positionen" },
                    onFailure = { "Wiederherstellung fehlgeschlagen – falsches Passwort oder ungültige Datei" }
                )
            )
        }
    }

    private suspend fun toast(msg: String) = withContext(Dispatchers.Main) {
        Toast.makeText(getApplication<Application>(), msg, Toast.LENGTH_LONG).show()
    }
}
