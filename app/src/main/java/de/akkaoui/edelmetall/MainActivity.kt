package de.akkaoui.edelmetall

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.akkaoui.edelmetall.ui.AppRoot
import de.akkaoui.edelmetall.ui.AppViewModel
import de.akkaoui.edelmetall.ui.LockScreen
import de.akkaoui.edelmetall.ui.theme.EdelmetallTheme
import de.akkaoui.edelmetall.ui.theme.isDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val vm: AppViewModel by viewModels()

    /** Entsperrt bis die App länger als 30 Sekunden im Hintergrund war. */
    private var unlocked by mutableStateOf(false)
    private var stoppedAt = 0L
    private var promptShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Kein Screenshot, keine Vorschau der Werte im App-Wechsler
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        // Live-Preise: jede Minute, solange die App sichtbar ist
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    vm.refresh()
                    delay(60_000)
                }
            }
        }

        setContent {
            val settings by vm.settings.collectAsState()
            val dark = isDark(settings.themeMode)
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                window.setBackgroundDrawable(ColorDrawable(if (dark) 0xFF0F1115.toInt() else 0xFFF6F5F2.toInt()))
                onDispose { }
            }
            EdelmetallTheme(dark) {
                val locked = settings.lockEnabled && !unlocked
                if (locked) {
                    LockScreen(onUnlock = { authenticate() })
                    LaunchedEffect(Unit) { authenticate() }
                } else {
                    AppRoot(vm)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > 30_000) unlocked = false
    }

    override fun onStop() {
        super.onStop()
        if (!promptShowing) stoppedAt = SystemClock.elapsedRealtime()
    }

    private fun authenticate() {
        if (promptShowing) return
        val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        val status = BiometricManager.from(this).canAuthenticate(authenticators)
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            // Keine Displaysperre eingerichtet: nicht aussperren, aber darauf hinweisen.
            unlocked = true
            Toast.makeText(this, "Keine Displaysperre eingerichtet – App ist ungeschützt.", Toast.LENGTH_LONG).show()
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptShowing = false
                    stoppedAt = 0L
                    unlocked = true
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    promptShowing = false
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Edelmetall Tracker entsperren")
            .setSubtitle("Mit Fingerabdruck, Gesicht oder Geräte-PIN")
            .setAllowedAuthenticators(authenticators)
            .build()
        promptShowing = true
        prompt.authenticate(info)
    }
}
