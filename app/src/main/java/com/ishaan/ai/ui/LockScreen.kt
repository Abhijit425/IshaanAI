package com.ishaan.ai.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.ishaan.ai.data.AppSettings
import com.ishaan.ai.ui.components.InfoBanner
import com.ishaan.ai.ui.components.LocalContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LockScreen(activity: FragmentActivity, settings: AppSettings, onUnlocked: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var real by remember { mutableStateOf<AppSettings?>(null) }
    LaunchedEffect(Unit) { real = c.settings.current() }
    val s = real ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

    if (!s.pinSet) { FirstRunSetup(onDone = { scope.launch { real = c.settings.current(); onUnlocked() } }); return }

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var waitUntil by remember { mutableLongStateOf(0L) }

    fun biometric() {
        val can = BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        if (!can) { error = "Biometrics not available on this phone. Use your PIN."; return }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onUnlocked() }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Ishaan AI").setSubtitle("Medical records are protected")
                .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK).setNegativeButtonText("Use PIN").build(),
        )
    }
    LaunchedEffect(s.biometricEnabled) { if (s.biometricEnabled) biometric() }

    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text("Ishaan AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Enter your PIN", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = pin, onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) pin = it },
            label = { Text("PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                scope.launch {
                    if (System.currentTimeMillis() < waitUntil) { error = "Too many attempts. Please wait."; return@launch }
                    if (c.settings.verifyPin(pin)) { pin = ""; error = null; onUnlocked() }
                    else {
                        val f = c.settings.failedAttempts()
                        pin = ""
                        if (f >= 5) { waitUntil = System.currentTimeMillis() + 30_000L * (f - 4); error = "Wrong PIN. Wait ${30 * (f - 4)} seconds." }
                        else error = "Wrong PIN."
                    }
                }
            },
            enabled = pin.length >= 4, modifier = Modifier.fillMaxWidth(),
        ) { Text("Unlock") }
        if (s.biometricEnabled) TextButton(onClick = { biometric() }) { Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(6.dp)); Text("Use fingerprint / face") }
    }
}

@Composable
private fun FirstRunSetup(onDone: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Welcome to Ishaan AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("A private, offline place for Ishaan's medical journey.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        if (step == 0) {
            InfoBanner("Everything stays on this phone, encrypted. The app has no internet permission at all, so it cannot upload anything.")
            InfoBanner("This app organises records and shows trends. It does not diagnose, and never suggests medicine or dose changes. Always follow Ishaan's doctors.")
            InfoBanner("If the phone is lost, reset or the app is uninstalled, the data is gone unless you make an encrypted backup (Settings → Backup). Please back up regularly.", MaterialTheme.colorScheme.error)
            InfoBanner("In an emergency — cannot pass urine, severe breathing difficulty, unusual drowsiness, seizures, or looks seriously unwell — go to emergency care immediately. Don't wait for the app.", MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("I understand — set a PIN") }
        } else {
            Text("Choose a 4–8 digit PIN", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(pin, { if (it.length <= 8 && it.all(Char::isDigit)) pin = it }, label = { Text("PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pin2, { if (it.length <= 8 && it.all(Char::isDigit)) pin2 = it }, label = { Text("Repeat PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
            if (pin2.isNotEmpty() && pin != pin2) Text("PINs don't match", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { scope.launch { c.settings.setPin(pin); c.settings.acknowledgeDisclaimer(); delay(100); onDone() } }, enabled = pin.length >= 4 && pin == pin2, modifier = Modifier.fillMaxWidth()) { Text("Save PIN") }
            Text("You can turn on fingerprint/face unlock later in Settings.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}
